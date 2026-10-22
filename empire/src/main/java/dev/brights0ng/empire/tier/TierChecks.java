// Written by Gemini.
package dev.brights0ng.empire.tier;

import java.util.function.Supplier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class TierChecks {

    private static TierCheck delegate = TierCheck.ALWAYS_PASS;

    public static void install(Supplier<TierCheck> supplier) {
        delegate = supplier.get();
    }

    public static boolean columnSettled(ServerLevel level, ChunkPos chunk) {
        return delegate.columnSettled(level, chunk);
    }

    public static void reset() {
        delegate = TierCheck.ALWAYS_PASS;
    }

    private TierChecks() {
    }
}
