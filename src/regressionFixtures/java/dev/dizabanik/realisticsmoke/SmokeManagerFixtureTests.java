package dev.dizabanik.realisticsmoke;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.SmokerBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
public final class SmokeManagerFixtureTests {
    private static int assertions;
    private static int scenarios;

    private SmokeManagerFixtureTests() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass a scratch directory");
        Path temporary = Files.createTempDirectory(Files.createDirectories(Path.of(args[0])), "smoke-manager-");
        try {
            FabricLoader.getInstance().setConfigDir(temporary.resolve("config"));
            packingAndFakeGuards();
            conservation();
            smallIncomingContributions();
            disconnectedPockets();
            topologySplitAndMerge();
            openingConductance();
            roofsAndNegativeYExhaust();
            bodyAndEyeSampling();
            loadedChunkGuard();
            sourceRevalidationAndFacing();
            boundedGeometry();
            donorReservationAtSaturation();
            strongestCapAndSourceAdmission();
            batchedUnloadCleanup();
            System.out.println("PASS: " + assertions + " assertions; " + scenarios
                + " SmokeManager simulation scenarios with a fake world (no Minecraft launch)");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void near(double actual, double expected, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= 1e-5,
            message + ": expected " + expected + ", got " + actual);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(SmokeManager manager, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = SmokeManager.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(manager, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Error cause) throw cause;
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    private static long key(int x, int y, int z) { return BlockPos.asLong(x, y, z); }

    private static final class Simulation {
        final SmokeManager manager = new SmokeManager();
        final ServerWorld.Server server = new ServerWorld.Server();
        final ServerWorld world = new ServerWorld(server);
        final SmokeConfig cfg;
        final Object state;

        Simulation() throws Exception {
            Constructor<SmokeConfig> constructor = SmokeConfig.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            cfg = constructor.newInstance();
            SmokeConfig.INSTANCE = cfg;
            cfg.simulationIntervalTicks = 1;
            cfg.minDensity = 1e-9f;
            cfg.maxDensity = 128;
            cfg.baseDissipation = 0;
            cfg.outdoorRetention = 1;
            cfg.risingFraction = 0;
            cfg.lateralFractionWhenBlocked = 0;
            cfg.lateralFractionWhileRising = 0;
            cfg.pressureDownFraction = 0;
            cfg.particles = false;
            cfg.maxActiveCellsPerWorld = 1000;
            world.setBackground(BlockState.SOLID);
            world.loadChunk(0, 0);
            BlockPos placeholder = new BlockPos(14, 0, 14);
            manager.onSourceLoaded(world, placeholder);
            manager.onSourceUnloaded(world, placeholder);
            state = ((Map<?, ?>) field(manager, "states")).get(world);
        }

        Long2FloatOpenHashMap density() throws Exception { return (Long2FloatOpenHashMap) field(state, "density"); }
        Long2IntOpenHashMap components() throws Exception { return (Long2IntOpenHashMap) field(state, "components"); }
        LongOpenHashSet set(String name) throws Exception { return (LongOpenHashSet) field(state, name); }
        Long2ByteOpenHashMap profile(String name) throws Exception { return (Long2ByteOpenHashMap) field(state, name); }
        Long2LongLinkedOpenHashMap geometryCache() throws Exception { return (Long2LongLinkedOpenHashMap) field(state, "geometry"); }

        void block(int x, int y, int z, BlockState block) {
            world.setBlock(x, y, z, block);
            manager.invalidateGeometry(world, new BlockPos(x, y, z));
        }

        void mask(int x, int y, int z, int mask) {
            if ((x & 1) != 0 || (y & 1) != 0 || (z & 1) != 0) throw new AssertionError("Expected cell origin");
            for (int i = 0; i < 8; i++) {
                block(x + (i & 1), y + (i >>> 2), z + ((i >>> 1) & 1),
                    (mask & (1 << i)) != 0 ? BlockState.AIR : BlockState.SOLID);
            }
        }

        void seed(int x, int y, int z, int component, float mass) throws Exception {
            int i = Integer.numberOfTrailingZeros(component);
            long key = key(x + (i & 1), y + (i >>> 2), z + ((i >>> 1) & 1));
            density().put(key, mass);
            components().put(key, component);
        }

        SmokeCellGeometry geometry(int x, int y, int z) throws Exception {
            return (SmokeCellGeometry) invoke(manager, "geometry",
                new Class<?>[] {ServerWorld.class, state.getClass(), SmokeConfig.class, long.class},
                world, state, cfg, key(x, y, z));
        }

        float sample(double x, double y, double z) throws Exception {
            return (float) invoke(manager, "densityAt",
                new Class<?>[] {ServerWorld.class, state.getClass(), SmokeConfig.class, double.class, double.class, double.class},
                world, state, cfg, x, y, z);
        }

        int exhaust(int x, int y, int z, int component) throws Exception {
            return (int) invoke(manager, "exhaustProfile",
                new Class<?>[] {ServerWorld.class, state.getClass(), long.class, int.class},
                world, state, key(x, y, z), component);
        }

        void tick() { server.ticks++; manager.tickWorld(world); }
        double mass() throws Exception {
            double total = 0;
            for (float value : density().values()) total += value;
            return total;
        }

        void cleanBuffers() throws Exception {
            for (String name : new String[] {"next", "working"}) {
                check(((Long2FloatOpenHashMap) field(state, name)).isEmpty(), name + " cleared after simulation");
            }
            check(set("selected").isEmpty(), "Selection scratch cleared after simulation");
            check(density().keySet().equals(components().keySet()), "Density/component maps have identical live keys");
        }
    }

    private static void packingAndFakeGuards() {
        scenarios++;
        for (int x : new int[] {-33554432, -17, -1, 0, 16, 33554431}) {
            for (int y : new int[] {-2048, -64, -1, 0, 127, 2047}) {
                for (int z : new int[] {-33554432, -16, -1, 0, 17, 33554431}) {
                    long packed = key(x, y, z);
                    check(BlockPos.unpackLongX(packed) == x, "Signed packed X round trip");
                    check(BlockPos.unpackLongY(packed) == y, "Signed packed Y round trip");
                    check(BlockPos.unpackLongZ(packed) == z, "Signed packed Z round trip");
                }
            }
        }
        ServerWorld world = new ServerWorld(new ServerWorld.Server());
        world.loadChunk(-1, -1);
        var retained = world.getChunkManager().getWorldChunk(-1, -1);
        world.unloadChunk(-1, -1);
        check(world.getChunkManager().getWorldChunk(-1, -1) == null, "Nonloading lookup returns null");
        boolean threw = false;
        try { retained.getBlockState(new BlockPos(-1, -1, -1)); } catch (AssertionError expected) { threw = true; }
        check(threw, "Retained chunk reference throws after unload");
        threw = false;
        try { retained.sampleHeightmap(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, 15, 15); }
        catch (AssertionError expected) { threw = true; }
        check(threw, "Heightmap access throws after unload");
    }

    private static void conservation() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        for (int x = 0; x <= 4; x += 2) for (int y = 0; y <= 4; y += 2) s.mask(x, y, 0, 0xff);
        s.seed(2, 0, 0, 0xff, 1);
        s.cfg.risingFraction = 0.6f;
        s.cfg.lateralFractionWhenBlocked = 0.4f;
        s.cfg.lateralFractionWhileRising = 0.2f;
        s.cfg.pressureDownThreshold = 0;
        s.cfg.pressureDownFraction = 0.1f;
        for (int step = 0; step < 20; step++) {
            s.tick();
            near(s.mass(), 1, "Closed simulation conserves mass at step " + step);
            check(s.density().size() <= 9, "Transport stays in carved room");
            for (float mass : s.density().values()) check(Float.isFinite(mass) && mass > 0, "Finite positive live mass");
            s.cleanBuffers();
        }
        check(s.density().size() > 1, "Conservation case actually transported smoke");
    }

    private static void smallIncomingContributions() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        for (int x = 0; x <= 4; x += 2) s.mask(x, 0, 0, 0xff);
        s.seed(0, 0, 0, 0xff, 0.2f);
        s.seed(4, 0, 0, 0xff, 0.2f);
        s.cfg.minDensity = 0.035f;
        s.cfg.lateralFractionWhenBlocked = 0.4f;
        s.tick();
        near(s.density().get(key(2, 0, 0)), 0.04, "Two individually sub-prune transfers accumulate before pruning");
        near(s.mass(), 0.4, "Small incoming contributions are not lost");
        s.cleanBuffers();
    }

