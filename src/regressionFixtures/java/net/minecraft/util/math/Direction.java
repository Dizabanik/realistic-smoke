package net.minecraft.util.math;

public enum Direction {
    NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);
    private final int x, z;
    Direction(int x, int z) { this.x = x; this.z = z; }
    public int getOffsetX() { return x; }
    public int getOffsetZ() { return z; }
}
