package dev.dizabanik.realisticsmoke.mixin;

import dev.dizabanik.realisticsmoke.RealisticSmokeMod;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void realisticSmoke$invalidateGeometry(BlockPos pos, BlockState state, int flags,
                                                   CallbackInfoReturnable<BlockState> callback) {
        if (callback.getReturnValue() != null
                && ((WorldChunk) (Object) this).getWorld() instanceof ServerWorld world) {
            RealisticSmokeMod.onBlockChanged(world, pos);
        }
    }
}