    private static void disconnectedPockets() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.mask(0, 0, 0, 0x81);
        s.seed(0, 0, 0, 0x01, 0.125f);
        near(s.sample(0.5, 0.5, 0.5), 1, "Seeded disconnected pocket has concentration");
        near(s.sample(1.5, 1.5, 1.5), 0, "Other pocket in same grid cell stays clean");
        s.tick();
        near(s.sample(1.5, 1.5, 1.5), 0, "Simulation does not mix disconnected pockets");
        s.mask(0, 0, 0, 0x80);
        near(s.sample(1.5, 1.5, 1.5), 0, "Sampling after wall placement does not teleport smoke");
        s.tick();
        check(s.density().isEmpty(), "Removed pocket displaces smoke instead of moving it through wall");
        s.cleanBuffers();
    }

    private static void topologySplitAndMerge() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.mask(0, 0, 0, 0xff);
        s.seed(0, 0, 0, 0xff, 1);
        s.geometry(0, 0, 0);
        s.mask(0, 0, 0, 0x81);
        near(s.sample(0.5, 0.5, 0.5), 4, "Split is visible to sampling before next simulation");
        near(s.sample(1.5, 1.5, 1.5), 4, "Split allocates old mass by surviving overlap");
        s.tick();
        check(s.density().size() == 2, "Topology split creates two representatives");
        near(s.density().get(key(0, 0, 0)), 0.5, "First split mass");
        near(s.density().get(key(1, 1, 1)), 0.5, "Second split mass");
        check(s.components().get(key(0, 0, 0)) == 1 && s.components().get(key(1, 1, 1)) == 0x80,
            "Split component maps reflect new topology");
        s.mask(0, 0, 0, 0x8b);
        near(s.sample(1.5, 0.5, 1.5), 2, "New connecting block samples both old pockets before simulation");
        s.tick();
        check(s.density().size() == 1, "Merge collapses representatives");
        near(s.mass(), 1, "Split/merge preserves surviving mass");
        check(s.components().get(key(0, 0, 0)) == 0x8b, "Merged component map updated");
        s.cleanBuffers();
    }

    private static double upwardTransfer(int openingMask) throws Exception {
        Simulation s = new Simulation();
        s.mask(0, 0, 0, 0xff);
        s.mask(0, 2, 0, openingMask);
        s.seed(0, 0, 0, 0xff, 1);
        s.cfg.risingFraction = 0.5f;
        s.tick();
        near(s.mass(), 1, "Opening conductance preserves total mass");
        return s.density().get(key(0, 2, 0));
    }

    private static void openingConductance() throws Exception {
        scenarios++;
        double one = upwardTransfer(0x01);
        double four = upwardTransfer(0xff);
        near(one, 0.125, "One-block opening transfer");
        near(four, 0.5, "Four-block opening transfer");
        near(four, one * 4, "Conductance scales by matched face openings");
    }

    private static void roofsAndNegativeYExhaust() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.world.setBackground(BlockState.AIR);
        s.mask(0, -32, 0, 0xff);
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) s.block(x, -30, z, BlockState.GLASS);
        s.seed(0, -32, 0, 0xff, 1);
        s.cfg.particles = true;
        s.cfg.particleMinDensity = 0;
        near(s.exhaust(0, -32, 0, 0xff), 0, "Negative-Y glass roof blocks exhaust");
        s.cfg.outdoorRetention = 0.1f;
        s.tick();
        near(s.mass(), 1, "Glass roof prevents outdoor dissipation");
        check(s.profile("vents").isEmpty(), "Glass roof creates no vent profile");
        check((s.profile("ceilings").get(key(0, -32, 0)) & 0xff) == 0xaa, "Glass roof produces four ceiling levels");
        for (int x = 0; x < 2; x++) for (int z = 0; z < 2; z++) s.block(x, -30, z, BlockState.DECOR);
        near(s.exhaust(0, -32, 0, 0xff), 0xf0, "Passable decoration skipped down to negative world bottom");
        s.tick();
        near(s.mass(), 0.1, "Decorated open sky applies configured outdoor retention");
        check((s.profile("vents").get(key(0, -32, 0)) & 0xff) == 0xf0, "Unsigned negative-Y exhaust profile retains upper bits");
        check(s.profile("ceilings").isEmpty(), "Passable decor does not make a ceiling");
        s.block(0, -30, 0, BlockState.WATER);
        near(s.exhaust(0, -32, 0, 0xff), 0xe0, "Fluid blocks its sky column despite empty collision shape");
        s.block(0, -30, 0, new BlockState(new net.minecraft.block.Block(), false, true, false).with(Properties.OPEN, true));
        near(s.exhaust(0, -32, 0, 0xff), 0xf0, "OPEN property permits exhaust despite nonempty shape");
        s.cleanBuffers();
    }

    private static double exposure(Simulation s, UUID id) throws Exception {
        Map<?, ?> players = (Map<?, ?>) field(field(s.manager, "exposure"), "players");
        return (double) field(players.get(id), "exposure");
    }

    private static void bodyAndEyeSampling() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.world.loadChunk(0, -1);
        s.mask(0, 62, -4, 0xff);
        s.mask(0, 64, -4, 0xff);
        s.seed(0, 62, -4, 0xff, 4);
        s.cfg.simulationIntervalTicks = 1000;
        ServerPlayerEntity player = new ServerPlayerEntity(new UUID(0, 1));
        s.world.getPlayers().add(player);
        near(s.sample(1.25, 63.5, -3.5), 4, "Body coordinate samples smoke");
        near(s.sample(player.eye.x, player.eye.y, player.eye.z), 0, "Clean eye cell does not sample smoky body");
        s.tick();
        for (int tick = 0; tick < 20; tick++) s.tick();
        near(exposure(s, player.getUuid()), 0, "Manager wires eye-only sampling to real tracker");
        s.seed(0, 64, -4, 0xff, 2);
        for (int tick = 0; tick < 20; tick++) s.tick();
        near(exposure(s, player.getUuid()), (2 - s.cfg.safeDensity) * s.cfg.exposureGainPerSecond,
            "Manager sampler feeds actual eye concentration to tracker");
        check(s.density().size() == 2 && s.components().size() == 2, "Sampling does not rewrite simulation maps");
        s.manager.removePlayer(player.getUuid());
        check(((Map<?, ?>) field(field(s.manager, "exposure"), "players")).isEmpty(), "Manager player removal delegates to tracker");
    }

    private static void loadedChunkGuard() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.world.loadChunk(-1, -1);
        s.mask(-2, -64, -2, 0xff);
        s.seed(-2, -64, -2, 0xff, 1);
        s.geometry(-2, -64, -2);
        s.world.unloadChunk(-1, -1);
        int reads = s.world.blockReads;
        near(s.sample(-1.5, -63.5, -1.5), 0, "Unloaded cached pocket samples clean air");
        check(s.geometry(-2, -64, -2).mask() == 0, "Loaded check precedes geometry cache hit");
        near(s.exhaust(-2, -64, -2, 0xff), 0, "Unloaded exhaust returns no profile");
        s.manager.onSourceLoaded(s.world, new BlockPos(-1, -64, -1));
        s.tick();
        check(s.density().isEmpty(), "Unloaded donor is discarded without block reads");
        check(s.world.blockReads == reads, "No unloaded block reads during sample/source/simulation");
        check(s.world.forcingReads == 0 && s.world.loadedLookups > 0, "Manager uses only nonloading chunk lookups");
        near(s.sample(0, -65, 0), 0, "Below build limit is clean");
        near(s.sample(0, 128, 0), 0, "Above build limit is clean");
        s.cleanBuffers();
    }

    private static void sourceRevalidationAndFacing() throws Exception {
        scenarios++;
        for (Direction facing : Direction.values()) {
            Simulation s = new Simulation();
            int x = 6, y = 0, z = 6;
            BlockPos source = new BlockPos(x, y, z);
            long output = key(x + facing.getOffsetX(), y, z + facing.getOffsetZ());
            s.block(x + facing.getOffsetX(), y, z + facing.getOffsetZ(), BlockState.AIR);
            s.block(x, y, z, BlockState.source(new AbstractFurnaceBlock(), true, facing));
            s.manager.onSourceLoaded(s.world, source);
            s.tick();
            near(s.density().get(output), s.cfg.furnaceEmission, "Blocked top uses furnace facing " + facing);
            check(s.set("activeSources").contains(source.asLong()), "Lit source active");
            s.block(x, y, z, BlockState.source(new AbstractFurnaceBlock(), false, facing));
            s.tick();
            near(s.mass(), s.cfg.furnaceEmission, "Unlit source emits nothing");
            check(s.set("sources").contains(source.asLong()) && s.set("activeSources").isEmpty(), "Unlit source stays registered, not active");
            s.block(x, y, z, BlockState.SOLID);
            s.tick();
            check(s.set("sources").isEmpty(), "Replaced source removed on revalidation");
            check(((Long2ObjectOpenHashMap<?>) field(s.state, "sourcesByChunk")).isEmpty(), "Revalidation cleans chunk source index");
            s.cleanBuffers();
        }
        Simulation top = new Simulation();
        top.block(6, 1, 6, BlockState.AIR);
        top.block(7, 0, 6, BlockState.AIR);
        top.block(6, 0, 6, BlockState.source(new SmokerBlock(), true, Direction.EAST));
        top.manager.onSourceLoaded(top.world, new BlockPos(6, 0, 6));
        top.tick();
        near(top.density().get(key(6, 1, 6)), top.cfg.smokerEmission, "Above pocket preferred over disconnected facing pocket");
        near(top.density().get(key(7, 0, 6)), 0, "Facing fallback not used when top is passable");
    }

    private static void boundedGeometry() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.cfg.maxGeometryCacheCellsPerWorld = 3;
        s.cfg.passabilityCacheClearSteps = 2;
        for (int x = 0; x < 16; x += 2) {
            s.geometry(x, 0, 0);
            check(s.geometryCache().size() <= 3, "Geometry LRU never exceeds configured capacity");
        }
        check(!s.geometryCache().containsKey(key(0, 0, 0)), "LRU evicts oldest cell");
        s.mask(0, 0, 0, 0xff);
        s.seed(0, 0, 0, 0xff, 1);
        for (int tick = 0; tick < 6; tick++) {
            s.tick();
            check(s.geometryCache().size() <= 3, "Simulation/profile reads respect small geometry budget");
            check(((Long2IntLinkedOpenHashMap) field(s.state, "columnTops")).size() <= 12,
                "Column cache respects four times geometry budget");
            near(s.mass(), 1, "Cache eviction and periodic clearing preserve mass");
        }
    }

    private static void donorReservationAtSaturation() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.cfg.maxActiveCellsPerWorld = 1;
        s.cfg.risingFraction = 1;
        Long2FloatOpenHashMap iterationOrder = new Long2FloatOpenHashMap();
        for (int x = 0; x < 20; x += 4) for (int z = 0; z < 20; z += 4) {
            if (x >= 16 || z >= 16) s.world.loadChunk(x >> 4, z >> 4);
            s.mask(x, 0, z, 0xff);
            s.mask(x, 2, z, 0xff);
            s.seed(x, 0, z, 0xff, 0.01f);
            iterationOrder.put(key(x, 0, z), 0.01f);
        }
        long last = 0;
        var order = iterationOrder.keySet().iterator();
        while (order.hasNext()) last = order.nextLong();
        s.density().put(last, 1);
        s.tick();
        check(s.density().size() == 1, "Final active cap enforced after saturated transport");
        check(s.density().containsKey(last), "Strongest late-iteration donor remains reserved");
        near(s.density().get(last), 1, "Rejected destination preserves full donor mass (lost-mass regression)");
        s.cleanBuffers();
    }

    private static void strongestCapAndSourceAdmission() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.cfg.maxActiveCellsPerWorld = 2;
        s.mask(0, 0, 0, 0xff);
        s.mask(4, 0, 0, 1);
        s.mask(8, 0, 0, 0xff);
        s.seed(0, 0, 0, 0xff, 0.8f);
        s.seed(4, 0, 0, 1, 0.2f);
        s.seed(8, 0, 0, 0xff, 0.9f);
        s.tick();
        check(s.density().size() == 2, "Strongest selection enforces cap");
        check(!s.density().containsKey(key(0, 0, 0)) && s.density().containsKey(key(4, 0, 0))
            && s.density().containsKey(key(8, 0, 0)), "Final cap selects concentration, not raw mass");
        s.cleanBuffers();

        Simulation admission = new Simulation();
        admission.cfg.maxActiveCellsPerWorld = 1;
        admission.cfg.furnaceEmission = 0.1f;
        admission.cfg.smokerEmission = 0.2f;
        admission.cfg.campfireEmission = 0.3f;
        for (int x : new int[] {0, 4, 8}) {
            admission.mask(x, 2, 0, 0xff);
            net.minecraft.block.Block block = x == 0 ? new AbstractFurnaceBlock() : x == 4 ? new SmokerBlock() : new CampfireBlock();
            admission.block(x, 1, 0, BlockState.source(block, true, Direction.EAST));
            admission.manager.onSourceLoaded(admission.world, new BlockPos(x, 1, 0));
        }
        admission.block(9, 1, 0, BlockState.source(new AbstractFurnaceBlock(), true, Direction.EAST));
        admission.manager.onSourceLoaded(admission.world, new BlockPos(9, 1, 0));
        admission.tick();
        check(admission.density().size() == 1 && admission.density().containsKey(key(8, 2, 0)),
            "Overload admission keeps strongest individual source pocket");
        near(admission.mass(), 0.4, "Every source sharing admitted output accumulates");
        check(admission.set("activeSources").size() == 4, "Nonadmitted lit sources remain active");
        check(((SmokeSourceAdmission) field(admission.state, "admission")).size() == 1, "Source admission itself is bounded");
        admission.cleanBuffers();
    }

    private static void batchedUnloadCleanup() throws Exception {
        scenarios++;
        Simulation s = new Simulation();
        s.world.loadChunk(1, 0);
        s.world.loadChunk(2, 0);
        for (int x : new int[] {0, 16, 32}) {
            s.mask(x, 0, 0, 0xff);
            s.seed(x, 0, 0, 0xff, 1);
            s.geometry(x, 0, 0);
            s.profile("ceilings").put(key(x, 0, 0), (byte) 0xaa);
            s.profile("vents").put(key(x, 0, 0), (byte) 0xf0);
            s.manager.onSourceLoaded(s.world, new BlockPos(x, 4, 0));
            s.set("activeSources").add(key(x, 4, 0));
        }
        ((Long2IntLinkedOpenHashMap) field(s.state, "columnTops")).put(key(0, 0, 0), 2);
        s.cfg.simulationIntervalTicks = 1000;
        for (int x : new int[] {0, 1}) {
            s.world.unloadChunk(x, 0);
            s.manager.onChunkUnloaded(s.world, new ChunkPos(x, 0));
        }
        check(s.density().size() == 3, "Unload callbacks defer field traversal until tick");
        check(s.set("unloadedChunks").size() == 2, "Unload callbacks batch two chunks");
        check(s.set("sources").size() == 1 && s.set("activeSources").size() == 1, "Source indexes cleaned immediately");
        s.tick();
        check(s.density().size() == 1 && s.density().containsKey(key(32, 0, 0)), "Tick purges only unloaded density");
        check(s.geometryCache().size() == 1 && s.geometryCache().containsKey(key(32, 0, 0)), "Tick purges unloaded geometry");
        check(s.profile("ceilings").size() == 1 && s.profile("vents").size() == 1, "Tick purges unloaded profiles");
        check(((Long2IntLinkedOpenHashMap) field(s.state, "columnTops")).isEmpty(), "Batched purge clears column cache");
        check(s.set("unloadedChunks").isEmpty(), "Unload batch cleared after purge");
        s.cleanBuffers();
        s.cfg.simulationIntervalTicks = 1;
        s.tick();
        near(s.mass(), 1, "Loaded field survives following simulation");
        check(s.world.forcingReads == 0, "Unload handling never forces chunk reads");
        s.cleanBuffers();
        s.manager.onWorldUnloaded(s.world);
        check(((Map<?, ?>) field(s.manager, "states")).isEmpty(), "World unload drops all world state");
        s.manager.onSourceLoaded(s.world, new BlockPos(32, 4, 0));
        s.manager.clear();
        check(((Map<?, ?>) field(s.manager, "states")).isEmpty(), "Session clear drops all world state");
        check(((Map<?, ?>) field(field(s.manager, "exposure"), "players")).isEmpty(), "Session clear drops exposure state");
    }
}
