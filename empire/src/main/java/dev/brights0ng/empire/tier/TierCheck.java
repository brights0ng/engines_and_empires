// Written by Gemini.
package dev.brights0ng.empire.tier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

@FunctionalInterface
public interface TierCheck {

    boolean columnSettled(ServerLevel level, ChunkPos chunk);

    TierCheck ALWAYS_PASS = (level, chunk) -> true;
}
