package dev.dizabanik.realisticsmoke;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.SmokerBlock;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;

public final class SmokeParticleRenderer {

    private static final long SOURCE_SALT = 0x9E3779B97F4A7C15L;
    private static final long VENT_SALT = 0xD1B54A32D192ED03L;
    private static final long LAYER_SALT = 0x94D049BB133111EBL;

    private long randomState = 0x6A09E667F3BCC909L;
    private long visualEpoch;
    private final CandidateHeap candidates = new CandidateHeap();
    private final Audience audience = new Audience();
    private final BlockPos.Mutable sourcePos = new BlockPos.Mutable();

    @FunctionalInterface
    public interface Passability {
        boolean test(int x, int y, int z);
    }

    public void render(
        ServerWorld world,
        SmokeConfig cfg,
        LongOpenHashSet activeSources,
        Long2FloatOpenHashMap mass,
        Long2IntOpenHashMap components,
        Long2ByteOpenHashMap ceilings,
        Long2ByteOpenHashMap vents,
        Passability passability
    ) {
        long epoch = ++visualEpoch;
        if (
            !cfg.particles ||
            cfg.particleBudgetPerWorld <= 0 ||
            world.getPlayers().isEmpty()
        ) {
            return;
        }
        try {
            audience.prepare(world.getPlayers(), cfg.particleViewDistance);
            int sent = 0;
            int first = firstCategory(
                cfg.particleBudgetPerWorld,
                cfg.sourceParticleBudget,
                cfg.ventParticleBudget,
                epoch
            );
            for (
                int phase = 0;
                phase < 3 && sent < cfg.particleBudgetPerWorld;
                phase++
            ) {
                int remaining = cfg.particleBudgetPerWorld - sent;
                switch ((first + phase) % 3) {
                    case 0 -> sent += renderSources(
                        world,
                        cfg,
                        activeSources,
                        passability,
                        audience,
                        Math.min(
                            Math.max(0, cfg.sourceParticleBudget),
                            remaining
                        ),
                        epoch
                    );
                    case 1 -> sent += renderProfiles(
                        world,
                        cfg,
                        activeSources,
                        mass,
                        components,
                        vents,
                        passability,
                        audience,
                        Math.min(
                            Math.max(0, cfg.ventParticleBudget),
                            remaining
                        ),
                        epoch,
                        true
                    );
                    case 2 -> sent += renderProfiles(
                        world,
                        cfg,
                        activeSources,
                        mass,
                        components,
                        ceilings,
                        passability,
                        audience,
                        remaining,
                        epoch,
                        false
                    );
                    default -> throw new AssertionError();
                }
            }
        } finally {
            audience.clear();
        }
    }

