package dev.dizabanik.realisticsmoke;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.world.ServerWorld;

public final class SmokeParticleRenderer {
    @FunctionalInterface
    public interface Passability { boolean test(int x, int y, int z); }
    public void render(ServerWorld world, SmokeConfig cfg, LongOpenHashSet activeSources,
                       Long2FloatOpenHashMap mass, Long2IntOpenHashMap components,
                       Long2ByteOpenHashMap ceilings, Long2ByteOpenHashMap vents,
                       Passability passability) {}
}
