package net.minecraft.block;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Properties.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class BlockState {
    public record Shape(boolean isEmpty) {}
    public record Fluid(boolean isEmpty) {}
    public static final BlockState AIR = new BlockState(new Block(), true, true, true);
    public static final BlockState SOLID = new BlockState(new Block(), false, true, false);
    public static final BlockState GLASS = new BlockState(new Block(), false, true, false);
    public static final BlockState DECOR = new BlockState(new Block(), false, true, true);
    public static final BlockState WATER = new BlockState(new Block(), false, false, true);
    private final Block block;
    private final boolean air;
    private final Fluid fluid;
    private final Shape shape;
    private final Map<Property<?>, Object> properties = new HashMap<>();
    public BlockState(Block block, boolean air, boolean emptyFluid, boolean emptyShape) {
        this.block = block;
        this.air = air;
        fluid = new Fluid(emptyFluid);
        shape = new Shape(emptyShape);
    }
    public static BlockState source(Block block, boolean lit, Direction facing) {
        BlockState state = new BlockState(block, false, true, false).with(Properties.LIT, lit);
        if (block instanceof AbstractFurnaceBlock) state.with(AbstractFurnaceBlock.FACING, facing);
        return state;
    }
    public <T> BlockState with(Property<T> property, T value) { properties.put(property, value); return this; }
    public boolean contains(Property<?> property) { return properties.containsKey(property); }
    @SuppressWarnings("unchecked")
    public <T> T get(Property<T> property) {
        if (!contains(property)) throw new AssertionError("Absent fake property: " + property.name());
        return (T) properties.get(property);
    }
    public boolean isAir() { return air; }
    public Block getBlock() { return block; }
    public Fluid getFluidState() { return fluid; }
    public Shape getCollisionShape(ServerWorld world, BlockPos pos) {
        world.requireLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        return shape;
    }
}
