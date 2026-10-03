package net.minecraft.server.network;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
public final class ServerPlayerEntity {
    public record DamageAttempt(int tick, float amount, boolean accepted) {}
    private final UUID uuid;
    public boolean creative, spectator, alive = true, acceptDamage = true, dieOnDamage;
    public final List<DamageAttempt> damageAttempts = new ArrayList<>();
    public final List<StatusEffectInstance> effects = new ArrayList<>();
    public final Vec3d eye = new Vec3d(1.25, 65.62, -3.5);
    public ServerPlayerEntity(UUID uuid) { this.uuid = uuid; }
    public UUID getUuid() { return uuid; }
    public boolean isCreative() { return creative; }
    public boolean isSpectator() { return spectator; }
    public boolean isAlive() { return alive; }
    public Vec3d getEyePos() { return eye; }
    public void addStatusEffect(StatusEffectInstance effect) { effects.add(effect); }
    public boolean damage(ServerWorld world, String source, float amount) {
        if (!"magic".equals(source)) throw new AssertionError("Expected vanilla magic damage");
        damageAttempts.add(new DamageAttempt(world.getServer().getTicks(), amount, acceptDamage));
        if (acceptDamage && dieOnDamage) alive = false;
        return acceptDamage;
    }
}
