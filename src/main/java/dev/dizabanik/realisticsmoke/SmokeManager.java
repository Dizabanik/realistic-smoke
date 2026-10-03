package dev.dizabanik.realisticsmoke;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.SmokerBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

public final class SmokeManager {

    private final Map<ServerWorld, WorldState> states = new HashMap<>();
    private final SmokeExposureTracker exposure = new SmokeExposureTracker();
    private static final SmokeExposureTracker.DensitySampler CLEAN_AIR = (
        x,
        y,
        z
    ) -> 0.0f;

    public void onSourceLoaded(ServerWorld world, BlockPos pos) {
        WorldState state = states.computeIfAbsent(world, ignored ->
            new WorldState()
        );
        long packed = pos.asLong();
        if (state.sources.add(packed)) {
            state.sourcesByChunk
                .computeIfAbsent(chunkKey(packed), ignored ->
                    new LongOpenHashSet()
                )
                .add(packed);
        }
        invalidateGeometry(world, pos);
    }

    public void onSourceUnloaded(ServerWorld world, BlockPos pos) {
        WorldState state = states.get(world);
        if (state != null) {
            removeSource(state, pos.asLong());
            invalidateGeometry(world, pos);
        }
    }

    private static void removeSource(WorldState state, long packed) {
        state.sources.remove(packed);
        state.activeSources.remove(packed);
        long chunk = chunkKey(packed);
        LongOpenHashSet sources = state.sourcesByChunk.get(chunk);
        if (sources != null) {
            sources.remove(packed);
            if (sources.isEmpty()) {
                state.sourcesByChunk.remove(chunk);
            }
        }
    }

    public void onChunkUnloaded(ServerWorld world, ChunkPos chunkPos) {
        WorldState state = states.get(world);
        if (state == null) {
            return;
        }
        long chunk = chunkPos.toLong();
        LongOpenHashSet removed = state.sourcesByChunk.remove(chunk);
        if (removed != null) {
            var sources = removed.iterator();
            while (sources.hasNext()) {
                long packed = sources.nextLong();
                state.sources.remove(packed);
                state.activeSources.remove(packed);
            }
        }
        state.unloadedChunks.add(chunk);
    }

