package net.minecraft.block;
import net.minecraft.state.property.Properties.Property;
import net.minecraft.util.math.Direction;
public class AbstractFurnaceBlock extends Block {
    public static final Property<Direction> FACING = new Property<>("facing");
}
