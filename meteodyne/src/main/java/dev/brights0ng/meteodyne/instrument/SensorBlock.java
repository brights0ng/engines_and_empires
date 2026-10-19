// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** A configurable analogue weather sensor on a floor, wall, or ceiling mounting plate. */
public abstract class SensorBlock extends FaceAttachedHorizontalDirectionalBlock implements EntityBlock {
    public static final IntegerProperty POWER = BlockStateProperties.POWER;
    private static final VoxelShape[][] SHAPES = new VoxelShape[3][4];
    static {
        for (AttachFace face : AttachFace.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                SHAPES[face.ordinal()][facing.get2DDataValue()] = SensorShape.shape(face, facing);
            }
        }
    }

    protected SensorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACE, AttachFace.FLOOR).setValue(FACING, Direction.NORTH).setValue(POWER, 0));
    }

    protected abstract BlockEntityType<? extends SensorBlockEntity> entityType();

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(FACE, FACING, POWER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction clicked = context.getClickedFace();
        AttachFace face = clicked == Direction.DOWN ? AttachFace.CEILING
                : clicked == Direction.UP ? AttachFace.FLOOR : AttachFace.WALL;
        Direction facing = face == AttachFace.WALL ? clicked : context.getHorizontalDirection().getOpposite();
        return defaultBlockState().setValue(FACE, face).setValue(FACING, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES[state.getValue(FACE).ordinal()][state.getValue(FACING).get2DDataValue()];
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return type == entityType()
                ? (l, p, s, entity) -> ((SensorBlockEntity) entity).tick() : null;
    }

    @Override
    protected boolean isSignalSource(BlockState state) { return true; }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return state.getValue(POWER); }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return direction == Direction.UP ? state.getValue(POWER) : 0;
    }

    @Override
    public boolean canConnectRedstone(BlockState state, BlockGetter level, BlockPos pos, Direction direction) { return direction != null; }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && player.mayBuild()
                && level.getBlockEntity(pos) instanceof SensorBlockEntity sensor) {
            server.openMenu(sensor, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        super.onRemove(state, level, pos, next, moving);
        if (!state.is(next.getBlock())) {
            level.updateNeighborsAt(pos, this);
            level.updateNeighborsAt(pos.below(), this);
        }
    }
}
