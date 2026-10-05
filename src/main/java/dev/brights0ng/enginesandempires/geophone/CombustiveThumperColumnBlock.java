package dev.brights0ng.enginesandempires.geophone;

import com.simibubi.create.api.equipment.goggles.IProxyHoveringInformation;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import com.simibubi.create.foundation.fluid.FluidHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The upper two blocks of the combustive thumper's three-tall column: the open rail section in the middle and the fuel
 * cylinder on top. They are only ever placed by the base ({@link CombustiveThumperBlock}) and hold no state of their own:
 * everything (redstone, buckets, pipes, the wrench, the goggles) is handed down to the base, so the whole column behaves
 * as one machine whichever block of it you touch.
 *
 * <p>They never drop anything. A player breaking one breaks the base instead (which drops the machine); anything else
 * removing one (an explosion, a command) leaves the base to notice its column is broken and break itself, see
 * {@link CombustiveThumperBlockEntity}. A middle block with no base under it, or a top with no middle, simply vanishes.
 */
public class CombustiveThumperColumnBlock extends Block implements IWrenchable, IProxyHoveringInformation {

    public enum Part implements StringRepresentable {
        MIDDLE("middle", 1), TOP("top", 2);

        private final String name;
        private final int height;

        Part(String name, int height) {
            this.name = name;
            this.height = height;
        }

        /** How many blocks above the base this part sits. */
        public int height() {
            return height;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final EnumProperty<Axis> HORIZONTAL_AXIS = BlockStateProperties.HORIZONTAL_AXIS;

    public CombustiveThumperColumnBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(PART, Part.MIDDLE).setValue(HORIZONTAL_AXIS, Axis.Z));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(PART, HORIZONTAL_AXIS);
        super.createBlockStateDefinition(builder);
    }

    public static BlockPos basePos(BlockPos pos, BlockState state) {
        return pos.below(state.getValue(PART).height());
    }

    private static CombustiveThumperBlockEntity base(LevelReader level, BlockPos pos, BlockState state) {
        BlockEntity be = level.getBlockEntity(basePos(pos, state));
        return be instanceof CombustiveThumperBlockEntity thumper ? thumper : null;
    }

    /** A part with nothing correct under it goes: the middle needs the base, the top needs the middle. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        if (direction == Direction.DOWN) {
            boolean supported = state.getValue(PART) == Part.MIDDLE
                    ? neighborState.is(SeismicContent.COMBUSTIVE_THUMPER.get())
                    : neighborState.is(this) && neighborState.getValue(PART) == Part.MIDDLE;
            if (!supported) {
                return Blocks.AIR.defaultBlockState();
            }
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    /** Breaking any part breaks the base, which is what drops the machine (unless in creative). */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos base = basePos(pos, state);
            if (level.getBlockState(base).is(SeismicContent.COMBUSTIVE_THUMPER.get())) {
                level.destroyBlock(base, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    /** A redstone signal reaching any block of the column fires it. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighborBlock, BlockPos neighborPos,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, neighborBlock, neighborPos, movedByPiston);
        if (level.isClientSide || !level.hasNeighborSignal(pos)) {
            return;
        }
        CombustiveThumperBlockEntity thumper = base(level, pos, state);
        if (thumper != null) {
            thumper.fireIfArmed();
        }
    }

    /** Buckets and other fluid containers fill and drain the base's tank. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        CombustiveThumperBlockEntity thumper = base(level, pos, state);
        if (thumper != null && (FluidHelper.tryEmptyItemIntoBE(level, player, hand, stack, thumper)
                || FluidHelper.tryFillItemFromBE(level, player, hand, stack, thumper))) {
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** The column cannot be turned. */
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        return InteractionResult.PASS;
    }

    /** Flushes the tank if there is anything in it, otherwise picks up the whole machine, exactly as on the base. */
    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        Level level = context.getLevel();
        BlockPos base = basePos(context.getClickedPos(), state);
        CombustiveThumperBlockEntity thumper = base(level, context.getClickedPos(), state);
        if (thumper != null && !thumper.fluid().isEmpty()) {
            if (!level.isClientSide) {
                thumper.flush();
                level.playSound(null, base, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 0.8F);
            }
            return InteractionResult.SUCCESS;
        }
        BlockState baseState = level.getBlockState(base);
        if (context.getPlayer() == null || !(baseState.getBlock() instanceof CombustiveThumperBlock baseBlock)) {
            return InteractionResult.PASS;
        }
        BlockHitResult atBase = new BlockHitResult(base.getCenter(), context.getClickedFace(), base, false);
        return baseBlock.onSneakWrenched(baseState, new UseOnContext(context.getPlayer(), context.getHand(), atBase));
    }

    /** Goggles aimed at any part show the base's readout. */
    @Override
    public BlockPos getInformationSource(Level level, BlockPos pos, BlockState state) {
        return basePos(pos, state);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(SeismicContent.COMBUSTIVE_THUMPER.get());
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
