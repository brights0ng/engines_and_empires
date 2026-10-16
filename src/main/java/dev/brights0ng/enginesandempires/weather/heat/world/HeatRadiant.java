package dev.brights0ng.enginesandempires.weather.heat.world;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import dev.brights0ng.enginesandempires.weather.climate.SunLight;
import dev.brights0ng.enginesandempires.weather.climate.SunWarmth;
import dev.brights0ng.paradynelib.heat.air.AirParams;
import dev.brights0ng.paradynelib.heat.air.Radiant;
import dev.brights0ng.paradynelib.ships.ShipCover;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * Radiant heat on a person (weather phase 8f-3, {@code claude/weather-backbone-phase8f.md} section 3; Bright,
 * 2026-10-15): the warmth or chill felt straight from sources in view, on top of the air's.
 *
 * <ul>
 *   <li><b>Sources:</b> every block in the {@code heat_power} table with a radiant share (fires, lava, magma,
 *       furnaces, engines, and glowstone, shroomlight and ice, which act only this way), within {@link #SCAN} blocks
 *       and within its own reach. Natural ones count too (the person is near them by definition); driven ones
 *       (engines) only while running. Lava counts once (the face toward the person), not per open face.</li>
 *   <li><b>Distance:</b> from the middle of the person's body to the nearest point of the source block, 1/d^2, no
 *       closer than {@link AirParams#radiantMin}.</li>
 *   <li><b>Line of sight:</b> the sun-ray block rule (8e-2): a block that stops light stops it, glass and other
 *       light-passing blocks let a quarter through; lava stops it too (a lake radiates from its surface). The
 *       {@link #MAX_RAYS} strongest are traced.</li>
 *   <li><b>Together:</b> {@link Radiant#combine}, warm ones eased toward the hottest contributor's air limit, cold ones
 *       toward {@link #COLD_LIMIT}.</li>
 *   <li><b>Ships</b> (8f-5): someone aboard also sees the ship's own sources, looked for and traced in the ship's grid
 *       around where their body is in it; lines from the world's sources are also stopped by any ship's blocks they
 *       cross (a hull), by the same rule.</li>
 * </ul>
 * Server thread.
 */
public final class HeatRadiant {

    /** Blocks around the person looked through (about lava's radiant reach). */
    static final int SCAN = 10;
    /** At most this many lines of sight are traced, strongest first. */
    static final int MAX_RAYS = 32;
    /** Chill from many cold sources together eases toward this, C. */
    static final double COLD_LIMIT = 20;
    /** Warmth eases toward this when no contributor has an air limit, C. */
    static final double DEFAULT_HOT_LIMIT = 45;
    private static final double STEP = 0.25;

    private record Candidate(int x, int y, int z, double clear, double limit, boolean aboard) {
    }

    /** The radiant warmth (negative: chill), C, on a person whose body's middle is at {@code body}. */
    public static double at(ServerLevel level, Vec3 body) {
        return at(level, body, null);
    }

    /**
     * The radiant warmth, C, on a person whose body's middle is at world point {@code body} and, if they are aboard a
     * ship, at {@code plotBody} in its grid (null if not).
     */
    public static double at(ServerLevel level, Vec3 body, Vec3 plotBody) {
        AirParams p = AirParams.DEFAULT;
        List<Candidate> found = new ArrayList<>();
        candidates(level, body, p, false, found);
        if (plotBody != null) {
            candidates(level, plotBody, p, true, found);
        }
        if (found.isEmpty()) {
            return 0;
        }
        found.sort(Comparator.comparingDouble((Candidate c) -> -Math.abs(c.clear)));
        double hot = 0;
        double cold = 0;
        double hotLimit = 0;
        int n = Math.min(MAX_RAYS, found.size());
        for (int i = 0; i < n; i++) {
            Candidate c = found.get(i);
            double through = c.aboard ? through(level, plotBody, c.x, c.y, c.z, false)
                    : through(level, body, c.x, c.y, c.z, true);
            if (through <= 0) {
                continue;
            }
            double w = c.clear * through;
            if (w > 0) {
                hot += w;
                hotLimit = Math.max(hotLimit, c.limit);
            } else {
                cold += w;
            }
        }
        return Radiant.combine(hot, hotLimit > 0 ? hotLimit : DEFAULT_HOT_LIMIT, cold, COLD_LIMIT);
    }

    /** Whether a block in this state may radiate (for skipping chunk sections). */
    static boolean radiates(BlockState state) {
        HeatPowerData.Variant v = HeatTables.power(state);
        if (v != null && v.power() != 0 && v.radiant() > 0) {
            return true;
        }
        for (HeatPowerData.Variant d : HeatTables.driven(state)) {
            if (d.power() != 0 && d.radiant() > 0) {
                return true;
            }
        }
        return false;
    }

    private static void candidates(ServerLevel level, Vec3 body, AirParams p, boolean aboard, List<Candidate> out) {
        int bx = (int) Math.floor(body.x);
        int by = (int) Math.floor(body.y);
        int bz = (int) Math.floor(body.z);
        int minY = Math.max(level.getMinBuildHeight(), by - SCAN);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, by + SCAN);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = (bx - SCAN) >> 4; cx <= (bx + SCAN) >> 4; cx++) {
            for (int cz = (bz - SCAN) >> 4; cz <= (bz + SCAN) >> 4; cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue;
                }
                int x0 = Math.max(bx - SCAN, cx << 4);
                int x1 = Math.min(bx + SCAN, (cx << 4) + 15);
                int z0 = Math.max(bz - SCAN, cz << 4);
                int z1 = Math.min(bz + SCAN, (cz << 4) + 15);
                for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
                    int index = chunk.getSectionIndexFromSectionY(sy);
                    if (index < 0 || index >= chunk.getSections().length) {
                        continue;
                    }
                    LevelChunkSection section = chunk.getSection(index);
                    if (section.hasOnlyAir() || !section.maybeHas(HeatRadiant::radiates)) {
                        continue;
                    }
                    int y0 = Math.max(minY, sy << 4);
                    int y1 = Math.min(maxY, (sy << 4) + 15);
                    for (int y = y0; y <= y1; y++) {
                        for (int z = z0; z <= z1; z++) {
                            for (int x = x0; x <= x1; x++) {
                                BlockState state = section.getBlockState(x & 15, y & 15, z & 15);
                                if (!radiates(state)) {
                                    continue;
                                }
                                add(level, pos.set(x, y, z), state, body, p, aboard, out);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void add(ServerLevel level, BlockPos pos, BlockState state, Vec3 body, AirParams p,
                            boolean aboard, List<Candidate> out) {
        double d = distanceToBlock(body, pos);
        List<HeatPowerData.Variant> driven = HeatTables.driven(state);
        if (!driven.isEmpty()) {
            for (HeatPowerData.Variant v : driven) {
                if (v.power() == 0 || v.radiant() <= 0) {
                    continue;
                }
                double share = HeatDrivers.share(level, pos, state, v);
                if (share > 0) {
                    candidate(pos, v.scaled(share), d, p, aboard, out);
                }
            }
            return;
        }
        candidate(pos, HeatTables.power(state), d, p, aboard, out);
    }

    private static void candidate(BlockPos pos, HeatPowerData.Variant v, double d, AirParams p, boolean aboard,
                                  List<Candidate> out) {
        if (v == null || d > Radiant.reach(p, v.power(), v.radiant())) {
            return;
        }
        double clear = Radiant.warmth(p, v.power(), v.radiant(), d, 1);
        if (Math.abs(clear) < p.radiantFloor()) {
            return;
        }
        out.add(new Candidate(pos.getX(), pos.getY(), pos.getZ(), clear, Double.isNaN(v.maxOffset()) ? 0 : v.maxOffset(),
                aboard));
    }

    /** From a point to the nearest point of the block at {@code pos}. */
    static double distanceToBlock(Vec3 at, BlockPos pos) {
        double dx = Math.max(0, Math.max(pos.getX() - at.x, at.x - (pos.getX() + 1)));
        double dy = Math.max(0, Math.max(pos.getY() - at.y, at.y - (pos.getY() + 1)));
        double dz = Math.max(0, Math.max(pos.getZ() - at.z, at.z - (pos.getZ() + 1)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * The share of the line from {@code from} to the block's middle that gets through (1, 1/4 or 0), in one grid (the
     * world's or a ship's); {@code ships}: also stopped by ships' blocks it crosses (a line in the world).
     */
    static double through(ServerLevel level, Vec3 from, int tx, int ty, int tz, boolean ships) {
        double ex = tx + 0.5 - from.x;
        double ey = ty + 0.5 - from.y;
        double ez = tz + 0.5 - from.z;
        double len = Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (len < 1e-6) {
            return 1;
        }
        int ox = (int) Math.floor(from.x);
        int oy = (int) Math.floor(from.y);
        int oz = (int) Math.floor(from.z);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int worst = SunLight.PASS;
        long last = Long.MIN_VALUE;
        for (double t = STEP; t < len; t += STEP) {
            double f = t / len;
            int x = (int) Math.floor(from.x + ex * f);
            int y = (int) Math.floor(from.y + ey * f);
            int z = (int) Math.floor(from.z + ez * f);
            if ((x == tx && y == ty && z == tz) || (x == ox && y == oy && z == oz)) {
                continue;
            }
            long key = BlockPos.asLong(x, y, z);
            if (key == last) {
                continue;
            }
            last = key;
            pos.set(x, y, z);
            if (!level.hasChunkAt(pos)) {
                return 0;
            }
            BlockState state = level.getBlockState(pos);
            int c = state.getFluidState().is(FluidTags.LAVA) ? SunLight.STOPS : SunWarmth.classify(state);
            if (c > worst) {
                worst = c;
                if (worst >= SunLight.STOPS) {
                    return 0;
                }
            }
        }
        if (ships && len > 0.6) {
            int hull = ShipCover.rayObstruction(level, from.x, from.y, from.z, ex / len, ey / len, ez / len, 0.3,
                    len - 0.3, HeatRadiant::classify);
            worst = Math.max(worst, hull);
        }
        return SunLight.blockShare(worst);
    }

    /** What a block does to a radiant line: the sun-ray rule, and lava stops it. */
    static int classify(BlockState state) {
        return state.getFluidState().is(FluidTags.LAVA) ? SunLight.STOPS : SunWarmth.classify(state);
    }

    private HeatRadiant() {
    }
}
