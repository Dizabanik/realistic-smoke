package dev.dizabanik.realisticsmoke;

public final class SmokeCellGeometryTest {
    private static final int[] DX = { 0, 0, 1, -1, 0, 0 };
    private static final int[] DY = { 1, -1, 0, 0, 0, 0 };
    private static final int[] DZ = { 0, 0, 0, 0, 1, -1 };

    public static void main(String[] args) {
        checkDirections();
        checkAllGeometries();
        checkAllFaces();
        checkNeighborAlignment();
        checkDisconnectedPockets();
        System.out.println(
                "SmokeCellGeometryTest passed: all 256 masks, connectivity, faces, neighbor alignment, and disconnected pockets.");
    }

    private static void checkDirections() {
        for (int direction = 0; direction < 6; direction++) {
            check(SmokeCellGeometry.DX[direction] == DX[direction], "DX", direction);
            check(SmokeCellGeometry.DY[direction] == DY[direction], "DY", direction);
            check(SmokeCellGeometry.DZ[direction] == DZ[direction], "DZ", direction);
            int opposite = SmokeCellGeometry.opposite(direction);
            check(opposite == (direction ^ 1), "opposite", direction);
            check(DX[opposite] == -DX[direction] && DY[opposite] == -DY[direction]
                    && DZ[opposite] == -DZ[direction], "opposite offsets", direction);
        }
        check(SmokeCellGeometry.representative(0) == 32, "empty representative", 0);
        check(SmokeCellGeometry.of(0x1ff) == SmokeCellGeometry.of(0xff), "low eight bits", 0x1ff);
        check(SmokeCellGeometry.of(-1) == SmokeCellGeometry.of(0xff), "negative mask", -1);
    }

    private static void checkAllGeometries() {
        for (int mask = 0; mask < 256; mask++) {
            SmokeCellGeometry geometry = SmokeCellGeometry.of(mask);
            check(geometry == SmokeCellGeometry.of(mask), "cached geometry", mask);
            check(geometry.mask() == mask, "mask", mask);
            check(geometry.components() == geometry.components(), "shared component array", mask);
            int covered = 0;
            int previousRepresentative = -1;
            for (int component : geometry.components()) {
                check(component != 0 && (component & ~mask) == 0, "component subset", mask);
                check((covered & component) == 0, "disjoint components", mask);
                int representative = SmokeCellGeometry.representative(component);
                check(representative > previousRepresentative, "representative order", mask);
                check(component == reachable(mask, representative), "connected and maximal", mask);
                previousRepresentative = representative;
                covered |= component;
            }
            check(covered == mask, "complete coverage", mask);
            for (int index = 0; index < 8; index++) {
                int expected = (mask & (1 << index)) == 0 ? 0 : reachable(mask, index);
                check(geometry.componentAt(index) == expected, "componentAt " + index, mask);
            }
        }
    }

    private static int reachable(int mask, int start) {
        int reached = 1 << start;
        int previous;
        do {
            previous = reached;
            for (int from = 0; from < 8; from++) {
                if ((reached & (1 << from)) == 0) {
                    continue;
                }
                for (int to = 0; to < 8; to++) {
                    int distance = Math.abs(x(from) - x(to)) + Math.abs(y(from) - y(to))
                            + Math.abs(z(from) - z(to));
                    if (distance == 1 && (mask & (1 << to)) != 0) {
                        reached |= 1 << to;
                    }
                }
            }
        } while (reached != previous);
        return reached;
    }

    private static void checkAllFaces() {
        for (int mask = 0; mask < 256; mask++) {
            for (int direction = 0; direction < 6; direction++) {
                int expected = 0;
                for (int index = 0; index < 8; index++) {
                    if ((mask & (1 << index)) != 0 && onFace(index, direction)) {
                        expected |= 1 << faceIndex(index, direction);
                    }
                }
                check(SmokeCellGeometry.faceBits(mask, direction) == expected, "face " + direction, mask);
            }
        }
    }

    private static void checkNeighborAlignment() {
        for (int direction = 0; direction < 6; direction++) {
            int[] across = new int[8];
            for (int index = 0; index < 8; index++) {
                int nx = x(index) - DX[direction];
                int ny = y(index) - DY[direction];
                int nz = z(index) - DZ[direction];
                across[index] = onFace(index, direction) ? nx | (nz << 1) | (ny << 2) : -1;
            }
            for (int mask = 0; mask < 256; mask++) {
                for (int neighbor = 0; neighbor < 256; neighbor++) {
                    int expected = 0;
                    for (int index = 0; index < 8; index++) {
                        if (across[index] >= 0 && (mask & (1 << index)) != 0
                                && (neighbor & (1 << across[index])) != 0) {
                            expected |= 1 << faceIndex(index, direction);
                        }
                    }
                    int actual = SmokeCellGeometry.faceBits(mask, direction)
                            & SmokeCellGeometry.faceBits(neighbor, SmokeCellGeometry.opposite(direction));
                    check(actual == expected, "neighbor alignment " + direction + "/" + neighbor, mask);
                }
            }
        }
    }

    private static void checkDisconnectedPockets() {
        int diagonal = (1 << 0) | (1 << 7);
        SmokeCellGeometry geometry = SmokeCellGeometry.of(diagonal);
        check(geometry.components().length == 2, "opposite corner pockets", diagonal);
        check(geometry.componentAt(0) == 1 && geometry.componentAt(7) == 128, "isolated corners", diagonal);
        int checkerboard = (1 << 0) | (1 << 3) | (1 << 5) | (1 << 6);
        geometry = SmokeCellGeometry.of(checkerboard);
        check(geometry.components().length == 4, "four isolated pockets", checkerboard);
        int separateEdges = (1 << 0) | (1 << 1) | (1 << 6) | (1 << 7);
        geometry = SmokeCellGeometry.of(separateEdges);
        check(geometry.components().length == 2 && geometry.componentAt(0) == 3
                && geometry.componentAt(6) == 192, "two disconnected edges", separateEdges);
        check(SmokeCellGeometry.of(0).components().length == 0, "empty cell", 0);
        check(SmokeCellGeometry.of(255).components().length == 1, "full cell", 255);
    }

    private static boolean onFace(int index, int direction) {
        return DX[direction] != 0 ? x(index) == (DX[direction] > 0 ? 1 : 0)
                : DY[direction] != 0 ? y(index) == (DY[direction] > 0 ? 1 : 0)
                        : z(index) == (DZ[direction] > 0 ? 1 : 0);
    }

    private static int faceIndex(int index, int direction) {
        return DY[direction] != 0 ? x(index) | (z(index) << 1)
                : DX[direction] != 0 ? y(index) | (z(index) << 1)
                        : x(index) | (y(index) << 1);
    }

    private static int x(int index) {
        return index & 1;
    }

    private static int y(int index) {
        return (index >>> 2) & 1;
    }

    private static int z(int index) {
        return (index >>> 1) & 1;
    }

    private static void check(boolean condition, String description, int mask) {
        if (!condition) {
            throw new AssertionError(description + " (mask=" + mask + ")");
        }
    }
}
