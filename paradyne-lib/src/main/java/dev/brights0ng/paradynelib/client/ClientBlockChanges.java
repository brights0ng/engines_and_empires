// Written by Claude.
package dev.brights0ng.paradynelib.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Tells client-side listeners about every block change in the client level: the player's own placing and breaking,
 * changes the server sends (single blocks and section batches), explosions and pistons alike. NeoForge has no client
 * event for this; {@code client.LevelRendererBlockChangeMixin} feeds it from {@code LevelRenderer.blockChanged}, the one
 * call every client block change goes through to re-mesh its section.
 *
 * <p>Also fired for block entity data updates, where the old and new states are the same object; listeners that care
 * only about the block itself should skip those. Called on the client thread.
 */
public final class ClientBlockChanges {

    @FunctionalInterface
    public interface Listener {
        void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    public static void register(Listener listener) {
        LISTENERS.add(listener);
    }

    /** Called by the mixin. */
    public static void fire(BlockPos pos, BlockState oldState, BlockState newState) {
        for (Listener listener : LISTENERS) {
            listener.onBlockChanged(pos, oldState, newState);
        }
    }

    private ClientBlockChanges() {
    }
}
