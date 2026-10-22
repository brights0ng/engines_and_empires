// Written by Gemini.
package dev.brights0ng.empire.bonfire;

import java.util.HashMap;
import java.util.Map;

import dev.brights0ng.empire.EmpireContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.mojang.serialization.MapCodec;

public class BonfirePartBlock extends Block {

    public static final MapCodec<BonfirePartBlock> CODEC = simpleCodec(BonfirePartBlock::new);

    public static final IntegerProperty OFFSET = IntegerProperty.create("offset", 0, 25);

    public static final BlockPos[] PART_OFFSETS = new BlockPos[26];
    private static final Map<BlockPos, Integer> OFFSET_TO_INDEX = new HashMap<>();

    static {
        int idx = 0;
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos offset = new BlockPos(dx, dy, dz);
                    PART_OFFSETS[idx] = offset;
                    OFFSET_TO_INDEX.put(offset, idx);
                    idx++;
                }
            }
        }
    }

    public static int getOffsetIndex(int dx, int dy, int dz) {
        Integer idx = OFFSET_TO_INDEX.get(new BlockPos(dx, dy, dz));
        return idx != null ? idx : 0;
    }

    public BonfirePartBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(OFFSET, 0));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OFFSET);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    @Override
    public VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    public float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    public ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(EmpireContent.BONFIRE_ITEM.get());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        int offset = state.getValue(OFFSET);
        BlockPos mainPos = pos.subtract(PART_OFFSETS[offset]);
        if (level.getBlockState(mainPos).is(EmpireContent.BONFIRE.get())) {
            level.destroyBlock(mainPos, !player.isCreative());
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())) {
            int offset = state.getValue(OFFSET);
            BlockPos mainPos = pos.subtract(PART_OFFSETS[offset]);
            if (!BonfireBlock.isRemovingBonfire() && level.getBlockState(mainPos).is(EmpireContent.BONFIRE.get())) {
                level.destroyBlock(mainPos, true);
            }
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        int offset = state.getValue(OFFSET);
        BlockPos mainPos = pos.subtract(PART_OFFSETS[offset]);
        BlockState mainState = level.getBlockState(mainPos);
        if (mainState.getBlock() instanceof BonfireBlock bonfireBlock) {
            return bonfireBlock.useItemOn(stack, mainState, level, mainPos, player, hand, hitResult.withPosition(mainPos));
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        int offset = state.getValue(OFFSET);
        BlockPos mainPos = pos.subtract(PART_OFFSETS[offset]);
        BlockState mainState = level.getBlockState(mainPos);
        if (mainState.getBlock() instanceof BonfireBlock bonfireBlock) {
            return bonfireBlock.useWithoutItem(mainState, level, mainPos, player, hitResult.withPosition(mainPos));
        }
        return InteractionResult.PASS;
    }
}
