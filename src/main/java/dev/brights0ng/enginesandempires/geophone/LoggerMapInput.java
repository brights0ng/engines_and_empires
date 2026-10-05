package dev.brights0ng.enginesandempires.geophone;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Where on a smart logger's map a click lands, shared by the client (which works the map's controls) and the server
 * (which must not let a right-click on the map do anything else, such as placing the item in hand).
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class LoggerMapInput {

    /** A point on a logger's top: which logger, and where, as {u, v} (see {@link LoggerDisplay}). */
    public record Spot(SmartLoggerBlockEntity logger, double u, double v) {

        /** Whether it is on the map's screen, bezel and all. */
        public boolean onScreen() {
            return LoggerDisplay.inScreenArea(u, v);
        }
    }

    /** Where a click at {@code hit} on the top face of the block at {@code pos} lands, or null if not on a logger's top. */
    public static Spot spot(BlockGetter level, BlockPos pos, Direction face, Vec3 hit) {
        if (face != Direction.UP) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!state.is(SeismicContent.SMART_LOGGER.get())) {
            return null;
        }
        if (!(level.getBlockEntity(SmartLoggerBlock.masterPos(pos, state)) instanceof SmartLoggerBlockEntity logger)
                || !logger.isMaster()) {
            return null;
        }
        Vec3 middle = logger.middle();
        double[] local = logger.frame().local(middle.x, middle.z, hit.x, hit.z);
        return new Spot(logger, local[0], local[1]);
    }

    /**
     * On the server, a right-click on the map is the map's, whatever is in hand and whether or not the player is sneaking:
     * the client works out what it means and asks for anything that changes the logger. Nothing else happens.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide) {
            return;
        }
        Spot spot = spot(event.getLevel(), event.getPos(), event.getFace(), event.getHitVec().getLocation());
        if (spot != null && spot.onScreen()) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    private LoggerMapInput() {
    }
}
