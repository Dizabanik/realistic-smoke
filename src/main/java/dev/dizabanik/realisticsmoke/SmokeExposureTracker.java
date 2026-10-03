package dev.dizabanik.realisticsmoke;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

public final class SmokeExposureTracker {

    private static final int DAMAGE_INTERVAL_TICKS = 20;
    private static final int EFFECT_MARGIN_TICKS = 20;
    private static final double DAMAGE_PER_EXCESS_EXPOSURE = 0.20;

    private final Map<UUID, PlayerState> players = new HashMap<>();

    @FunctionalInterface
    public interface DensitySampler {
        float sample(double x, double y, double z);
    }

    public void tick(
        ServerWorld world,
        SmokeConfig cfg,
        DensitySampler sampler
    ) {
        int now = world.getServer().getTicks();
        for (ServerPlayerEntity player : world.getPlayers()) {
            UUID id = player.getUuid();
            if (
                player.isCreative() || player.isSpectator() || !player.isAlive()
            ) {
                remove(id);
                continue;
            }

            PlayerState state = players.get(id);
            if (state == null) {
                players.put(id, new PlayerState(now));
                continue;
            }
            if (state.lastTick == now) {
                continue;
            }
            long damageElapsed = elapsedTicks(now, state.lastTick);
            state.lastTick = now;

            long elapsed = elapsedTicks(now, state.lastSampleTick);
            if (elapsed >= cfg.exposureIntervalTicks) {
                Vec3d eye = player.getEyePos();
                float density = sampler.sample(eye.x, eye.y, eye.z);
                if (!Float.isFinite(density)) {
                    continue;
                }
                state.lastSampleTick = now;
                double seconds = elapsed / 20.0;
                if (density > cfg.safeDensity) {
                    state.exposure = finiteSum(
                        state.exposure,
                        (density - (double) cfg.safeDensity) *
                            cfg.exposureGainPerSecond *
                            seconds
                    );
                } else {
                    state.exposure = Math.max(
                        0.0,
                        state.exposure - cfg.exposureRecoveryPerSecond * seconds
                    );
                }

                if (state.exposure >= cfg.nauseaExposure) {
                    player.addStatusEffect(
                        new StatusEffectInstance(
                            StatusEffects.NAUSEA,
                            effectDuration(60, cfg.exposureIntervalTicks),
                            0,
                            true,
                            false,
                            true
                        )
                    );
                }
                if (state.exposure >= cfg.severeExposure) {
                    player.addStatusEffect(
                        new StatusEffectInstance(
                            StatusEffects.BLINDNESS,
                            effectDuration(40, cfg.exposureIntervalTicks),
                            0,
                            true,
                            false,
                            true
                        )
                    );
                }
            }

            if (state.exposure >= cfg.lethalExposure) {
                double damagePerSecond = Math.min(
                    cfg.maxDamagePerSecond,
                    cfg.baseDamagePerSecond +
                        (state.exposure - cfg.lethalExposure) *
                            DAMAGE_PER_EXCESS_EXPOSURE
                );
                double maxPending =
                    cfg.maxDamagePerSecond *
                    Math.max(1.0, cfg.exposureIntervalTicks / 20.0);
                state.pendingDamage = Math.min(
                    maxPending,
                    state.pendingDamage +
                        damagePerSecond * (damageElapsed / 20.0)
                );
            } else {
                state.pendingDamage = 0.0;
            }

            if (
                state.pendingDamage > 0.0 &&
                elapsedTicks(now, state.lastDamageTick) >= DAMAGE_INTERVAL_TICKS
            ) {
                state.lastDamageTick = now;
                double budget = Math.min(Float.MAX_VALUE, state.pendingDamage);
                float damage = (float) budget;
                if (
                    damage > 0.0f &&
                    player.damage(
                        world,
                        world.getDamageSources().magic(),
                        damage
                    )
                ) {
                    state.pendingDamage = Math.max(
                        0.0,
                        state.pendingDamage - budget
                    );
                }
                if (!player.isAlive()) {
                    remove(id);
                }
            }
        }
    }

    public void remove(UUID id) {
        players.remove(id);
    }

    public void clear() {
        players.clear();
    }

    private static long elapsedTicks(int now, int then) {
        return Integer.toUnsignedLong(now - then);
    }

    private static int effectDuration(int original, int interval) {
        return (int) Math.max(
            original,
            Math.min(Integer.MAX_VALUE, (long) interval + EFFECT_MARGIN_TICKS)
        );
    }

    private static double finiteSum(double value, double increment) {
        return Math.min(Double.MAX_VALUE, value + increment);
    }

    private static final class PlayerState {

        private double exposure;
        private double pendingDamage;
        private int lastTick;
        private int lastSampleTick;
        private int lastDamageTick;

        private PlayerState(int now) {
            lastTick = now;
            lastSampleTick = now;
            lastDamageTick = now;
        }
    }
}
