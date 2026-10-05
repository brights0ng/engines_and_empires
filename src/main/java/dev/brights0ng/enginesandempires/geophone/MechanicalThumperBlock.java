package dev.brights0ng.enginesandempires.geophone;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ICogWheel;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A big metal slab, wound up by Create's rotational power and slammed down onto a {@link StrikePlateBlock} once released,
 * sending a vibration out to {@link SeismicShots#MECHANICAL_RANGE}: the mechanical tier of the seismic survey's shot
 * sources, after the sledgehammer and a hand-struck strike plate.
 *
 * <p>It is driven the way Create's own Mechanical Mixer is, turned on its side: rather than a shaft plugging straight into
 * it, it meshes with a cogwheel placed against one of its horizontal faces, so a cog whose own axis faces towards the
 * thumper is what actually turns it, not a shaft end-on. {@link #getRotationAxis} runs along that same horizontal line,
 * from a {@code HORIZONTAL_AXIS} property inherited from {@link HorizontalAxisKineticBlock}; only {@link #hasShaftTowards}
 * is overridden, from that base class's "yes, a shaft may plug in" to "no, only a meshing cog will do," matching the Mixer.
 *
 * <p>It needs a strike plate directly beneath it: that is both what it slams into and, thematically, the very thing a
 * sledgehammer would strike by hand for the weakest tier of the same shot.
 */
public class MechanicalThumperBlock extends HorizontalAxisKineticBlock implements IBE<MechanicalThumperBlockEntity>, ICogWheel {

    public MechanicalThumperBlock(Properties properties) {
        super(properties);
    }

    /** Driven only by a meshing cogwheel, the way the Mechanical Mixer is: a shaft cannot plug straight into it. */
    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return false;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(SeismicContent.STRIKE_PLATE.get());
    }

    @Override
    public SpeedLevel getMinimumRequiredSpeedLevel() {
        return SpeedLevel.MEDIUM;
    }

    /** A redstone pulse while armed lets it go. Nothing happens if it is not armed, or the signal is not actually new. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide || !level.hasNeighborSignal(pos)) {
            return;
        }
        withBlockEntityDo(level, pos, MechanicalThumperBlockEntity::releaseIfArmed);
    }

    @Override
    public Class<MechanicalThumperBlockEntity> getBlockEntityClass() {
        return MechanicalThumperBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends MechanicalThumperBlockEntity> getBlockEntityType() {
        return SeismicContent.MECHANICAL_THUMPER_ENTITY.get();
    }
}
