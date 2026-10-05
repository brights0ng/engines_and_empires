package dev.brights0ng.enginesandempires.geophone;

import org.jetbrains.annotations.Nullable;

import com.simibubi.create.content.kinetics.base.HorizontalAxisKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import com.simibubi.create.foundation.fluid.FluidHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The combustive thumper: the fuel-fired tier of the seismic survey's shot sources, above the mechanical thumper. See
 * {@link CombustiveThumperBlockEntity} for how it works.
 *
 * <p>It stands three blocks tall: this block is the base (the drive, the anvil, and the block entity that runs the whole
 * machine), with a rail section and a fuel cylinder above it ({@link CombustiveThumperColumnBlock}). Placing it puts up
 * the whole column, so it needs two free blocks above; breaking any block of it takes down all three.
 *
 * <p>Driven the way Create's Mechanical Press is: a shaft plugs straight into either end of its horizontal axis. Like
 * every thumper it needs a {@link StrikePlateBlock} directly beneath it.
 *
 * <p>Its tank takes fluid from pipes and buckets, and gives it back to pipes and empty buckets. A sneak-click with a
 * wrench flushes it outright, voiding whatever is inside; with nothing inside, the wrench picks the machine up as usual.
 */
public class CombustiveThumperBlock extends HorizontalAxisKineticBlock implements IBE<CombustiveThumperBlockEntity> {

    public CombustiveThumperBlock(Properties properties) {
        super(properties);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getBlockState(pos.below()).is(SeismicContent.STRIKE_PLATE.get());
    }

    /** Only placeable with room for the whole column above. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() + 2 >= level.getMaxBuildHeight()
                || !level.getBlockState(pos.above()).canBeReplaced(context)
                || !level.getBlockState(pos.above(2)).canBeReplaced(context)) {
            return null;
        }
        return super.getStateForPlacement(context);
    }

    /** Puts up the rail section and the fuel cylinder above the base. */
    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        placeColumn(level, pos, state.getValue(HORIZONTAL_AXIS));
    }

    /** The two blocks above a base at {@code pos}. Also used by the game tests, which place blocks directly. */
    public static void placeColumn(Level level, BlockPos pos, Axis axis) {
        BlockState column = SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get().defaultBlockState()
                .setValue(CombustiveThumperColumnBlock.HORIZONTAL_AXIS, axis);
        level.setBlock(pos.above(), column.setValue(CombustiveThumperColumnBlock.PART, CombustiveThumperColumnBlock.Part.MIDDLE),
                Block.UPDATE_ALL);
        level.setBlock(pos.above(2), column.setValue(CombustiveThumperColumnBlock.PART, CombustiveThumperColumnBlock.Part.TOP),
                Block.UPDATE_ALL);
    }

    /** Whether the two column blocks above a base at {@code pos} are there and intact. */
    public static boolean columnIntact(LevelReader level, BlockPos pos) {
        return isPart(level.getBlockState(pos.above()), CombustiveThumperColumnBlock.Part.MIDDLE)
                && isPart(level.getBlockState(pos.above(2)), CombustiveThumperColumnBlock.Part.TOP);
    }

    private static boolean isPart(BlockState state, CombustiveThumperColumnBlock.Part part) {
        return state.is(SeismicContent.COMBUSTIVE_THUMPER_COLUMN.get())
                && state.getValue(CombustiveThumperColumnBlock.PART) == part;
    }

    /** A redstone pulse fires it, if the ram is fully raised. (A pulse to either block above does the same.) */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide || !level.hasNeighborSignal(pos)) {
            return;
        }
        withBlockEntityDo(level, pos, CombustiveThumperBlockEntity::fireIfArmed);
    }

    /** Filling from, or emptying into, a bucket or any other fluid container, the same way Create's own tanks do. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        return onBlockEntityUseItemOn(level, pos, be -> {
            if (FluidHelper.tryEmptyItemIntoBE(level, player, hand, stack, be)
                    || FluidHelper.tryFillItemFromBE(level, player, hand, stack, be)) {
                return ItemInteractionResult.SUCCESS;
            }
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        });
    }

    /** A three-tall machine cannot be turned in place. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /** Flushes the tank if there is anything in it; otherwise picks the machine up, as a wrench normally does. */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (level.getBlockEntity(pos) instanceof CombustiveThumperBlockEntity be && !be.fluid().isEmpty()) {
            if (!level.isClientSide) {
                be.flush();
                level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 0.8F);
            }
            return InteractionResult.SUCCESS;
        }
        return super.onSneakWrenched(state, context);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    public Class<CombustiveThumperBlockEntity> getBlockEntityClass() {
        return CombustiveThumperBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CombustiveThumperBlockEntity> getBlockEntityType() {
        return SeismicContent.COMBUSTIVE_THUMPER_ENTITY.get();
    }
}
