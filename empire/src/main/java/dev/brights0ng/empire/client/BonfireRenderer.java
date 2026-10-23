// Written by Claude.
package dev.brights0ng.empire.client;

import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.bonfire.BonfireBlockEntity;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.renderer.GeoBlockRenderer;

/**
 * Draws the bonfire at its core block. The core sits at the bottom centre of the 3x3x3 structure, which is where the
 * model's origin is, so no offset is needed; the render box covers the whole structure, so the model is not culled while
 * only its edges are on screen.
 */
public class BonfireRenderer extends GeoBlockRenderer<BonfireBlockEntity> {

    public BonfireRenderer(BlockEntityRendererProvider.Context context) {
        super(new BonfireModel<>(BonfireRenderer::flames));
    }

    static BonfireModel.Flames flames(BonfireBlockEntity bonfire) {
        BlockState state = bonfire.getBlockState();
        if (!state.hasProperty(BonfireBlock.LIT) || !state.getValue(BonfireBlock.LIT)) {
            return BonfireModel.Flames.OUT;
        }
        return state.getValue(BonfireBlock.ROARING) ? BonfireModel.Flames.ROARING : BonfireModel.Flames.LIT;
    }

    @Override
    public AABB getRenderBoundingBox(BonfireBlockEntity bonfire) {
        BlockPos pos = bonfire.getBlockPos();
        // Three blocks wide and deep; the roaring flames reach a little above the structure's three blocks.
        return new AABB(pos.getX() - 1, pos.getY(), pos.getZ() - 1, pos.getX() + 2, pos.getY() + 4, pos.getZ() + 2);
    }
}
