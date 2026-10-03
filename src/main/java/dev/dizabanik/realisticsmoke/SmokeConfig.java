package dev.dizabanik.realisticsmoke;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

public final class SmokeConfig {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();
    private static final Path PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("realistic-smoke.json");

    public static SmokeConfig INSTANCE = new SmokeConfig();

    public int simulationIntervalTicks = 10;
    public int exposureIntervalTicks = 20;

    public int maxActiveCellsPerWorld = 12_000;
    public int maxGeometryCacheCellsPerWorld = 24_000;
    public float minDensity = 0.035f;
    public float maxDensity = 12.0f;

    public float furnaceEmission = 0.55f;
    public float smokerEmission = 0.85f;
    public float campfireEmission = 1.00f;

    public float baseDissipation = 0.018f;
    public float outdoorRetention = 0.10f;
    public float risingFraction = 0.56f;
    public float lateralFractionWhileRising = 0.10f;
    public float lateralFractionWhenBlocked = 0.48f;
    public float pressureDownFraction = 0.04f;
    public float pressureDownThreshold = 4.0f;

    public int passabilityCacheSteps = 4;
    public int passabilityCacheClearSteps = 128;

    public boolean particles = true;
    public int particleIntervalTicks = 10;
    public int particleBudgetPerWorld = 64;
    public int sourceParticleBudget = 16;
    public int ventParticleBudget = 12;
    public float particleMinDensity = 0.20f;
    public double particleViewDistance = 48.0;

    public float safeDensity = 0.75f;
    public float exposureGainPerSecond = 0.48f;
    public float exposureRecoveryPerSecond = 0.90f;
    public float nauseaExposure = 4.0f;
    public float severeExposure = 8.0f;
    public float lethalExposure = 14.0f;
    public float baseDamagePerSecond = 1.0f;
    public float maxDamagePerSecond = 4.0f;

    private SmokeConfig() {}

