package dev.dizabanik.realisticsmoke;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.Block;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.CampfireBlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RealisticSmokeMod implements ModInitializer {

    public static final String MOD_ID = "realistic_smoke";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final SmokeManager SMOKE = new SmokeManager();

    @Override
    public void onInitialize() {
        SmokeConfig.load();

        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            for (BlockPos pos : chunk.getBlockEntityPositions()) {
                if (isSmokeSourceBlock(chunk.getBlockState(pos).getBlock())) {
                    SMOKE.onSourceLoaded(world, pos);
                }
            }
        });

        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            SMOKE.onChunkUnloaded(world, chunk.getPos());
        });

        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(
            (blockEntity, world) -> {
                if (isSmokeSource(blockEntity)) {
                    SMOKE.onSourceLoaded(world, blockEntity.getPos());
                }
            }
        );

        ServerBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(
            (blockEntity, world) -> {
                if (isSmokeSource(blockEntity)) {
                    SMOKE.onSourceUnloaded(world, blockEntity.getPos());
                }
            }
        );

        ServerTickEvents.END_WORLD_TICK.register(SMOKE::tickWorld);
        ServerLivingEntityEvents.AFTER_DEATH.register(
            (entity, damageSource) -> {
                if (entity instanceof ServerPlayerEntity player) {
                    SMOKE.removePlayer(player.getUuid());
                }
            }
        );
        ServerPlayerEvents.AFTER_RESPAWN.register(
            (oldPlayer, newPlayer, alive) -> {
                SMOKE.removePlayer(oldPlayer.getUuid());
            }
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SMOKE.removePlayer(handler.player.getUuid());
        });
        ServerWorldEvents.UNLOAD.register((server, world) ->
            SMOKE.onWorldUnloaded(world)
        );
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> SMOKE.clear());
        LOGGER.info(
            "Realistic Smoke initialized: sparse connected air pockets, no smoke entities."
        );
    }

    public static void onBlockChanged(ServerWorld world, BlockPos pos) {
        SMOKE.invalidateGeometry(world, pos);
    }

    public static boolean isSmokeSourceBlock(Block block) {
        return (
            block instanceof AbstractFurnaceBlock ||
            block instanceof CampfireBlock
        );
    }

    private static boolean isSmokeSource(BlockEntity blockEntity) {
        return (
            blockEntity instanceof AbstractFurnaceBlockEntity ||
            blockEntity instanceof CampfireBlockEntity
        );
    }
}
