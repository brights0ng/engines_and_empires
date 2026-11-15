// Written by Claude (GreedyFluids M7d d3); edited by Claude (M7c: the background is Temperature.at).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.meteodyne.climate.Temperature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The air temperature at a point in airflow mode (M7d d3; Bright, 2026-11-15), for readers such as thermometers,
 * player climate and ice chests (wired in part e):
 * <ol>
 *   <li>{@link Source#LIVE}: the running tile's latest frame;</li>
 *   <li>{@link Source#SAVED}: else what the point's chunk saved for that block, decayed toward the background
 *       ({@link AirflowSaved}); the air value, or the wall value for a block inside a wall;</li>
 *   <li>{@link Source#BACKGROUND}: else the background.</li>
 * </ol>
 * The background is Meteodyne's air temperature at the block ({@code Temperature.at}), the field the tiles
 * interpolate (M7c), so a saved offset is read against about the background it was saved against. Server thread.
 */
public final class AirflowTemperatures {

    public enum Source { LIVE, SAVED, BACKGROUND }

    /** A temperature, C, where it came from, and (LIVE only) the game tick its frame stands for, else -1. */
    public record Reading(double celsius, Source source, long tick) {
    }

    /** The air temperature at (x, y, z), world blocks; null when the engine isn't AIRFLOW or this isn't its level. */
    public static Reading at(ServerLevel level, double x, double y, double z) {
        AirflowRegions regions = AirflowRegions.get();
        if (regions == null || regions.level() != level) {
            return null;
        }
        return at(regions, x, y, z);
    }

    static Reading at(AirflowRegions regions, double x, double y, double z) {
        ServerLevel level = regions.level();
        AirflowRegions.Region r = regions.regionAt(x, y, z);
        AirflowRuntime.Frame f = r == null || r.slot() == null ? null : r.slot().frame();
        if (f != null && f.frame() != null) {
            double t = f.frame().temperature(x - r.x0(), y - r.y0(), z - r.z0());
            if (Double.isFinite(t)) {
                return new Reading(t, Source.LIVE, f.tick());
            }
        }
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        double background = Temperature.at(level, new BlockPos(bx, by, bz));
        LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        if (chunk != null && chunk.hasData(AirflowContent.CHUNK_DATA)) {
            AirflowSaved.Section s = chunk.getData(AirflowContent.CHUNK_DATA).section(SectionPos.blockToSectionCoord(by));
            if (s != null) {
                int local = ((by & 15) * 16 + (bz & 15)) * 16 + (bx & 15);
                long now = level.getGameTime();
                double offset = s.offset(local, false, now);
                if (Double.isNaN(offset)) {
                    offset = s.offset(local, true, now);
                }
                if (!Double.isNaN(offset)) {
                    return new Reading(background + offset, Source.SAVED, -1);
                }
            }
        }
        return new Reading(background, Source.BACKGROUND, -1);
    }

    private AirflowTemperatures() {
    }
}
