package net.minecraft.util.math;

public record ChunkPos(int x, int z) {
    public long toLong() { return toLong(x, z); }
    public static long toLong(int x, int z) { return (x & 0xffffffffL) | ((z & 0xffffffffL) << 32); }
}
