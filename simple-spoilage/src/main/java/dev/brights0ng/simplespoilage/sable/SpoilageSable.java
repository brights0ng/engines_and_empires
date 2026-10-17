package dev.brights0ng.simplespoilage.sable;

import dev.brights0ng.simplespoilage.FoodClimateSweep;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.level.ServerLevel;

/**
 * Simple Spoilage's Sable side (split step 2d): ships' holds age their food like any other place. Sable is optional, so
 * this is the only package that touches it, reached only from {@code SimpleSpoilage} when Sable is loaded.
 */
public final class SpoilageSable {

    public static void register() {
        FoodClimateSweep.shipChunks(SpoilageSable::addShipChunks);
    }

    /** Ships' chunks (Sable's plot chunks made in play send no load event). */
    private static void addShipChunks(ServerLevel level, LongArrayList queue) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        LongOpenHashSet seen = new LongOpenHashSet(queue);
        for (ServerSubLevel ship : container.getAllSubLevels()) {
            if (ship.isRemoved()) {
                continue;
            }
            for (var holder : ship.getPlot().getLoadedChunks()) {
                long key = holder.getPos().toLong();
                if (seen.add(key)) {
                    queue.add(key);
                }
            }
        }
    }

    private SpoilageSable() {
    }
}
