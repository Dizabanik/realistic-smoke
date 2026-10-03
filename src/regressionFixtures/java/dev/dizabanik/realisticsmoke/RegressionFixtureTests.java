package dev.dizabanik.realisticsmoke;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public final class RegressionFixtureTests {
    private static int assertions;
    private static Path configFile;

    private RegressionFixtureTests() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Pass a scratch directory, e.g. build/regressionFixtures/work");
        }
        Path scratch = Files.createDirectories(Path.of(args[0]));
        Path temporary = Files.createTempDirectory(scratch, "smoke-regression-");
        try {
            Path configDir = temporary.resolve("nested/config");
            FabricLoader.getInstance().setConfigDir(configDir);
            configFile = configDir.resolve("realistic-smoke.json");
            configDefaultsAndWrites();
            configNonFiniteFields();
            configMalformedPreservation();
            configFailedWriteCleanup();
            tinyGainsAndRecovery();
            dimensionsAndElapsedTime();
            damageCadence();
            rejectedDamageBudget();
            lifecycleResets();
            effectDurations();
            badSamplerAndTickWraparound();
            System.out.println(
                    "PASS: " + assertions + " assertions; SmokeConfig and SmokeExposureTracker isolated regressions");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition)
            throw new AssertionError(message);
    }

    private static void near(double actual, double expected, String message) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= 1e-6,
                message + ": expected " + expected + ", got " + actual);
    }

    private static SmokeConfig defaults() throws Exception {
        Constructor<SmokeConfig> constructor = SmokeConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static List<Field> configFields() {
        return Arrays.stream(SmokeConfig.class.getFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers())).toList();
    }

    private static void sameConfig(SmokeConfig actual, SmokeConfig expected, String context) throws Exception {
        for (Field field : configFields()) {
            check(field.get(actual).equals(field.get(expected)), context + ": " + field.getName());
        }
    }

    private static void noTemporaryConfigFiles() throws Exception {
        try (var files = Files.list(configFile.getParent())) {
            check(files.map(path -> path.getFileName().toString()).toList()
                    .equals(List.of("realistic-smoke.json")), "No stranded temporary config files");
        }
    }

    private static void configDefaultsAndWrites() throws Exception {
        check(!Files.exists(configFile.getParent()), "Missing config directory initially");
        SmokeConfig.load();
        sameConfig(SmokeConfig.INSTANCE, defaults(), "Missing config uses defaults");
        sameConfig(new Gson().fromJson(Files.readString(configFile), SmokeConfig.class),
                defaults(), "Initial saved config round trip");
        noTemporaryConfigFiles();
        for (int interval : new int[] { 5, 37, 100 }) {
            Files.writeString(configFile, "{\"exposureIntervalTicks\":" + interval + ",\"safeDensity\":1.25}");
            SmokeConfig.load();
            check(SmokeConfig.INSTANCE.exposureIntervalTicks == interval, "Valid config loaded");
            near(SmokeConfig.INSTANCE.safeDensity, 1.25, "Valid float loaded");
            sameConfig(new Gson().fromJson(Files.readString(configFile), SmokeConfig.class),
                    SmokeConfig.INSTANCE, "Replacement is complete readable JSON");
            noTemporaryConfigFiles();
        }
        Files.writeString(configFile, "{}");
        SmokeConfig.load();
        sameConfig(SmokeConfig.INSTANCE, defaults(), "Missing fields use constructor defaults");
        Files.writeString(configFile, "{\"exposureIntervalTicks\":-1,\"severeExposure\":0.1,"
                + "\"nauseaExposure\":20,\"lethalExposure\":1,\"baseDamagePerSecond\":10,"
                + "\"maxDamagePerSecond\":1,\"maxDensity\":2,\"safeDensity\":99}");
        SmokeConfig.load();
        check(SmokeConfig.INSTANCE.exposureIntervalTicks == 5, "Interval lower bound");
        near(SmokeConfig.INSTANCE.severeExposure, 20, "Severe threshold ordered");
        near(SmokeConfig.INSTANCE.lethalExposure, 20, "Lethal threshold ordered");
        near(SmokeConfig.INSTANCE.maxDamagePerSecond, 10, "Damage bounds ordered");
        near(SmokeConfig.INSTANCE.safeDensity, 2, "Safe density bounded by max density");
    }

    private static void configNonFiniteFields() throws Exception {
        Method sanitize = SmokeConfig.class.getDeclaredMethod("sanitize");
        sanitize.setAccessible(true);
        SmokeConfig expected = defaults();
        List<Field> floatingFields = configFields().stream()
                .filter(field -> field.getType() == float.class || field.getType() == double.class).toList();
        check(!floatingFields.isEmpty(), "Discovered config floating-point fields");
        for (double invalid : new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY }) {
            for (Field field : floatingFields) {
                SmokeConfig candidate = defaults();
                if (field.getType() == float.class)
                    field.setFloat(candidate, (float) invalid);
                else
                    field.setDouble(candidate, invalid);
                sanitize.invoke(candidate);
                sameConfig(candidate, expected, "Non-finite default: " + field.getName() + "=" + invalid);
            }
            StringBuilder json = new StringBuilder("{");
            for (Field field : floatingFields) {
                if (json.length() > 1)
                    json.append(',');
                json.append('"').append(field.getName()).append("\":\"").append(invalid).append('"');
            }
            Files.writeString(configFile, json.append('}').toString());
            SmokeConfig.load();
            sameConfig(SmokeConfig.INSTANCE, expected, "All non-finite JSON fields=" + invalid);
            String saved = Files.readString(configFile);
            check(!saved.contains("NaN") && !saved.contains("Infinity"), "Saved JSON has finite values");
            sameConfig(new Gson().fromJson(saved, SmokeConfig.class), expected, "Finite defaults saved");
            noTemporaryConfigFiles();
        }
    }

    private static void configMalformedPreservation() throws Exception {
        for (String malformed : new String[] { "{", "null", "[]", "{\"exposureIntervalTicks\":\"broken\"}" }) {
            byte[] bytes = malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            Files.write(configFile, bytes);
            int warnings = RealisticSmokeMod.LOGGER.warningCount();
            SmokeConfig.load();
            sameConfig(SmokeConfig.INSTANCE, defaults(), "Malformed config uses defaults");
            check(Arrays.equals(bytes, Files.readAllBytes(configFile)), "Malformed bytes preserved: " + malformed);
            check(RealisticSmokeMod.LOGGER.warningCount() == warnings + 1, "Malformed config warns");
            noTemporaryConfigFiles();
        }
    }

    private static void configFailedWriteCleanup() throws Exception {
        Files.delete(configFile);
        Files.createDirectory(configFile);
        Path marker = configFile.resolve("preserve-me");
        Files.writeString(marker, "unchanged");
        SmokeConfig.INSTANCE = defaults();
        Method save = SmokeConfig.class.getDeclaredMethod("save");
        save.setAccessible(true);
        int warnings = RealisticSmokeMod.LOGGER.warningCount();
        save.invoke(null);
        check(RealisticSmokeMod.LOGGER.warningCount() == warnings + 1, "Failed replacement warns");
        check(Files.readString(marker).equals("unchanged"), "Failed replacement preserves destination");
        noTemporaryConfigFiles();
        Files.delete(marker);
        Files.delete(configFile);
    }

    private static final class Scenario {
        final ServerWorld.Server server = new ServerWorld.Server();
        final ServerWorld world = new ServerWorld(server);
        final SmokeExposureTracker tracker = new SmokeExposureTracker();
        final SmokeConfig cfg;
        ServerPlayerEntity player = new ServerPlayerEntity(UUID.randomUUID());
        float density = 1;
        int samples;

        Scenario(int interval) throws Exception {
            cfg = defaults();
            cfg.exposureIntervalTicks = interval;
            cfg.safeDensity = 0;
            cfg.exposureGainPerSecond = 1;
            cfg.exposureRecoveryPerSecond = 1;
            world.getPlayers().add(player);
        }

        float sample(double x, double y, double z) {
            near(x, player.eye.x, "Eye x only");
            near(y, player.eye.y, "Eye y only");
            near(z, player.eye.z, "Eye z only");
            samples++;
            return density;
        }

        void tick(int tick) {
            server.ticks = tick;
            tracker.tick(world, cfg, this::sample);
        }

        void run(int from, int through) {
            for (int tick = from; tick <= through; tick++)
                tick(tick);
        }

        void primeLethal() throws Exception {
            cfg.exposureGainPerSecond = 0;
            cfg.exposureRecoveryPerSecond = 0;
            cfg.baseDamagePerSecond = cfg.maxDamagePerSecond = 4;
            tick(0);
            setState(tracker, player.getUuid(), "exposure", cfg.lethalExposure + 100);
        }
    }

    private static Map<?, ?> states(SmokeExposureTracker tracker) throws Exception {
        Field field = SmokeExposureTracker.class.getDeclaredField("players");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(tracker);
    }

    private static double state(SmokeExposureTracker tracker, UUID id, String name) throws Exception {
        Object entry = states(tracker).get(id);
        check(entry != null, "Player state present for " + name);
        Field field = entry.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(entry);
    }

    private static void setState(SmokeExposureTracker tracker, UUID id, String name, double value) throws Exception {
        Object entry = states(tracker).get(id);
        Field field = entry.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setDouble(entry, value);
    }

    private static void tinyGainsAndRecovery() throws Exception {
        Scenario s = new Scenario(5);
        s.density = 0.001f;
        s.tick(0);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 0, "No pre-tracking charge");
        s.run(1, 100);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 0.005, "Tiny gains accumulate without cutoff");
        check(s.samples == 20, "Tiny gain sample cadence");
        s.density = 0;
        s.tick(105);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 0, "Recovery stops at zero");
        s.density = 1;
        s.tick(110);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 0.25, "Zero exposure keeps cadence");
        setState(s.tracker, s.player.getUuid(), "exposure", 1000);
        s.tick(115);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 1000.25, "No exposure cap at maximum damage");
        s.density = 0;
        s.tick(120);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 1000, "Excess exposure retains recovery time");
    }

    private static void dimensionsAndElapsedTime() throws Exception {
        Scenario s = new Scenario(7);
        ServerWorld destination = new ServerWorld(s.server);
        s.tick(0);
        s.tick(7);
        destination.getPlayers().add(s.player);
        s.tracker.tick(destination, s.cfg, s::sample);
        check(s.samples == 1, "Same server tick in two worlds is deduplicated");
        s.world.getPlayers().clear();
        s.server.ticks = 14;
        s.tracker.tick(destination, s.cfg, s::sample);
        check(s.samples == 2, "Dimension transfer preserves cadence");
        near(state(s.tracker, s.player.getUuid(), "exposure"), 0.7, "Dimension exposure retained");
        s.server.ticks = 34;
        s.tracker.tick(destination, s.cfg, s::sample);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 1.7, "Actual elapsed sample ticks");
        s.server.ticks = 35;
        s.tracker.tick(destination, s.cfg, (x, y, z) -> 0);
        check(s.samples == 3, "Transfer does not reset sampling baseline");
        s.server.ticks = 41;
        s.tracker.tick(destination, s.cfg, (x, y, z) -> 0);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 1.35, "Zero-density destination recovers");
    }

    private static void spacedAttempts(ServerPlayerEntity player) {
        for (int i = 1; i < player.damageAttempts.size(); i++) {
            check(player.damageAttempts.get(i).tick() - player.damageAttempts.get(i - 1).tick() >= 20,
                    "No damage attempt spacing below 20 ticks");
        }
    }

    private static void damageCadence() throws Exception {
        for (int interval = 5; interval <= 100; interval++) {
            Scenario s = new Scenario(interval);
            s.primeLethal();
            s.run(1, 2000);
            double damage = s.player.damageAttempts.stream().filter(attempt -> attempt.accepted())
                    .mapToDouble(attempt -> attempt.amount()).sum();
            near(damage, 400, "Sustained max damage per second, interval=" + interval);
            check(s.player.damageAttempts.size() == 100, "Damage delivery independent of sample interval=" + interval);
            near(state(s.tracker, s.player.getUuid(), "pendingDamage"), 0, "No lost/leftover ordinary budget");
            spacedAttempts(s.player);
        }
        Scenario s = new Scenario(13);
        s.primeLethal();
        s.cfg.baseDamagePerSecond = 1;
        setState(s.tracker, s.player.getUuid(), "exposure", s.cfg.lethalExposure + 5);
        s.run(1, 200);
        near(s.player.damageAttempts.stream().mapToDouble(attempt -> attempt.amount()).sum(), 20,
                "Unsaturated rate retains base plus excess-exposure formula");
    }

    private static void rejectedDamageBudget() throws Exception {
        for (int interval : new int[] { 5, 7, 20, 25, 37, 100 }) {
            Scenario s = new Scenario(interval);
            s.primeLethal();
            s.player.acceptDamage = false;
            double cap = s.cfg.maxDamagePerSecond * Math.max(1.0, interval / 20.0);
            for (int tick = 1; tick <= 2000; tick++) {
                s.tick(tick);
                check(state(s.tracker, s.player.getUuid(), "pendingDamage") <= cap + 1e-9,
                        "Rejected budget bounded each tick, interval=" + interval);
            }
            near(state(s.tracker, s.player.getUuid(), "pendingDamage"), cap, "Backlog saturates at one bounded window");
            check(s.player.damageAttempts.stream()
                    .allMatch(attempt -> !attempt.accepted() && attempt.amount() <= cap + 1e-6),
                    "Rejected attempts cannot bank arbitrary damage");
            s.player.acceptDamage = true;
            s.run(2001, 2020);
            near(s.player.damageAttempts.get(s.player.damageAttempts.size() - 1).amount(), cap,
                    "Leaving invulnerability delivers at most bounded budget");
            near(state(s.tracker, s.player.getUuid(), "pendingDamage"), 0, "Accepted budget consumed");
            spacedAttempts(s.player);
        }
        Scenario s = new Scenario(5);
        s.primeLethal();
        s.player.acceptDamage = false;
        s.run(1, 20);
        s.cfg.exposureRecoveryPerSecond = 20;
        s.density = 0;
        setState(s.tracker, s.player.getUuid(), "exposure", s.cfg.lethalExposure);
        s.tick(25);
        near(state(s.tracker, s.player.getUuid(), "pendingDamage"), 0, "Below lethal clears rejected budget");
        s.player.acceptDamage = true;
        s.run(26, 40);
        check(s.player.damageAttempts.size() == 1, "No stale damage after recovery");
        Scenario changed = new Scenario(100);
        changed.primeLethal();
        changed.player.acceptDamage = false;
        changed.run(1, 200);
        changed.cfg.exposureIntervalTicks = 5;
        changed.cfg.baseDamagePerSecond = changed.cfg.maxDamagePerSecond = 1;
        changed.tick(201);
        near(state(changed.tracker, changed.player.getUuid(), "pendingDamage"), 1,
                "Config reload shrinks budget immediately");
    }

    private static void lifecycleResets() throws Exception {
        Scenario s = new Scenario(5);
        s.primeLethal();
        s.player.acceptDamage = false;
        s.run(1, 20);
        UUID id = s.player.getUuid();
        s.tracker.remove(id);
        check(states(s.tracker).isEmpty(), "Death hook removes state and pending damage");
        s.world.getPlayers().clear();
        s.player = new ServerPlayerEntity(id);
        s.world.getPlayers().add(s.player);
        s.tick(21);
        near(state(s.tracker, id, "exposure"), 0, "Same-UUID respawn starts fresh");
        s.tracker.remove(id);
        s.world.getPlayers().clear();
        check(states(s.tracker).isEmpty(), "Disconnect removes absent player state");
        s.world.getPlayers().add(s.player);
        s.tick(22);
        s.player.alive = false;
        s.tick(23);
        check(states(s.tracker).isEmpty(), "Dead-player tick fallback removes state");
        s.player.alive = true;
        s.tick(24);
        s.player.creative = true;
        s.tick(25);
        check(states(s.tracker).isEmpty(), "Creative cleanup");
        s.player.creative = false;
        s.tick(26);
        s.player.spectator = true;
        s.tick(27);
        check(states(s.tracker).isEmpty(), "Spectator cleanup");
        s.player.spectator = false;
        s.tick(28);
        s.world.getPlayers().add(new ServerPlayerEntity(UUID.randomUUID()));
        s.tick(29);
        check(states(s.tracker).size() == 2, "Multiple UUIDs tracked");
        s.tracker.clear();
        check(states(s.tracker).isEmpty(), "Server-session clear removes all UUIDs");
        s.tick(30);
        near(state(s.tracker, id, "pendingDamage"), 0, "New session has no stale budget");
        Scenario lethal = new Scenario(5);
        lethal.primeLethal();
        lethal.player.dieOnDamage = true;
        lethal.run(1, 20);
        check(states(lethal.tracker).isEmpty(), "Death during tracker damage clears state");
    }

    private static void effectDurations() throws Exception {
        for (int interval : new int[] { 5, 20, 37, 60, 100 }) {
            Scenario s = new Scenario(interval);
            s.primeLethal();
            s.run(1, interval);
            check(s.player.effects.size() == 2, "Both exposure effects refreshed");
            for (var effect : s.player.effects) {
                int original = effect.effect().equals(StatusEffects.NAUSEA) ? 60 : 40;
                check(effect.duration() == Math.max(original, interval + 20), "Effect covers next sample plus margin");
                check(effect.amplifier() == 0 && effect.ambient() && !effect.particles() && effect.icon(),
                        "Effect flags preserved");
            }
        }
    }

    private static void badSamplerAndTickWraparound() throws Exception {
        Scenario s = new Scenario(5);
        s.tick(0);
        for (float bad : new float[] { Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY }) {
            s.density = bad;
            s.tick(s.server.ticks + 5);
            near(state(s.tracker, s.player.getUuid(), "exposure"), 0, "Non-finite sampler cannot poison exposure");
        }
        s.density = 1;
        s.tick(20);
        near(state(s.tracker, s.player.getUuid(), "exposure"), 1, "Valid sample resumes elapsed integration");
        Scenario wrap = new Scenario(5);
        wrap.tick(Integer.MAX_VALUE - 2);
        wrap.tick(Integer.MIN_VALUE + 2);
        near(state(wrap.tracker, wrap.player.getUuid(), "exposure"), 0.25,
                "Server counter wraparound integrates five ticks");
    }
}
