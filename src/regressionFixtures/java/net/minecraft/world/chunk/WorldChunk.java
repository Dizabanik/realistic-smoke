package net.minecraft.world.chunk;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

public final class WorldChunk {
    private final ServerWorld world;
    private final int x, z;
    public WorldChunk(ServerWorld world, int x, int z) { this.world = world; this.x = x; this.z = z; }
    public BlockState getBlockState(BlockPos pos) {
        world.requireLoaded(x, z);
        if (Math.floorDiv(pos.getX(), 16) != x || Math.floorDiv(pos.getZ(), 16) != z) {
            throw new AssertionError("Block read through the wrong chunk");
        }
        return world.readBlock(pos);
    }
    public int sampleHeightmap(Heightmap.Type type, int localX, int localZ) {
        world.requireLoaded(x, z);
        if (type != Heightmap.Type.WORLD_SURFACE || localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
            throw new AssertionError("Unexpected fake heightmap request");
        }
        return world.surfaceHeight(x * 16 + localX, z * 16 + localZ);
    }
}
