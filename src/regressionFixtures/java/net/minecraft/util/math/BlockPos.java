package net.minecraft.util.math;

public class BlockPos {
    protected int x, y, z;
    public BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public long asLong() { return asLong(x, y, z); }
    public static long asLong(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | ((long) y & 0xfffL);
    }
    public static int unpackLongX(long value) { return (int) (value >> 38); }
    public static int unpackLongY(long value) { return (int) (value << 52 >> 52); }
    public static int unpackLongZ(long value) { return (int) (value << 26 >> 38); }
    public static final class Mutable extends BlockPos {
        public Mutable() { super(0, 0, 0); }
        public Mutable set(int x, int y, int z) { this.x = x; this.y = y; this.z = z; return this; }
    }
}