    private int renderSources(
        ServerWorld world,
        SmokeConfig cfg,
        LongOpenHashSet sources,
        Passability passability,
        Audience audience,
        int budget,
        long epoch
    ) {
        if (budget <= 0) {
            return 0;
        }
        candidates.reset(budget);
        var iterator = sources.iterator();
        while (iterator.hasNext()) {
            long key = iterator.nextLong();
            int x = BlockPos.unpackLongX(key);
            int y = BlockPos.unpackLongY(key);
            int z = BlockPos.unpackLongZ(key);
            if (
                !audience.nearCandidate(x + 0.5, y + 0.5, z + 0.5) ||
                !loaded(world, x, y, z)
            ) {
                continue;
            }
            candidates.offer(key, priority(key, epoch, SOURCE_SALT));
        }

        int sent = 0;
        for (int i = 0; i < candidates.size && sent < budget; i++) {
            long key = candidates.keys[i];
            int x = BlockPos.unpackLongX(key);
            int y = BlockPos.unpackLongY(key);
            int z = BlockPos.unpackLongZ(key);
            WorldChunk chunk = loadedChunk(world, x, y, z);
            if (chunk == null) {
                continue;
            }
            BlockState state = chunk.getBlockState(sourcePos.set(x, y, z));
            boolean campfire = state.getBlock() instanceof CampfireBlock;
            boolean furnace = state.getBlock() instanceof AbstractFurnaceBlock;
            if (
                (!campfire && !furnace) ||
                !state.contains(Properties.LIT) ||
                !state.get(Properties.LIT)
            ) {
                continue;
            }
            float emission = campfire
                ? cfg.campfireEmission
                : state.getBlock() instanceof SmokerBlock
                  ? cfg.smokerEmission
                  : cfg.furnaceEmission;
            if (!(emission > 0)) {
                continue;
            }

            double px, py, pz, vx, vz;
            if (passable(world, passability, x, y + 1, z)) {
                px = x + 0.5 + (randomFloat() - 0.5) * 0.16;
                py = y + 1.02;
                pz = z + 0.5 + (randomFloat() - 0.5) * 0.16;
                vx = (randomFloat() - 0.5) * 0.02;
                vz = (randomFloat() - 0.5) * 0.02;
            } else if (furnace && state.contains(AbstractFurnaceBlock.FACING)) {
                Direction facing = state.get(AbstractFurnaceBlock.FACING);
                int exitX = x + facing.getOffsetX();
                int exitZ = z + facing.getOffsetZ();
                if (!passable(world, passability, exitX, y, exitZ)) {
                    continue;
                }
                px = x + 0.5 + facing.getOffsetX() * 0.54;
                py = y + 0.38;
                pz = z + 0.5 + facing.getOffsetZ() * 0.54;
                vx = facing.getOffsetX() * 0.6;
                vz = facing.getOffsetZ() * 0.6;
            } else {
                continue;
            }
            boolean large =
                state.getBlock() instanceof SmokerBlock ||
                (!campfire && ((epoch + mix64(key)) & 1) == 0);
            if (
                send(
                    world,
                    audience,
                    large ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                    px,
                    py,
                    pz,
                    0,
                    vx,
                    1.0,
                    vz,
                    campfire ? 0.03 : 0.04
                )
            ) {
                sent++;
            }
        }
        return sent;
    }

    private int renderProfiles(
        ServerWorld world,
        SmokeConfig cfg,
        LongOpenHashSet sources,
        Long2FloatOpenHashMap mass,
        Long2IntOpenHashMap components,
        Long2ByteOpenHashMap profiles,
        Passability passability,
        Audience audience,
        int budget,
        long epoch,
        boolean vent
    ) {
        if (budget <= 0 || profiles.isEmpty()) {
            return 0;
        }
        candidates.reset(budget);
        long salt = vent ? VENT_SALT : LAYER_SALT;
        var iterator = profiles.long2ByteEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            int x = base(BlockPos.unpackLongX(key));
            int y = base(BlockPos.unpackLongY(key));
            int z = base(BlockPos.unpackLongZ(key));
            if (
                !audience.nearCandidate(x + 1.0, y + 1.0, z + 1.0) ||
                !loaded(world, x, y, z)
            ) {
                continue;
            }
            int component = components.get(key) & 0xff;
            float density = concentration(mass.get(key), component);
            if (
                !(density >= cfg.particleMinDensity) || !Float.isFinite(density)
            ) {
                continue;
            }
            int profile = entry.getByteValue() & 0xff;
            int mask = vent
                ? profile & component
                : ceilingMask(profile, component);
            mask = withoutSources(mask, x, y, z, sources);
            if (mask == 0) {
                continue;
            }
            double nearestSq = nearestProfilePoint(
                audience,
                mask,
                x,
                y,
                z,
                epoch,
                key,
                salt,
                vent
            );
            if (!Double.isFinite(nearestSq)) {
                continue;
            }
            double score = priority(key, epoch, salt);
            if (!vent) {
                double proximity =
                    audience.distanceSq > 0
                        ? 1.0 - Math.min(1.0, nearestSq / audience.distanceSq)
                        : 1.0;
                score = density * (0.82 + 0.32 * score) + 0.30 * proximity;
            }
            candidates.offer(key, score);
        }

