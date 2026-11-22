// Written by Claude (GreedyFluids M7d d3); edited by Claude (M7c: the background is Temperature.at; M7e: the offset
// the readers use; M8b5a: a running tile's air is read as it is, the offset taken from the readers' own air).
// Edited by Claude: only a running tile's frame is live; a failed one's last frame may be what ran away (2026-11-21).
package dev.brights0ng.meteodyne.airflow;

import dev.brights0ng.meteodyne.climate.Temperature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/**
 * The air at a point in airflow mode (M7d d3, M7e; Bright, 2026-11-15), as an offset from Meteodyne's air temperature
 * there ({@code Temperature.at}, what the readers start from):
 * <ol>
 *   <li>{@link Source#LIVE}: the running tile's latest frame (not a failed tile's last, which may be what ran
 *       away), read as it is (M8b5a; Bright, 2026-11-17: a reading samples the air where it is, not the outside air
 *       plus the simulation's offset from its own background):
 *       the offset is the frame's air less {@code Temperature.at} at the point's block, so a reader that adds it to
 *       {@code Temperature.at} there gets the frame's air exactly, as the airflow view shows it;</li>
 *   <li>{@link Source#SAVED}: else what the point's chunk saved for that block, decayed toward the background
 *       ({@link AirflowSaved}); the air value, or the wall value for a block inside a wall;</li>
 *   <li>{@link Source#BACKGROUND}: else none (offset 0).</li>
 * </ol>
 * {@link #offset} is what the readers add to the outside air (M7e): food, ice chests included, thermometers and
 * mounted sensors ({@code MeteodyneTemperature.room}), the player's felt temperature ({@code PersonClimate}) and mobs
 * ({@code ClimateTempModifier}). Live, it computes {@code Temperature.at} at the point's block once (M8b5a); a reader
 * whose own air comes from another block (an entity's feet, its middle a block up) is off by the climate's change
 * across that block. A ship's plot has no tile, so it reads 0 (no ship room heat in airflow mode). A point inside a
 * solid block (a chest, a barrel, an ice chest) reads the mean of the air beside it while its tile runs. Server thread.
 */
public final class AirflowTemperatures {

    public enum Source { LIVE, SAVED, BACKGROUND }

    /** A temperature, C, where it came from, and (LIVE only) the game tick its frame stands for, else -1. */
    public record Reading(double celsius, Source source, long tick) {
    }

    /** An offset from the background, C, where it came from, and (LIVE only) its frame's tick, else -1. */
    public record Offset(double celsius, Source source, long tick) {
        static final Offset NONE = new Offset(0, Source.BACKGROUND, -1);
    }

    /**
     * The air's offset from the outside air at {@code at}, C; NaN when the engine isn't AIRFLOW or this isn't its
     * level (the caller keeps its legacy reading).
     */
    public static double offset(ServerLevel level, Vec3 at) {
        AirflowRegions regions = AirflowRegions.get();
        if (regions == null || regions.level() != level) {
            return Double.NaN;
        }
        return offset(regions, at.x, at.y, at.z).celsius();
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
        long[] tick = {-1};
        double live = live(regions, x, y, z, tick);
        if (!Double.isNaN(live)) {
            return new Reading(live, Source.LIVE, tick[0]);
        }
        Offset o = stored(regions, x, y, z);
        double background = Temperature.at(regions.level(), new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
        return new Reading(background + o.celsius(), o.source(), o.tick());
    }

    static Offset offset(AirflowRegions regions, double x, double y, double z) {
        long[] tick = {-1};
        double live = live(regions, x, y, z, tick);
        if (!Double.isNaN(live)) {
            double air = Temperature.at(regions.level(), new BlockPos(Mth.floor(x), Mth.floor(y), Mth.floor(z)));
            return new Offset(live - air, Source.LIVE, tick[0]);
        }
        return stored(regions, x, y, z);
    }

    /**
     * The running tile's air at (x, y, z), C, its frame's tick in {@code tick[0]}: the frame's temperature there, or
     * inside a solid block the mean of the air cells beside it; NaN where no tile runs or no air is beside it.
     */
    private static double live(AirflowRegions regions, double x, double y, double z, long[] tick) {
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
        AirflowRegions.Region r = regions.regionAt(x, y, z);
        AirflowRuntime.Frame f = r == null || r.slot() == null
                || r.slot().status() != AirflowRuntime.Status.RUNNING ? null : r.slot().frame();
        if (f == null || f.frame() == null) {
            return Double.NaN;
        }
        tick[0] = f.tick();
        int cx = bx - r.x0(), cy = by - r.y0(), cz = bz - r.z0();
        var frame = f.frame();
        if (frame.isAir(cx + 0.5, cy + 0.5, cz + 0.5)) {
            return frame.temperature(x - r.x0(), y - r.y0(), z - r.z0());
        }
        double sum = 0;
        int n = 0;
        for (int[] d : FACES) {
            int ax = cx + d[0], ay = cy + d[1], az = cz + d[2];
            if (frame.isAir(ax + 0.5, ay + 0.5, az + 0.5)) {
                sum += frame.temperature(ax + 0.5, ay + 0.5, az + 0.5);
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** With no tile running there: the saved offset of the point's block, else none. */
    private static Offset stored(AirflowRegions regions, double x, double y, double z) {
        ServerLevel level = regions.level();
        int bx = Mth.floor(x), by = Mth.floor(y), bz = Mth.floor(z);
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
                    return new Offset(offset, Source.SAVED, -1);
                }
            }
        }
        return Offset.NONE;
    }

    private static final int[][] FACES = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    private AirflowTemperatures() {
    }
}