    private static void purgeUnloadedChunks(WorldState state) {
        if (state.unloadedChunks.isEmpty()) {
            return;
        }
        var keys = state.density.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            if (state.unloadedChunks.contains(chunkKey(key))) {
                keys.remove();
                state.components.remove(key);
                state.ceilings.remove(key);
                state.vents.remove(key);
            }
        }
        var geometry = state.geometry.keySet().iterator();
        while (geometry.hasNext()) {
            if (state.unloadedChunks.contains(chunkKey(geometry.nextLong()))) {
                geometry.remove();
            }
        }
        state.columnTops.clear();
        state.unloadedChunks.clear();
    }

    public void removePlayer(UUID id) {
        exposure.remove(id);
    }

    public void onWorldUnloaded(ServerWorld world) {
        states.remove(world);
    }

    public void clear() {
        states.clear();
        exposure.clear();
    }

    public void invalidateGeometry(ServerWorld world, BlockPos pos) {
        WorldState state = states.get(world);
        if (state == null) {
            return;
        }
        invalidateCell(state, cellOrigin(pos.getX(), pos.getY(), pos.getZ()));
        for (int direction = 0; direction < 6; direction++) {
            invalidateCell(
                state,
                cellOrigin(
                    pos.getX() + SmokeCellGeometry.DX[direction],
                    pos.getY() + SmokeCellGeometry.DY[direction],
                    pos.getZ() + SmokeCellGeometry.DZ[direction]
                )
            );
        }
        state.columnTops.remove(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
    }

    private static void invalidateCell(WorldState state, long origin) {
        state.geometry.remove(origin);
        int x = BlockPos.unpackLongX(origin);
        int y = BlockPos.unpackLongY(origin);
        int z = BlockPos.unpackLongZ(origin);
        for (int index = 0; index < 8; index++) {
            long key = blockKey(x, y, z, index);
            state.ceilings.remove(key);
            state.vents.remove(key);
            if (index < 4) {
                state.columnTops.remove(
                    BlockPos.asLong(x + (index & 1), 0, z + (index >>> 1))
                );
            }
        }
    }

    public void tickWorld(ServerWorld world) {
        SmokeConfig cfg = SmokeConfig.INSTANCE;
        WorldState state = states.get(world);
        if (state != null) {
            purgeUnloadedChunks(state);
            state.worldTick++;
            if (state.worldTick % cfg.simulationIntervalTicks == 0) {
                simulate(world, state, cfg);
            }
            if (
                cfg.particles &&
                cfg.particleBudgetPerWorld > 0 &&
                state.worldTick % cfg.particleIntervalTicks == 0
            ) {
                state.renderer.render(
                    world,
                    cfg,
                    state.activeSources,
                    state.density,
                    state.components,
                    state.ceilings,
                    state.vents,
                    (x, y, z) -> isPassable(world, state, cfg, x, y, z)
                );
            }
        }
        exposure.tick(
            world,
            cfg,
            state == null
                ? CLEAN_AIR
                : (x, y, z) -> densityAt(world, state, cfg, x, y, z)
        );
    }

    private void simulate(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg
    ) {
        state.simulationStep++;
        state.columnTops.clear();
        state.ceilings.clear();
        state.vents.clear();
        if (state.simulationStep % cfg.passabilityCacheClearSteps == 0) {
            state.geometry.clear();
            state.geometry.trim();
        }
        state.working.clear();
        reconcileGeometry(world, state, cfg);
        emitFromSources(world, state, cfg);
        state.next.clear();
        var donors = state.working.keySet().iterator();
        while (donors.hasNext()) {
            state.next.put(donors.nextLong(), 0.0f);
        }

        var iterator = state.working.long2FloatEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            long origin = cellOrigin(key);
            int x = BlockPos.unpackLongX(origin);
            int y = BlockPos.unpackLongY(origin);
            int z = BlockPos.unpackLongZ(origin);
            SmokeCellGeometry geometry = geometry(world, state, cfg, origin);
            int component = geometry.componentAt(blockIndex(key));
            if (component == 0) {
                continue;
            }
            float mass = entry.getFloatValue();
            float density = SmokeTransport.concentration(mass, component);
            float available = mass * (1.0f - cfg.baseDissipation);
            int exhaust = exhaustProfile(world, state, origin, component);
            available = SmokeTransport.outdoorMass(
                available,
                cfg.outdoorRetention,
                Integer.bitCount(exhaust)
            );

            int count = 0;
            float requested = 0.0f;
            boolean canRise = hasOpening(
                world,
                state,
                cfg,
                origin,
                component,
                0
            );
            for (int direction = 0; direction < 6; direction++) {
                int face = SmokeCellGeometry.faceBits(component, direction);
                if (
                    face == 0 ||
                    (direction == 1 && density < cfg.pressureDownThreshold)
                ) {
                    continue;
                }
                int nx = x + SmokeCellGeometry.DX[direction] * 2;
                int ny = y + SmokeCellGeometry.DY[direction] * 2;
                int nz = z + SmokeCellGeometry.DZ[direction] * 2;
                SmokeCellGeometry neighbor = geometry(
                    world,
                    state,
                    cfg,
                    BlockPos.asLong(nx, ny, nz)
                );
                for (int destination : neighbor.components()) {
                    int openings = Integer.bitCount(
                        face &
                            SmokeCellGeometry.faceBits(
                                destination,
                                SmokeCellGeometry.opposite(direction)
                            )
                    );
                    if (openings == 0) {
                        continue;
                    }
                    long target = blockKey(
                        nx,
                        ny,
                        nz,
                        SmokeCellGeometry.representative(destination)
                    );
                    float amount;
                    if (direction == 0) {
                        amount = SmokeTransport.openingTransfer(
                            available,
                            cfg.risingFraction,
                            openings
                        );
                    } else {
                        float targetDensity = SmokeTransport.concentration(
                            state.working.get(target),
                            destination
                        );
                        float fraction =
                            direction == 1
                                ? cfg.pressureDownFraction
                                : (canRise
                                      ? cfg.lateralFractionWhileRising
                                      : cfg.lateralFractionWhenBlocked) / 4.0f;
                        amount = SmokeTransport.gradientTransfer(
                            available,
                            density,
                            targetDensity,
                            fraction,
                            openings
                        );
                    }
                    if (amount > 0.0f) {
                        state.flowKeys[count] = target;
                        state.flowAmounts[count++] = amount;
                        requested += amount;
                    }
                }
            }
            float scale = SmokeTransport.transferScale(available, requested);
            float remaining = available;
            for (int index = 0; index < count; index++) {
                float amount = Math.min(
                    remaining,
                    state.flowAmounts[index] * scale
                );
                if (
                    accumulate(state.next, state.flowKeys[index], amount, cfg)
                ) {
                    remaining -= amount;
                }
            }
            accumulate(state.next, key, Math.max(0.0f, remaining), cfg);
        }
        finishField(world, state, cfg);
        Long2FloatOpenHashMap old = state.density;
        state.density = state.next;
        state.next = old;
        state.next.clear();
        state.working.clear();
        state.selected.clear();
        buildProfiles(world, state, cfg);
        if (state.simulationStep % cfg.passabilityCacheClearSteps == 0) {
            state.density.trim();
            state.next.trim();
            state.working.trim();
            state.components.trim();
            state.columnTops.trim();
            state.selected.trim();
        }
    }

    private void reconcileGeometry(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg
    ) {
        var iterator = state.density.long2FloatEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            long origin = cellOrigin(key);
            SmokeCellGeometry current = geometry(world, state, cfg, origin);
            int previous = state.components.get(key);
            if (previous == 0) {
                previous = current.componentAt(blockIndex(key));
            }
            int surviving = previous & current.mask();
            int volume = Integer.bitCount(surviving);
            if (volume == 0) {
                continue;
            }
            int x = BlockPos.unpackLongX(origin);
            int y = BlockPos.unpackLongY(origin);
            int z = BlockPos.unpackLongZ(origin);
            for (int component : current.components()) {
                int overlap = Integer.bitCount(component & surviving);
                if (overlap != 0) {
                    accumulate(
                        state.working,
                        blockKey(
                            x,
                            y,
                            z,
                            SmokeCellGeometry.representative(component)
                        ),
                        (entry.getFloatValue() * overlap) / volume,
                        cfg
                    );
                }
            }
        }
    }

    private void emitFromSources(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg
    ) {
        state.activeSources.clear();
        state.admission.reset(cfg.maxActiveCellsPerWorld, state.simulationStep);
        var iterator = state.sources.iterator();
        while (iterator.hasNext()) {
            long packed = iterator.nextLong();
            int x = BlockPos.unpackLongX(packed);
            int y = BlockPos.unpackLongY(packed);
            int z = BlockPos.unpackLongZ(packed);
            WorldChunk chunk = loadedChunk(world, x, y, z);
            if (chunk == null) {
                continue;
            }
            state.mutablePos.set(x, y, z);
            BlockState block = chunk.getBlockState(state.mutablePos);
            if (!RealisticSmokeMod.isSmokeSourceBlock(block.getBlock())) {
                iterator.remove();
                state.activeSources.remove(packed);
                LongOpenHashSet indexed = state.sourcesByChunk.get(
                    chunkKey(packed)
                );
                if (indexed != null) {
                    indexed.remove(packed);
                    if (indexed.isEmpty()) {
                        state.sourcesByChunk.remove(chunkKey(packed));
                    }
                }
                continue;
            }
            float emission = sourceEmission(block, cfg);
            if (emission <= 0.0f) {
                continue;
            }
            state.activeSources.add(packed);
            long output = sourceOutput(world, state, cfg, block, x, y, z);
            if (output != Long.MIN_VALUE) {
                if (state.working.containsKey(output)) {
                    accumulate(state.working, output, emission, cfg);
                } else {
                    int component = geometry(
                        world,
                        state,
                        cfg,
                        cellOrigin(output)
                    ).componentAt(blockIndex(output));
                    state.admission.offer(
                        output,
                        SmokeTransport.concentration(emission, component)
                    );
                }
            }
        }
        if (state.admission.size() > 0) {
            var active = state.activeSources.iterator();
            while (active.hasNext()) {
                long packed = active.nextLong();
                int x = BlockPos.unpackLongX(packed);
                int y = BlockPos.unpackLongY(packed);
                int z = BlockPos.unpackLongZ(packed);
                WorldChunk chunk = loadedChunk(world, x, y, z);
                if (chunk == null) {
                    continue;
                }
                BlockState block = chunk.getBlockState(
                    state.mutablePos.set(x, y, z)
                );
                long output = sourceOutput(world, state, cfg, block, x, y, z);
                if (state.admission.contains(output)) {
                    accumulate(
                        state.working,
                        output,
                        sourceEmission(block, cfg),
                        cfg
                    );
                }
            }
        }
    }

    private long sourceOutput(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg,
        BlockState block,
        int x,
        int y,
        int z
    ) {
        long output = outputPocket(world, state, cfg, x, y + 1, z);
        if (
            output == Long.MIN_VALUE &&
            block.contains(AbstractFurnaceBlock.FACING)
        ) {
            var facing = block.get(AbstractFurnaceBlock.FACING);
            output = outputPocket(
                world,
                state,
                cfg,
                x + facing.getOffsetX(),
                y,
                z + facing.getOffsetZ()
            );
        }
        if (output == Long.MIN_VALUE) {
            output = outputPocket(world, state, cfg, x, y, z);
        }
        return output;
    }

    private long outputPocket(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg,
        int x,
        int y,
        int z
    ) {
        long origin = cellOrigin(x, y, z);
        int component = geometry(world, state, cfg, origin).componentAt(
            blockIndex(x, y, z)
        );
        return component == 0
            ? Long.MIN_VALUE
            : blockKey(
                  BlockPos.unpackLongX(origin),
                  BlockPos.unpackLongY(origin),
                  BlockPos.unpackLongZ(origin),
                  SmokeCellGeometry.representative(component)
              );
    }

    private void finishField(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg
    ) {
        state.components.clear();
        var iterator = state.next.long2FloatEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            int component = geometry(
                world,
                state,
                cfg,
                cellOrigin(key)
            ).componentAt(blockIndex(key));
            float concentration = SmokeTransport.concentration(
                entry.getFloatValue(),
                component
            );
            if (
                component == 0 ||
                concentration < cfg.minDensity ||
                !Float.isFinite(concentration)
            ) {
                iterator.remove();
            } else {
                entry.setValue(
                    Math.min(
                        entry.getFloatValue(),
                        (cfg.maxDensity * Integer.bitCount(component)) / 8.0f
                    )
                );
                state.components.put(key, component);
            }
        }
        if (state.next.size() > cfg.maxActiveCellsPerWorld) {
            selectStrongest(state, cfg.maxActiveCellsPerWorld);
        }
    }

    private static void selectStrongest(WorldState state, int capacity) {
        if (state.selectedKeys.length < capacity) {
            state.selectedKeys = new long[capacity];
            state.selectedScores = new float[capacity];
        }
        int count = 0;
        var iterator = state.next.long2FloatEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            float score = SmokeTransport.concentration(
                entry.getFloatValue(),
                state.components.get(key)
            );
            count = offer(state, count, capacity, key, score);
        }
        state.selected.clear();
        for (int index = 0; index < count; index++) {
            state.selected.add(state.selectedKeys[index]);
        }
        var keys = state.next.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            if (!state.selected.contains(key)) {
                keys.remove();
                state.components.remove(key);
            }
        }
    }

    private static boolean less(
        float leftScore,
        long leftKey,
        float rightScore,
        long rightKey
    ) {
        return (
            leftScore < rightScore ||
            (leftScore == rightScore &&
                Long.compareUnsigned(leftKey, rightKey) < 0)
        );
    }

    private static int offer(
        WorldState state,
        int count,
        int capacity,
        long key,
        float score
    ) {
        if (count < capacity) {
            int index = count;
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (
                    !less(
                        score,
                        key,
                        state.selectedScores[parent],
                        state.selectedKeys[parent]
                    )
                ) {
                    break;
                }
                state.selectedKeys[index] = state.selectedKeys[parent];
                state.selectedScores[index] = state.selectedScores[parent];
                index = parent;
            }
            state.selectedKeys[index] = key;
            state.selectedScores[index] = score;
            return count + 1;
        }
        if (!less(state.selectedScores[0], state.selectedKeys[0], score, key)) {
            return count;
        }
        int index = 0;
        while (true) {
            int left = (index << 1) + 1;
            if (left >= capacity) {
                break;
            }
            int right = left + 1;
            int child =
                right < capacity &&
                less(
                    state.selectedScores[right],
                    state.selectedKeys[right],
                    state.selectedScores[left],
                    state.selectedKeys[left]
                )
                    ? right
                    : left;
            if (
                !less(
                    state.selectedScores[child],
                    state.selectedKeys[child],
                    score,
                    key
                )
            ) {
                break;
            }
            state.selectedKeys[index] = state.selectedKeys[child];
            state.selectedScores[index] = state.selectedScores[child];
            index = child;
        }
        state.selectedKeys[index] = key;
        state.selectedScores[index] = score;
        return count;
    }

    private void buildProfiles(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg
    ) {
        if (!cfg.particles) {
            return;
        }
        var iterator = state.density.long2FloatEntrySet().fastIterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            long key = entry.getLongKey();
            int component = state.components.get(key);
            if (
                SmokeTransport.concentration(entry.getFloatValue(), component) <
                cfg.particleMinDensity
            ) {
                continue;
            }
            long origin = cellOrigin(key);
            int exhaust = exhaustProfile(world, state, origin, component);
            if (exhaust != 0) {
                state.vents.put(key, (byte) exhaust);
            }
            int profile = ceilingProfile(world, state, cfg, origin, component);
            if (profile != 0) {
                state.ceilings.put(key, (byte) profile);
            }
        }
    }

    private int ceilingProfile(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg,
        long origin,
        int component
    ) {
        int x = BlockPos.unpackLongX(origin);
        int y = BlockPos.unpackLongY(origin);
        int z = BlockPos.unpackLongZ(origin);
        int above = geometry(
            world,
            state,
            cfg,
            BlockPos.asLong(x, y + 2, z)
        ).mask();
        int own = geometry(world, state, cfg, origin).mask();
        int profile = 0;
        for (int column = 0; column < 4; column++) {
            int level = 0;
            if (
                (component & (1 << (column + 4))) != 0 &&
                (above & (1 << column)) == 0 &&
                loadedChunk(
                    world,
                    x + (column & 1),
                    y + 2,
                    z + (column >>> 1)
                ) != null
            ) {
                level = 2;
            } else if (
                (component & (1 << column)) != 0 &&
                (own & (1 << (column + 4))) == 0
            ) {
                level = 1;
            }
            profile |= level << (column * 2);
        }
        return profile;
    }

    private boolean hasOpening(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg,
        long origin,
        int component,
        int direction
    ) {
        int face = SmokeCellGeometry.faceBits(component, direction);
        if (face == 0) {
            return false;
        }
        int x =
            BlockPos.unpackLongX(origin) + SmokeCellGeometry.DX[direction] * 2;
        int y =
            BlockPos.unpackLongY(origin) + SmokeCellGeometry.DY[direction] * 2;
        int z =
            BlockPos.unpackLongZ(origin) + SmokeCellGeometry.DZ[direction] * 2;
        int neighbor = geometry(
            world,
            state,
            cfg,
            BlockPos.asLong(x, y, z)
        ).mask();
        return (
            (face &
                SmokeCellGeometry.faceBits(
                    neighbor,
                    SmokeCellGeometry.opposite(direction)
                )) !=
            0
        );
    }

    private int exhaustProfile(
        ServerWorld world,
        WorldState state,
        long origin,
        int component
    ) {
        int x = BlockPos.unpackLongX(origin);
        int y = BlockPos.unpackLongY(origin);
        int z = BlockPos.unpackLongZ(origin);
        WorldChunk chunk = loadedChunk(world, x, y, z);
        if (chunk == null) {
            return 0;
        }
        int result = 0;
        for (int column = 0; column < 4; column++) {
            int index = column + 4;
            if ((component & (1 << index)) == 0) {
                index = column;
            }
            if ((component & (1 << index)) == 0) {
                continue;
            }
            int columnX = x + (column & 1);
            int columnZ = z + (column >>> 1);
            long columnKey = BlockPos.asLong(columnX, 0, columnZ);
            int top = state.columnTops.getAndMoveToLast(columnKey);
            if (top == Integer.MIN_VALUE) {
                int blockingY = chunk.sampleHeightmap(
                    Heightmap.Type.WORLD_SURFACE,
                    columnX & 15,
                    columnZ & 15
                );
                while (blockingY >= world.getBottomY()) {
                    state.mutablePos.set(columnX, blockingY, columnZ);
                    if (
                        !isSmokePassable(
                            world,
                            chunk.getBlockState(state.mutablePos),
                            state.mutablePos
                        )
                    ) {
                        break;
                    }
                    blockingY--;
                }
                top = blockingY + 1;
                if (
                    state.columnTops.size() >=
                    SmokeConfig.INSTANCE.maxGeometryCacheCellsPerWorld * 4
                ) {
                    state.columnTops.removeFirstInt();
                }
                state.columnTops.putAndMoveToLast(columnKey, top);
            }
            if (y + (index >>> 2) >= top) {
                result |= 1 << index;
            }
        }
        return result;
    }

    private SmokeCellGeometry geometry(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg,
        long origin
    ) {
        int x = BlockPos.unpackLongX(origin);
        int y = BlockPos.unpackLongY(origin);
        int z = BlockPos.unpackLongZ(origin);
        WorldChunk chunk = loadedChunk(world, x, y, z);
        if (chunk == null) {
            return SmokeCellGeometry.of(0);
        }
        long cached = state.geometry.getAndMoveToLast(origin);
        if (
            cached != -1L &&
            state.simulationStep - (cached >>> 8) <= cfg.passabilityCacheSteps
        ) {
            return SmokeCellGeometry.of((int) cached);
        }
        int mask = 0;
        for (int index = 0; index < 8; index++) {
            state.mutablePos.set(
                x + (index & 1),
                y + (index >>> 2),
                z + ((index >>> 1) & 1)
            );
            if (!world.isInBuildLimit(state.mutablePos)) {
                continue;
            }
            BlockState block = chunk.getBlockState(state.mutablePos);
            boolean passable = isSmokePassable(world, block, state.mutablePos);
            if (passable) {
                mask |= 1 << index;
            }
        }
        if (
            !state.geometry.containsKey(origin) &&
            state.geometry.size() >= cfg.maxGeometryCacheCellsPerWorld
        ) {
            state.geometry.removeFirstLong();
        }
        state.geometry.putAndMoveToLast(
            origin,
            (state.simulationStep << 8) | mask
        );
        return SmokeCellGeometry.of(mask);
    }

    private static boolean isSmokePassable(
        ServerWorld world,
        BlockState block,
        BlockPos pos
    ) {
        return (
            block.isAir() ||
            (block.getFluidState().isEmpty() &&
                ((block.contains(Properties.OPEN) &&
                    block.get(Properties.OPEN)) ||
                    block.getCollisionShape(world, pos).isEmpty()))
        );
    }

    private boolean isPassable(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg,
        int x,
        int y,
        int z
    ) {
        return (
            geometry(world, state, cfg, cellOrigin(x, y, z)).componentAt(
                blockIndex(x, y, z)
            ) != 0
        );
    }

    private float densityAt(
        ServerWorld world,
        WorldState state,
        SmokeConfig cfg,
        double x,
        double y,
        double z
    ) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        long origin = cellOrigin(bx, by, bz);
        int current = geometry(world, state, cfg, origin).componentAt(
            blockIndex(bx, by, bz)
        );
        if (current == 0) {
            return 0.0f;
        }
        float mass = 0.0f;
        int baseX = BlockPos.unpackLongX(origin);
        int baseY = BlockPos.unpackLongY(origin);
        int baseZ = BlockPos.unpackLongZ(origin);
        int currentMask = geometry(world, state, cfg, origin).mask();
        for (int index = 0; index < 8; index++) {
            long key = blockKey(baseX, baseY, baseZ, index);
            int previous = state.components.get(key);
            int surviving = previous & currentMask;
            int overlap = Integer.bitCount(surviving & current);
            if (overlap != 0) {
                mass +=
                    (state.density.get(key) * overlap) /
                    Integer.bitCount(surviving);
            }
        }
        return Math.min(
            cfg.maxDensity,
            SmokeTransport.concentration(mass, current)
        );
    }

    private static boolean accumulate(
        Long2FloatOpenHashMap map,
        long key,
        float amount,
        SmokeConfig cfg
    ) {
        if (!(amount > 0.0f) || !Float.isFinite(amount)) {
            return true;
        }
        if (
            !map.containsKey(key) &&
            map.size() >= cfg.maxActiveCellsPerWorld * 25
        ) {
            return false;
        }
        map.addTo(key, amount);
        return true;
    }

    private static float sourceEmission(BlockState block, SmokeConfig cfg) {
        if (!block.contains(Properties.LIT) || !block.get(Properties.LIT)) {
            return 0.0f;
        }
        if (block.getBlock() instanceof SmokerBlock) {
            return cfg.smokerEmission;
        }
        if (block.getBlock() instanceof AbstractFurnaceBlock) {
            return cfg.furnaceEmission;
        }
        return block.getBlock() instanceof CampfireBlock
            ? cfg.campfireEmission
            : 0.0f;
    }

    private static WorldChunk loadedChunk(
        ServerWorld world,
        int x,
        int y,
        int z
    ) {
        if (y < world.getBottomY() || y >= world.getTopYInclusive() + 1) {
            return null;
        }
        return world
            .getChunkManager()
            .getWorldChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    private static long chunkKey(long block) {
        return ChunkPos.toLong(
            Math.floorDiv(BlockPos.unpackLongX(block), 16),
            Math.floorDiv(BlockPos.unpackLongZ(block), 16)
        );
    }

    private static long cellOrigin(long block) {
        return cellOrigin(
            BlockPos.unpackLongX(block),
            BlockPos.unpackLongY(block),
            BlockPos.unpackLongZ(block)
        );
    }

    private static long cellOrigin(int x, int y, int z) {
        return BlockPos.asLong(
            Math.floorDiv(x, 2) * 2,
            Math.floorDiv(y, 2) * 2,
            Math.floorDiv(z, 2) * 2
        );
    }

    private static int blockIndex(long block) {
        return blockIndex(
            BlockPos.unpackLongX(block),
            BlockPos.unpackLongY(block),
            BlockPos.unpackLongZ(block)
        );
    }

    private static int blockIndex(int x, int y, int z) {
        return (x & 1) | ((z & 1) << 1) | ((y & 1) << 2);
    }

    private static long blockKey(int x, int y, int z, int index) {
        return BlockPos.asLong(
            x + (index & 1),
            y + (index >>> 2),
            z + ((index >>> 1) & 1)
        );
    }

    private static final class WorldState {

        private final LongOpenHashSet sources = new LongOpenHashSet();
        private final Long2ObjectOpenHashMap<LongOpenHashSet> sourcesByChunk =
            new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet activeSources = new LongOpenHashSet();
        private final LongOpenHashSet unloadedChunks = new LongOpenHashSet();
        private final SmokeSourceAdmission admission =
            new SmokeSourceAdmission();
        private Long2FloatOpenHashMap density = new Long2FloatOpenHashMap();
        private Long2FloatOpenHashMap next = new Long2FloatOpenHashMap();
        private final Long2FloatOpenHashMap working =
            new Long2FloatOpenHashMap();
        private final Long2IntOpenHashMap components =
            new Long2IntOpenHashMap();
        private final Long2LongLinkedOpenHashMap geometry =
            new Long2LongLinkedOpenHashMap();
        private final Long2IntLinkedOpenHashMap columnTops =
            new Long2IntLinkedOpenHashMap();
        private final Long2ByteOpenHashMap ceilings =
            new Long2ByteOpenHashMap();
        private final Long2ByteOpenHashMap vents = new Long2ByteOpenHashMap();
        private final BlockPos.Mutable mutablePos = new BlockPos.Mutable();
        private final SmokeParticleRenderer renderer =
            new SmokeParticleRenderer();
        private final long[] flowKeys = new long[24];
        private final float[] flowAmounts = new float[24];
        private final LongOpenHashSet selected = new LongOpenHashSet();
        private long[] selectedKeys = new long[0];
        private float[] selectedScores = new float[0];
        private long worldTick;
        private long simulationStep;

        private WorldState() {
            geometry.defaultReturnValue(-1L);
            columnTops.defaultReturnValue(Integer.MIN_VALUE);
        }
    }
}
