package dev.brights0ng.enginesandempires.geophone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;

/**
 * A thumper an incursion has choked with sculk (Frontier phase 6): dead weight, with no machinery that works. Broken, it
 * gives back a little of what it was made of; picked up with Silk Touch it can be cooked clean in a furnace (or by Create's
 * bulk blasting), which gives the working thumper back.
 *
 * <p>It keeps the axis the thumper was turned to, so it sits where the machine did. A choked combustive thumper is only its
 * base: the column above rots away when it chokes.
 */
public class ChokedThumperBlock extends Block {

    public static final EnumProperty<Direction.Axis> HORIZONTAL_AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    public ChokedThumperBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(HORIZONTAL_AXIS, Direction.Axis.Z));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_AXIS);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction.Axis axis = context.getHorizontalDirection().getAxis();
        return defaultBlockState().setValue(HORIZONTAL_AXIS, axis);
    }

    /** Now and then a wisp of soul rises off the sculk. */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.SCULK_SOUL, pos.getX() + random.nextDouble(), pos.getY() + 1.05,
                    pos.getZ() + random.nextDouble(), 0.0, 0.02, 0.0);
        }
    }
}