        int sent = 0;
        for (int i = 0; i < candidates.size && sent < budget; i++) {
            long key = candidates.keys[i];
            int x = base(BlockPos.unpackLongX(key));
            int y = base(BlockPos.unpackLongY(key));
            int z = base(BlockPos.unpackLongZ(key));
            int component = components.get(key) & 0xff;
            int profile = profiles.get(key) & 0xff;
            int mask = vent
                ? profile & component
                : ceilingMask(profile, component);
            mask = withoutSources(mask, x, y, z, sources);
            int options = Integer.bitCount(mask);
            int start = rotatedRank(key, epoch, salt, options);
            for (int attempt = 0; attempt < options; attempt++) {
                int index = bitAt(mask, (start + attempt) % options);
                int bx = x + (index & 1);
                int by = y + ((index >>> 2) & 1);
                int bz = z + ((index >>> 1) & 1);
                long hash = mix64(key ^ salt ^ epoch ^ index);
                double px =
                    bx + 0.5 + (vent ? 0 : (unitFloat(hash) - 0.5) * 0.46);
                double py = by + (vent ? 0.1 : 0.85);
                double pz =
                    bz +
                    0.5 +
                    (vent ? 0 : (unitFloat(mix64(hash)) - 0.5) * 0.46);
                if (
                    !Double.isFinite(audience.nearest(px, py, pz)) ||
                    !passable(world, passability, bx, by, bz)
                ) {
                    continue;
                }
                float density = concentration(mass.get(key), component);
                double spread = Math.min(0.42, 0.20 + density * 0.018);
                if (
                    send(
                        world,
                        audience,
                        ParticleTypes.LARGE_SMOKE,
                        px,
                        py,
                        pz,
                        vent ? 0 : 1,
                        vent ? 0 : spread,
                        vent ? 1.0 : 0.015,
                        vent ? 0 : spread,
                        vent ? 0.04 : 0.001
                    )
                ) {
                    sent++;
                    break;
                }
            }
        }
        return sent;
    }

    private static double nearestProfilePoint(
        Audience audience,
        int mask,
        int x,
        int y,
        int z,
        long epoch,
        long key,
        long salt,
        boolean vent
    ) {
        double nearest = Double.POSITIVE_INFINITY;
        while (mask != 0) {
            int index = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            long hash = mix64(key ^ salt ^ epoch ^ index);
            double px =
                x +
                (index & 1) +
                0.5 +
                (vent ? 0 : (unitFloat(hash) - 0.5) * 0.46);
            double py = y + ((index >>> 2) & 1) + (vent ? 0.1 : 0.85);
            double pz =
                z +
                ((index >>> 1) & 1) +
                0.5 +
                (vent ? 0 : (unitFloat(mix64(hash)) - 0.5) * 0.46);
            nearest = Math.min(nearest, audience.nearest(px, py, pz));
        }
        return nearest;
    }

    private static int withoutSources(
        int mask,
        int x,
        int y,
        int z,
        LongOpenHashSet sources
    ) {
        int result = mask;
        while (mask != 0) {
            int index = Integer.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            if (
                sources.contains(
                    BlockPos.asLong(
                        x + (index & 1),
                        y + ((index >>> 2) & 1),
                        z + ((index >>> 1) & 1)
                    )
                )
            ) {
                result &= ~(1 << index);
            }
        }
        return result;
    }

    private static WorldChunk loadedChunk(
        ServerWorld world,
        int x,
        int y,
        int z
    ) {
        if (y < world.getBottomY() || y > world.getTopYInclusive()) {
            return null;
        }
        return world.getChunkManager().getWorldChunk(x >> 4, z >> 4);
    }

    private static boolean loaded(ServerWorld world, int x, int y, int z) {
        return loadedChunk(world, x, y, z) != null;
    }

    private static boolean passable(
        ServerWorld world,
        Passability passability,
        int x,
        int y,
        int z
    ) {
        return loaded(world, x, y, z) && passability.test(x, y, z);
    }

    private static boolean send(
        ServerWorld world,
        Audience audience,
        ParticleEffect particle,
        double x,
        double y,
        double z,
        int count,
        double dx,
        double dy,
        double dz,
        double speed
    ) {
        if (
            !loaded(
                world,
                (int) Math.floor(x),
                (int) Math.floor(y),
                (int) Math.floor(z)
            )
        ) {
            return false;
        }
        ArrayList<ServerPlayerEntity> players = audience.at(x, y, z);
        if (players == null) {
            return false;
        }
        boolean attempted = false;
        for (int i = 0; i < players.size(); i++) {
            ServerPlayerEntity player = players.get(i);
            if (audience.eligible(player, x, y, z)) {
                world.spawnParticles(
                    player,
                    particle,
                    false,
                    false,
                    x,
                    y,
                    z,
                    count,
                    dx,
                    dy,
                    dz,
                    speed
                );
                attempted = true;
            }
        }
        return attempted;
    }

    static int firstCategory(
        int total,
        int sourceBudget,
        int ventBudget,
        long epoch
    ) {
        long reserved =
            (long) Math.max(0, sourceBudget) + Math.max(0, ventBudget);
        return total > 0 && reserved >= total
            ? (int) Math.floorMod(epoch, 3L)
            : 0;
    }

    static boolean withinVanillaRange(
        int blockX,
        int blockY,
        int blockZ,
        double x,
        double y,
        double z
    ) {
        double dx = blockX + 0.5 - x;
        double dy = blockY + 0.5 - y;
        double dz = blockZ + 0.5 - z;
        return dx * dx + dy * dy + dz * dz < 32.0 * 32.0;
    }

    static int base(int coordinate) {
        return Math.floorDiv(coordinate, 2) * 2;
    }

    static float concentration(float mass, int component) {
        int blocks = Integer.bitCount(component & 0xff);
        return blocks == 0 ? 0 : mass * (8.0f / blocks);
    }

    static int ceilingMask(int profile, int component) {
        int mask = 0;
        for (int column = 0; column < 4; column++) {
            int level = (profile >>> (column * 2)) & 3;
            if (level == 1 || level == 2) {
                mask |= 1 << (column | ((level - 1) << 2));
            }
        }
        return mask & component & 0xff;
    }

    static int rotatedRank(long key, long epoch, long salt, int options) {
        if (options == 0) {
            return 0;
        }
        return (int) ((Long.remainderUnsigned(mix64(key ^ salt), options) +
            Math.floorMod(epoch, (long) options)) %
            options);
    }

    static int bitAt(int mask, int rank) {
        while (rank-- > 0) {
            mask &= mask - 1;
        }
        return Integer.numberOfTrailingZeros(mask);
    }

    private static double priority(long key, long epoch, long salt) {
        return unitFloat(mix64(key ^ (epoch * salt)));
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }

    private static float unitFloat(long hash) {
        return (float) ((hash >>> 40) * (1.0 / (1 << 24)));
    }

    private float randomFloat() {
        randomState += SOURCE_SALT;
        return unitFloat(mix64(randomState));
    }

    static final class CandidateHeap {

        long[] keys = new long[0];
        double[] scores = new double[0];
        int size;
        private int capacity;

        void reset(int capacity) {
            this.capacity = capacity;
            size = 0;
            if (keys.length < capacity) {
                keys = new long[capacity];
                scores = new double[capacity];
            }
        }

        void offer(long key, double score) {
            if (capacity == 0) {
                return;
            }
            int index;
            if (size < capacity) {
                index = size++;
                while (index > 0) {
                    int parent = (index - 1) >>> 1;
                    if (!less(score, key, scores[parent], keys[parent])) {
                        break;
                    }
                    keys[index] = keys[parent];
                    scores[index] = scores[parent];
                    index = parent;
                }
            } else {
                if (!less(scores[0], keys[0], score, key)) {
                    return;
                }
                index = 0;
                while (index * 2 + 1 < size) {
                    int child = index * 2 + 1;
                    int right = child + 1;
                    if (
                        right < size &&
                        less(
                            scores[right],
                            keys[right],
                            scores[child],
                            keys[child]
                        )
                    ) {
                        child = right;
                    }
                    if (!less(scores[child], keys[child], score, key)) {
                        break;
                    }
                    keys[index] = keys[child];
                    scores[index] = scores[child];
                    index = child;
                }
            }
            keys[index] = key;
            scores[index] = score;
        }

        private static boolean less(
            double score,
            long key,
            double otherScore,
            long otherKey
        ) {
            int comparison = Double.compare(score, otherScore);
            return (
                comparison < 0 ||
                (comparison == 0 && Long.compareUnsigned(key, otherKey) < 0)
            );
        }
    }

    static final class Audience {

        private final Long2ObjectOpenHashMap<
            ArrayList<ServerPlayerEntity>
        > buckets = new Long2ObjectOpenHashMap<>();
        private final ArrayList<ArrayList<ServerPlayerEntity>> listPool =
            new ArrayList<>();
        private int usedLists;
        private double distanceSq;
        private double candidateDistanceSq;

        void prepare(List<ServerPlayerEntity> players, double distance) {
            clear();
            double configured = Double.isFinite(distance)
                ? Math.max(0, distance)
                : 0;
            distanceSq = configured * configured;
            double radius = Math.min(32.0, configured) + 3.0;
            candidateDistanceSq = radius * radius;
            for (int i = 0; i < players.size(); i++) {
                ServerPlayerEntity player = players.get(i);
                int minX = section(player.getX() - radius);
                int maxX = section(player.getX() + radius);
                int minY = section(player.getY() - radius);
                int maxY = section(player.getY() + radius);
                int minZ = section(player.getZ() - radius);
                int maxZ = section(player.getZ() + radius);
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            bucket(BlockPos.asLong(x, y, z)).add(player);
                        }
                    }
                }
            }
        }

        ArrayList<ServerPlayerEntity> bucket(long key) {
            ArrayList<ServerPlayerEntity> players = buckets.get(key);
            if (players == null) {
                if (usedLists == listPool.size()) {
                    listPool.add(new ArrayList<>());
                }
                players = listPool.get(usedLists++);
                buckets.put(key, players);
            }
            return players;
        }

        void clear() {
            for (int i = 0; i < usedLists; i++) {
                listPool.get(i).clear();
            }
            buckets.clear();
            usedLists = 0;
        }

        ArrayList<ServerPlayerEntity> at(double x, double y, double z) {
            return buckets.get(
                BlockPos.asLong(section(x), section(y), section(z))
            );
        }

        boolean nearCandidate(double x, double y, double z) {
            ArrayList<ServerPlayerEntity> players = at(x, y, z);
            if (players != null) {
                for (int i = 0; i < players.size(); i++) {
                    if (
                        players.get(i).squaredDistanceTo(x, y, z) <=
                        candidateDistanceSq
                    ) {
                        return true;
                    }
                }
            }
            return false;
        }

        double nearest(double x, double y, double z) {
            ArrayList<ServerPlayerEntity> players = at(x, y, z);
            double nearest = Double.POSITIVE_INFINITY;
            if (players != null) {
                for (int i = 0; i < players.size(); i++) {
                    ServerPlayerEntity player = players.get(i);
                    if (eligible(player, x, y, z)) {
                        nearest = Math.min(
                            nearest,
                            player.squaredDistanceTo(x, y, z)
                        );
                    }
                }
            }
            return nearest;
        }

        boolean eligible(
            ServerPlayerEntity player,
            double x,
            double y,
            double z
        ) {
            BlockPos pos = player.getBlockPos();
            return (
                player.squaredDistanceTo(x, y, z) <= distanceSq &&
                withinVanillaRange(pos.getX(), pos.getY(), pos.getZ(), x, y, z)
            );
        }

        private static int section(double coordinate) {
            return (int) Math.floor(coordinate / 16.0);
        }
    }
}