    public static void load() {
        SmokeConfig loaded = null;
        boolean saveConfig = true;
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                loaded = GSON.fromJson(reader, SmokeConfig.class);
                if (loaded == null) {
                    throw new IOException("Config must contain a JSON object");
                }
            } catch (Exception e) {
                saveConfig = false;
                RealisticSmokeMod.LOGGER.warn(
                    "Could not read {}; using defaults and preserving existing file",
                    PATH,
                    e
                );
            }
        }

        INSTANCE = loaded == null ? new SmokeConfig() : loaded;
        INSTANCE.sanitize();
        if (saveConfig) {
            save();
        }
    }

    private static void save() {
        Path temporaryPath = null;
        try {
            String json = GSON.toJson(INSTANCE);
            Files.createDirectories(PATH.getParent());
            temporaryPath = Files.createTempFile(
                PATH.getParent(),
                "realistic-smoke-",
                ".tmp"
            );
            try (Writer writer = Files.newBufferedWriter(temporaryPath)) {
                writer.write(json);
            }
            try {
                Files.move(
                    temporaryPath,
                    PATH,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(
                    temporaryPath,
                    PATH,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (Exception e) {
            RealisticSmokeMod.LOGGER.warn("Could not write {}", PATH, e);
        } finally {
            if (temporaryPath != null) {
                try {
                    Files.deleteIfExists(temporaryPath);
                } catch (IOException e) {
                    RealisticSmokeMod.LOGGER.warn(
                        "Could not remove temporary config {}",
                        temporaryPath,
                        e
                    );
                }
            }
        }
    }

    private void sanitize() {
        SmokeConfig defaults = new SmokeConfig();
        minDensity = finiteOrDefault(minDensity, defaults.minDensity);
        maxDensity = finiteOrDefault(maxDensity, defaults.maxDensity);
        furnaceEmission = finiteOrDefault(
            furnaceEmission,
            defaults.furnaceEmission
        );
        smokerEmission = finiteOrDefault(
            smokerEmission,
            defaults.smokerEmission
        );
        campfireEmission = finiteOrDefault(
            campfireEmission,
            defaults.campfireEmission
        );
        baseDissipation = finiteOrDefault(
            baseDissipation,
            defaults.baseDissipation
        );
        outdoorRetention = finiteOrDefault(
            outdoorRetention,
            defaults.outdoorRetention
        );
        risingFraction = finiteOrDefault(
            risingFraction,
            defaults.risingFraction
        );
        lateralFractionWhileRising = finiteOrDefault(
            lateralFractionWhileRising,
            defaults.lateralFractionWhileRising
        );
        lateralFractionWhenBlocked = finiteOrDefault(
            lateralFractionWhenBlocked,
            defaults.lateralFractionWhenBlocked
        );
        pressureDownFraction = finiteOrDefault(
            pressureDownFraction,
            defaults.pressureDownFraction
        );
        pressureDownThreshold = finiteOrDefault(
            pressureDownThreshold,
            defaults.pressureDownThreshold
        );
        particleMinDensity = finiteOrDefault(
            particleMinDensity,
            defaults.particleMinDensity
        );
        particleViewDistance = finiteOrDefault(
            particleViewDistance,
            defaults.particleViewDistance
        );
        safeDensity = finiteOrDefault(safeDensity, defaults.safeDensity);
        exposureGainPerSecond = finiteOrDefault(
            exposureGainPerSecond,
            defaults.exposureGainPerSecond
        );
        exposureRecoveryPerSecond = finiteOrDefault(
            exposureRecoveryPerSecond,
            defaults.exposureRecoveryPerSecond
        );
        nauseaExposure = finiteOrDefault(
            nauseaExposure,
            defaults.nauseaExposure
        );
        severeExposure = finiteOrDefault(
            severeExposure,
            defaults.severeExposure
        );
        lethalExposure = finiteOrDefault(
            lethalExposure,
            defaults.lethalExposure
        );
        baseDamagePerSecond = finiteOrDefault(
            baseDamagePerSecond,
            defaults.baseDamagePerSecond
        );
        maxDamagePerSecond = finiteOrDefault(
            maxDamagePerSecond,
            defaults.maxDamagePerSecond
        );

        simulationIntervalTicks = clamp(simulationIntervalTicks, 2, 100);
        exposureIntervalTicks = clamp(exposureIntervalTicks, 5, 100);
        maxActiveCellsPerWorld = clamp(maxActiveCellsPerWorld, 256, 200_000);
        maxGeometryCacheCellsPerWorld = clamp(
            maxGeometryCacheCellsPerWorld,
            256,
            400_000
        );
        minDensity = clamp(minDensity, 0.001f, 1.0f);
        maxDensity = clamp(maxDensity, 1.0f, 100.0f);

        furnaceEmission = clamp(furnaceEmission, 0.0f, 10.0f);
        smokerEmission = clamp(smokerEmission, 0.0f, 10.0f);
        campfireEmission = clamp(campfireEmission, 0.0f, 10.0f);

        baseDissipation = clamp(baseDissipation, 0.0f, 0.25f);
        outdoorRetention = clamp(outdoorRetention, 0.0f, 1.0f);
        risingFraction = clamp(risingFraction, 0.0f, 0.90f);
        lateralFractionWhileRising = clamp(
            lateralFractionWhileRising,
            0.0f,
            0.50f
        );
        lateralFractionWhenBlocked = clamp(
            lateralFractionWhenBlocked,
            0.0f,
            0.90f
        );
        pressureDownFraction = clamp(pressureDownFraction, 0.0f, 0.20f);
        pressureDownThreshold = clamp(pressureDownThreshold, 0.0f, maxDensity);

        passabilityCacheSteps = clamp(passabilityCacheSteps, 0, 40);
        passabilityCacheClearSteps = clamp(
            passabilityCacheClearSteps,
            16,
            2048
        );

        particleIntervalTicks = clamp(particleIntervalTicks, 2, 200);
        particleBudgetPerWorld = clamp(particleBudgetPerWorld, 0, 2000);
        sourceParticleBudget = clamp(
            sourceParticleBudget,
            0,
            particleBudgetPerWorld
        );
        ventParticleBudget = clamp(
            ventParticleBudget,
            0,
            particleBudgetPerWorld
        );
        particleMinDensity = clamp(particleMinDensity, minDensity, maxDensity);
        particleViewDistance = clamp(particleViewDistance, 8.0, 128.0);

        safeDensity = clamp(safeDensity, 0.0f, maxDensity);
        exposureGainPerSecond = clamp(exposureGainPerSecond, 0.0f, 10.0f);
        exposureRecoveryPerSecond = clamp(
            exposureRecoveryPerSecond,
            0.0f,
            20.0f
        );
        nauseaExposure = clamp(nauseaExposure, 0.1f, 100.0f);
        severeExposure = Math.max(
            nauseaExposure,
            clamp(severeExposure, 0.1f, 100.0f)
        );
        lethalExposure = Math.max(
            severeExposure,
            clamp(lethalExposure, 0.1f, 200.0f)
        );
        baseDamagePerSecond = clamp(baseDamagePerSecond, 0.0f, 20.0f);
        maxDamagePerSecond = Math.max(
            baseDamagePerSecond,
            clamp(maxDamagePerSecond, 0.0f, 40.0f)
        );
    }

    private static float finiteOrDefault(float value, float defaultValue) {
        return Float.isFinite(value) ? value : defaultValue;
    }

    private static double finiteOrDefault(double value, double defaultValue) {
        return Double.isFinite(value) ? value : defaultValue;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
