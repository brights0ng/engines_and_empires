package dev.brights0ng.enginesandempires.geophone;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Stakes a {@link GeophoneEntity} into the block that is right-clicked, exactly where it was clicked: standing up out
 * of the top of a block, hanging from the underside, or sticking out sideways from a wall, whichever face was clicked.
 *
 * <p>The face must be solid: a geophone cannot be staked into leaves, a slab's edge, or anything else with no solid face
 * there to drive a rod into.
 */
public class GeophoneItem extends Item {

    private final GeophoneTier tier;

    public GeophoneItem(Properties properties, GeophoneTier tier) {
        super(properties);
        this.tier = tier;
    }

    /** Which ores the geophone this places hears. */
    public GeophoneTier tier() {
        return tier;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction face = context.getClickedFace();
        BlockState state = level.getBlockState(pos);
        if (!state.isFaceSturdy(level, pos, face)) {
            return InteractionResult.FAIL;
        }

        if (level instanceof ServerLevel server) {
            Vec3 point = context.getClickLocation();
            GeophoneEntity geophone = new GeophoneEntity(SeismicContent.GEOPHONE_ENTITY.get(), server);
            geophone.stake(point, face, pos, tier);
            server.addFreshEntity(geophone);
            Player player = context.getPlayer();
            if (player == null || !player.getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
