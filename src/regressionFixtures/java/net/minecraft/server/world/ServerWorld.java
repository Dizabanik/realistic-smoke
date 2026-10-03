package net.minecraft.server.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

public final class ServerWorld {

    public static final class Server {

        public int ticks;

        public int getTicks() {
            return ticks;
        }
    }

    public static final class DamageSources {

        public String magic() {
            return "magic";
        }
    }

    private final Server server;
    private final List<ServerPlayerEntity> players = new ArrayList<>();
    private final DamageSources damageSources = new DamageSources();

    public ServerWorld(Server server) {
        this.server = server;
    }

    public Server getServer() {
        return server;
    }

    public List<ServerPlayerEntity> getPlayers() {
        return players;
    }

    public DamageSources getDamageSources() {
        return damageSources;
    }

    private final Map<Long, WorldChunk> chunks = new HashMap<>();
    private final Map<Long, BlockState> blocks = new HashMap<>();
    private final ChunkManager chunkManager = new ChunkManager();
    private BlockState background = BlockState.AIR;
    private int bottomY = -64,
        topY = 127;
    public int blockReads, heightmapReads, loadedLookups, forcingReads;

    public void setBackground(BlockState background) {
        this.background = background;
    }

    public void setBuildLimits(int bottomY, int topY) {
        this.bottomY = bottomY;
        this.topY = topY;
    }

    public int getBottomY() {
        return bottomY;
    }

    public int getTopYInclusive() {
        return topY;
    }

    public boolean isInBuildLimit(BlockPos pos) {
        return pos.getY() >= bottomY && pos.getY() <= topY;
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public void loadChunk(int x, int z) {
        chunks.put(ChunkPos.toLong(x, z), new WorldChunk(this, x, z));
    }

    public void unloadChunk(int x, int z) {
        chunks.remove(ChunkPos.toLong(x, z));
    }

    public void requireLoaded(int x, int z) {
        if (
            !chunks.containsKey(ChunkPos.toLong(x, z))
        ) throw new AssertionError("Read of unloaded chunk " + x + "," + z);
    }

    public void setBlock(int x, int y, int z, BlockState state) {
        requireLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        if (y < bottomY || y > topY) throw new AssertionError(
            "Fixture placement outside build limits"
        );
        blocks.put(BlockPos.asLong(x, y, z), state);
    }

    public BlockState readBlock(BlockPos pos) {
        requireLoaded(
            Math.floorDiv(pos.getX(), 16),
            Math.floorDiv(pos.getZ(), 16)
        );
        if (!isInBuildLimit(pos)) throw new AssertionError(
            "Read outside build limits"
        );
        blockReads++;
        return blocks.getOrDefault(pos.asLong(), background);
    }

    public int surfaceHeight(int x, int z) {
        requireLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
        heightmapReads++;
        for (int y = topY; y >= bottomY; y--) {
            if (
                !blocks
                    .getOrDefault(BlockPos.asLong(x, y, z), background)
                    .isAir()
            ) return y;
        }
        return bottomY - 1;
    }

    public WorldChunk getChunk(int x, int z) {
        forcingReads++;
        throw new AssertionError("Forcing getChunk");
    }

    public BlockState getBlockState(BlockPos pos) {
        forcingReads++;
        throw new AssertionError("World block read bypassed loaded chunk");
    }

    public final class ChunkManager {

        public WorldChunk getWorldChunk(int x, int z) {
            loadedLookups++;
            return chunks.get(ChunkPos.toLong(x, z));
        }

        public WorldChunk getChunk(int x, int z) {
            forcingReads++;
            throw new AssertionError("Forcing chunk-manager read");
        }
    }
}
