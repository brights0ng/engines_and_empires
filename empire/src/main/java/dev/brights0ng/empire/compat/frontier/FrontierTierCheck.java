// Written by Gemini.
package dev.brights0ng.empire.compat.frontier;

import dev.brights0ng.empire.tier.TierCheck;
import dev.brights0ng.frontier.Tier;
import dev.brights0ng.frontier.tier.FrontierLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

public final class FrontierTierCheck implements TierCheck {

    public static TierCheck create() {
        return new FrontierTierCheck();
    }

    @Override
    public boolean columnSettled(ServerLevel level, ChunkPos chunk) {
        int cx = (chunk.x << 4) + 8;
        int cz = (chunk.z << 4) + 8;
        for (int sy = level.getMinSection(); sy < level.getMaxSection(); sy++) {
            int cy = (sy << 4) + 8;
            BlockPos pos = new BlockPos(cx, cy, cz);
            if (FrontierLevels.tierAt(level, pos).atLeast(Tier.SETTLED)) {
                return true;
            }
        }
        return false;
    }
}
