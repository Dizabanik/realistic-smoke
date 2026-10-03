package dev.dizabanik.realisticsmoke;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class SmokeParticleRendererTest {

    public static void main(String[] args) {
        checkAlignment();
        checkConcentration();
        checkCeilings();
        checkRotation();
        checkHeap();
        checkCategoryBudgets();
        checkVanillaRange();
        checkAudienceReuse();
        System.out.println(
                "SmokeParticleRendererTest passed: profiles, geometry, selection, reserved budgets, vanilla distance semantics, and audience cleanup/reuse.");
    }

    private static void checkAlignment() {
        for (int coordinate = -129; coordinate <= 129; coordinate++) {
            int base = SmokeParticleRenderer.base(coordinate);
            check(
                    base % 2 == 0 && coordinate >= base && coordinate < base + 2,
                    "representative block alignment " + coordinate);
        }
        check(SmokeParticleRenderer.base(-1) == -2, "negative odd block");
        check(SmokeParticleRenderer.base(-63) == -64, "underground vent cell");
        check(
                SmokeParticleRenderer.base(17) == 16,
                "block key is not doubled cell coordinate");
    }

    private static void checkConcentration() {
        for (int component = 1; component < 256; component++) {
            float density = SmokeParticleRenderer.concentration(
                    0.75f,
                    component);
            check(
                    Math.abs(
                            (density * Integer.bitCount(component)) / 8.0f - 0.75f) < 0.000001f,
                    "mass conservation " + component);
        }
        check(
                SmokeParticleRenderer.concentration(1, 0) == 0,
                "empty component");
        check(
                SmokeParticleRenderer.concentration(1, 1) == 8,
                "single-block pocket");
        check(
                SmokeParticleRenderer.concentration(1, -1) == 1,
                "low eight component bits");
    }

    private static void checkCeilings() {
        for (int profile = 0; profile < 256; profile++) {
            for (int component = 0; component < 256; component++) {
                int expected = 0;
                for (int index = 0; index < 8; index++) {
                    int column = index % 4;
                    int level = (profile / (1 << (column * 2))) % 4;
                    int height = index / 4;
                    if ((component & (1 << index)) != 0 && level == height + 1) {
                        expected |= 1 << index;
                    }
                }
                check(
                        SmokeParticleRenderer.ceilingMask(profile, component) == expected,
                        "profile/component " + profile + "/" + component);
                check(
                        SmokeParticleRenderer.ceilingMask(
                                (byte) profile,
                                component) == expected,
                        "signed byte profile " + profile);
            }
        }
        check(
                SmokeParticleRenderer.ceilingMask(0xaa, 0x0f) == 0,
                "upper ceiling never renders in lower disconnected component");
        check(
                SmokeParticleRenderer.ceilingMask(0x55, 0xf0) == 0,
                "lower ceiling never renders in upper disconnected component");
    }

    private static void checkRotation() {
        for (int mask = 1; mask < 256; mask++) {
            int count = Integer.bitCount(mask);
            for (long key : new long[] { 0, 1, -1, 0x0123456789abcdefL }) {
                int visited = 0;
                for (long epoch = 101; epoch < 101 + count; epoch++) {
                    int rank = SmokeParticleRenderer.rotatedRank(
                            key,
                            epoch,
                            0x123456789abcdefL,
                            count);
                    int index = SmokeParticleRenderer.bitAt(mask, rank);
                    check(
                            (mask & (1 << index)) != 0,
                            "rotation selects profiled block");
                    visited |= 1 << index;
                }
                check(
                        visited == mask,
                        "every vent/ceiling block visited " + mask);
            }
        }
        check(
                SmokeParticleRenderer.rotatedRank(0, 1, 1, 0) == 0,
                "empty profile");
    }

    private static void checkHeap() {
        Random random = new Random(1234);
        SmokeParticleRenderer.CandidateHeap heap = new SmokeParticleRenderer.CandidateHeap();
        for (int capacity = 0; capacity <= 32; capacity++) {
            for (int trial = 0; trial < 20; trial++) {
                int[] order = new int[100];
                for (int i = 0; i < order.length; i++) {
                    order[i] = i;
                }
                for (int i = order.length - 1; i > 0; i--) {
                    int swap = random.nextInt(i + 1);
                    int value = order[i];
                    order[i] = order[swap];
                    order[swap] = value;
                }
                heap.reset(capacity);
                for (int key : order) {
                    heap.offer(key, key / 4);
                    check(heap.size <= capacity, "strict heap capacity");
                    for (int child = 1; child < heap.size; child++) {
                        int parent = (child - 1) / 2;
                        check(
                                heap.scores[parent] <= heap.scores[child],
                                "min-heap invariant");
                    }
                }
                check(heap.size == capacity, "heap filled");
                long[] actual = Arrays.copyOf(heap.keys, heap.size);
                Arrays.sort(actual);
                for (int i = 0; i < capacity; i++) {
                    check(
                            actual[i] == 100 - capacity + i,
                            "top scores independent of iteration order " + capacity);
                }
            }
        }
        heap.reset(2);
        heap.offer(0, 1);
        heap.offer(Long.MIN_VALUE, 1);
        heap.offer(-1, 1);
        check(
                (heap.keys[0] == Long.MIN_VALUE && heap.keys[1] == -1) ||
                        (heap.keys[1] == Long.MIN_VALUE && heap.keys[0] == -1),
                "unsigned key tie order");
    }

    private static void checkCategoryBudgets() {
        for (long epoch = 0; epoch < 30; epoch++) {
            check(
                    SmokeParticleRenderer.firstCategory(64, 16, 12, epoch) == 0,
                    "default reservations always precede ceilings");
            int[] normal = modelBudgets(64, 16, 12, epoch);
            check(
                    Arrays.equals(normal, new int[] { 16, 12, 36 }),
                    "default category allocations");
            check(
                    SmokeParticleRenderer.firstCategory(64, -1, 12, epoch) == 0,
                    "negative cap cannot trigger rotation");
            check(
                    SmokeParticleRenderer.firstCategory(
                            Integer.MAX_VALUE,
                            Integer.MAX_VALUE,
                            Integer.MAX_VALUE,
                            epoch) == epoch % 3,
                    "reservation sum cannot overflow");
        }
        for (int total = 1; total <= 12; total++) {
            for (int source = 0; source <= 12; source++) {
                for (int vent = 0; vent <= 12; vent++) {
                    for (long epoch = 0; epoch < 6; epoch++) {
                        int first = SmokeParticleRenderer.firstCategory(
                                total,
                                source,
                                vent,
                                epoch);
                        check(
                                first == (source + vent >= total ? epoch % 3 : 0),
                                "rotate only on exhaustion/oversubscription");
                        int[] allocation = modelBudgets(
                                total,
                                source,
                                vent,
                                epoch);
                        check(
                                allocation[0] <= source && allocation[1] <= vent,
                                "category caps");
                        check(
                                Arrays.stream(allocation).sum() == total,
                                "strict shared budget");
                        if (source + vent < total) {
                            check(
                                    allocation[0] == source &&
                                            allocation[1] == vent,
                                    "both upcoming categories reserved");
                        }
                    }
                }
            }
        }
    }

    private static int[] modelBudgets(
            int total,
            int source,
            int vent,
            long epoch) {
        int[] allocation = new int[3];
        int remaining = total;
        int first = SmokeParticleRenderer.firstCategory(
                total,
                source,
                vent,
                epoch);
        for (int phase = 0; phase < 3 && remaining > 0; phase++) {
            int category = (first + phase) % 3;
            int cap = category == 0 ? source : category == 1 ? vent : remaining;
            allocation[category] = Math.min(Math.max(0, cap), remaining);
            remaining -= allocation[category];
        }
        return allocation;
    }

    private static void checkVanillaRange() {
        int bx = -17,
                by = -63,
                bz = 31;
        double x = bx + 0.5,
                y = by + 0.5,
                z = bz + 0.5;
        check(
                SmokeParticleRenderer.withinVanillaRange(bx, by, bz, x, y, z),
                "block center");
        check(
                !SmokeParticleRenderer.withinVanillaRange(bx, by, bz, x + 32, y, z),
                "vanilla excludes exact 32-block boundary");
        check(
                SmokeParticleRenderer.withinVanillaRange(
                        bx,
                        by,
                        bz,
                        x + 32 - 1.0e-10,
                        y,
                        z),
                "vanilla includes just inside boundary");
        check(
                !SmokeParticleRenderer.withinVanillaRange(bx, by, bz, x, y - 32, z),
                "negative-Y exact boundary");
        check(
                !SmokeParticleRenderer.withinVanillaRange(
                        bx,
                        by,
                        bz,
                        Double.NaN,
                        y,
                        z),
                "NaN rejected");
        Random random = new Random(9876);
        for (int i = 0; i < 10_000; i++) {
            bx = random.nextInt(2000) - 1000;
            by = random.nextInt(256) - 128;
            bz = random.nextInt(2000) - 1000;
            x = bx + random.nextDouble() * 80 - 40;
            y = by + random.nextDouble() * 80 - 40;
            z = bz + random.nextDouble() * 80 - 40;
            boolean vanilla = new BlockPos(bx, by, bz).isWithinDistance(
                    new Vec3d(x, y, z),
                    32.0);
            check(
                    SmokeParticleRenderer.withinVanillaRange(bx, by, bz, x, y, z) == vanilla,
                    "scalar math matches mapped vanilla " + i);
        }
    }

    private static void checkAudienceReuse() {
        SmokeParticleRenderer.Audience audience = new SmokeParticleRenderer.Audience();
        long firstKey = BlockPos.asLong(-1, -4, 2);
        long secondKey = BlockPos.asLong(3, 5, -7);
        var first = audience.bucket(firstKey);
        var second = audience.bucket(secondKey);
        check(
                first != second && audience.bucket(firstKey) == first,
                "distinct/reused buckets");
        first.add(null);
        second.add(null);
        check(
                audience.at(-0.1, -63, 32) == first,
                "negative chunk/section lookup");
        try {
            throw new IllegalStateException("simulated callback failure");
        } catch (IllegalStateException expected) {
        } finally {
            audience.clear();
        }
        check(
                first.isEmpty() && second.isEmpty(),
                "every used list releases references");
        check(audience.at(-0.1, -63, 32) == null, "bucket map cleared");
        check(
                audience.bucket(secondKey) == first,
                "pool reusable after coordinates change");
        first.add(null);
        audience.prepare(List.of(), 48);
        check(
                first.isEmpty() && audience.at(48, 80, -112) == null,
                "prepare resets stale contents");
        check(
                !audience.nearCandidate(0, 0, 0) &&
                        Double.isInfinite(audience.nearest(0, 0, 0)),
                "empty audience queries");
        audience.clear();
        audience.clear();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
