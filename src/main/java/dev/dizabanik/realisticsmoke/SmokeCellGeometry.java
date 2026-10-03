package dev.dizabanik.realisticsmoke;

public final class SmokeCellGeometry {
    public static final int[] DX = {0, 0, 1, -1, 0, 0};
    public static final int[] DY = {1, -1, 0, 0, 0, 0};
    public static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private static final SmokeCellGeometry[] CACHE = new SmokeCellGeometry[256];

    static {
        for (int mask = 0; mask < CACHE.length; mask++) {
            CACHE[mask] = new SmokeCellGeometry(mask);
        }
    }

    private final int mask;
    private final int[] componentAt = new int[8];
    private final int[] components;

    private SmokeCellGeometry(int mask) {
        this.mask = mask;
        int[] found = new int[8];
        int count = 0;
        int remaining = mask;
        while (remaining != 0) {
            int component = 0;
            int frontier = Integer.lowestOneBit(remaining);
            while (frontier != 0) {
                int index = Integer.numberOfTrailingZeros(frontier);
                int bit = 1 << index;
                frontier &= ~bit;
                component |= bit;
                int neighbors = (1 << (index ^ 1)) | (1 << (index ^ 2)) | (1 << (index ^ 4));
                frontier |= neighbors & mask & ~component;
            }
            found[count++] = component;
            for (int index = 0; index < 8; index++) {
                if ((component & (1 << index)) != 0) {
                    componentAt[index] = component;
                }
            }
            remaining &= ~component;
        }
        components = java.util.Arrays.copyOf(found, count);
    }

    public static SmokeCellGeometry of(int mask) {
        return CACHE[mask & 0xff];
    }

    public int mask() {
        return mask;
    }

    public int componentAt(int blockIndex) {
        return componentAt[blockIndex];
    }

    public int[] components() {
        return components;
    }

    public static int representative(int component) {
        return Integer.numberOfTrailingZeros(component);
    }

    public static int faceBits(int component, int direction) {
        int face = 0;
        for (int pair = 0; pair < 4; pair++) {
            int low = pair & 1;
            int high = pair >>> 1;
            int index = switch (direction) {
                case 0 -> low | (high << 1) | 4;
                case 1 -> low | (high << 1);
                case 2 -> 1 | (high << 1) | (low << 2);
                case 3 -> (high << 1) | (low << 2);
                case 4 -> low | 2 | (high << 2);
                case 5 -> low | (high << 2);
                default -> throw new IllegalArgumentException("Invalid direction: " + direction);
            };
            if ((component & (1 << index)) != 0) {
                face |= 1 << pair;
            }
        }
        return face;
    }

    public static int opposite(int direction) {
        return direction ^ 1;
    }
}
