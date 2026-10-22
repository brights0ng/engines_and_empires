// Written by Gemini.
package dev.brights0ng.empire.bonfire;

import org.jetbrains.annotations.Nullable;

import dev.brights0ng.empire.EmpireContent;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public class BonfireItem extends BlockItem {

    public BonfireItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        BlockPos mainPos = context.getClickedPos();
        Level level = context.getLevel();

        if (!canPlaceBonfire(level, mainPos, context)) {
            return InteractionResult.FAIL;
        }

        InteractionResult result = super.place(context);
        if (result.consumesAction()) {
            placeParts(level, mainPos);
        }
        return result;
    }

    public static boolean canPlaceBonfire(Level level, BlockPos mainPos, @Nullable BlockPlaceContext context) {
        for (int dy = 0; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = mainPos.offset(dx, dy, dz);
                    if (!level.isInWorldBounds(p)) {
                        return false;
                    }
                    BlockState s = level.getBlockState(p);
                    if (context != null ? !s.canBeReplaced(context) : !s.canBeReplaced()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static void placeParts(Level level, BlockPos mainPos) {
        for (int i = 0; i < 26; i++) {
            BlockPos partPos = mainPos.offset(BonfirePartBlock.PART_OFFSETS[i]);
            BlockState partState = EmpireContent.BONFIRE_PART.get().defaultBlockState()
                    .setValue(BonfirePartBlock.OFFSET, i);
            level.setBlock(partPos, partState, Block.UPDATE_ALL);
        }
    }
}
