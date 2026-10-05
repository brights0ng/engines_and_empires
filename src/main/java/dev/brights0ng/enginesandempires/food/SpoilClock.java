package dev.brights0ng.enginesandempires.food;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * The clock food ages by: the Overworld's game time, which every dimension shares, which {@code /time set} does not touch,
 * and which only runs while the world does (so food does not spoil while a server is off).
 *
 * <p>Item stacks are handled on both sides and on no particular thread, so this answers for whoever asks: the client's own
 * copy of the time on the client thread, the server's otherwise. It answers -1 when there is no clock yet (a world still
 * loading); callers leave food alone then.
 */
public final class SpoilClock {

    private static volatile LongSupplier clientTime = () -> -1L;
    private static volatile BooleanSupplier onClientThread = () -> false;

    /** Called by the client setup, so that client code gets the client's clock (the client classes do not exist on servers). */
    public static void setClient(LongSupplier time, BooleanSupplier onThread) {
        clientTime = time;
        onClientThread = onThread;
    }

    /** The game time food ages by, or -1 when there is none. */
    public static long now() {
        if (onClientThread.getAsBoolean()) {
            return clientTime.getAsLong();
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return -1L;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld == null ? -1L : overworld.getGameTime();
    }

    /** Whether the caller is the server's own thread (the only place food is stamped as a side effect of looking at it). */
    public static boolean onServerThread() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread();
    }

    private SpoilClock() {
    }
}
