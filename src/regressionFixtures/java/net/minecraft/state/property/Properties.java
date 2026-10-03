package net.minecraft.state.property;

public final class Properties {
    private Properties() {}
    public record Property<T>(String name) {}
    public static final Property<Boolean> LIT = new Property<>("lit");
    public static final Property<Boolean> OPEN = new Property<>("open");
}
