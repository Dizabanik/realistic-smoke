package net.minecraft.entity.effect;
public record StatusEffectInstance(
    String effect, int duration, int amplifier, boolean ambient,
    boolean particles, boolean icon
) {}
