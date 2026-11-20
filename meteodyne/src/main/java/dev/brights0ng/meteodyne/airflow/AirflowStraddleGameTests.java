// Written by Claude (GreedyFluids M8a: the straddle scenarios); edited by Claude (M8b: B2's cuts, alone and with the
// neighbouring tiles running; b4: decision 10's bars).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GreedyFluids M8a, the straddle scenarios (report-only): what a tile border does to a room it cuts. Each builds a
 * small world as an {@link AirflowScene}, cuts tiles from it as the region manager would ({@link AirflowScene#tile}),
 * and steps them beside a reference tile that holds the whole room:
 * <ul>
 *   <li><b>Alone:</b> each tile as the only live one. Under B2 (M8b) its enclosed cut cells are EXCHANGE and, with no
 *       neighbour running, insulated (Bright, 2026-11-16); before, they were OPEN (every side but the floor) and the
 *       cut leaked as if it were an open window to the background (M8a's numbers).</li>
 *   <li><b>Both live:</b> the tiles of the cut stepped together ({@link AirflowScene.Run#together}), each reading the
 *       other's latest frame across the cut, as the air thread does when both run. The heat each side counts across
 *       the cut is the energy line: out of one, into the other, a step apart.</li>
 *   <li>A tile without the fire sees nothing of it alone; its numbers are the case for or against B3.</li>
 *   <li>Logged as {@code Airflow scenario straddle ...}.</li>
 *   <li><b>Bars (M8b b4; Bright, 2026-11-17, decision 10)</b> on the four warm cuts at 10 min: with every tile of
 *       the cut live, the fire's side 0.8–1.6× the uncut room's same cells, the far side 0.5–1.2×, and the heat out
 *       of the fire's side within 2 % of the heat into the rest; each tile alone, finite values only. The cellar and
 *       the flue are regression checks: finite values only.</li>
 * </ul>
 * World coordinates below; the scenes stand on 8 blocks of stone (air from y 8) unless a scenario says otherwise, the
 * outside air at 5 C.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class AirflowStraddleGameTests {

    private static final int G = 8;
    private static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState FIRE = Blocks.CAMPFIRE.defaultBlockState();
    private static final AirflowScene.Outside STILL = (x, y, z, t) -> 5;

    /** A world with its stone floor up to {@code top} − 1. */
    private static AirflowScene world(int nx, int ny, int nz, int top) {
        return new AirflowScene(nx, ny, nz).fill(0, 0, 0, nx - 1, top - 1, nz - 1, STONE);
    }

    /**
     * Scenario a's house (8 × 4 × 8 inside, stone brick shell, campfire at inside x 3, z 3 on the floor) with its
     * inside from (ix, iy, iz).
     */
    private static void house(AirflowScene world, int ix, int iy, int iz) {
        world.shell(ix - 1, iy - 1, iz - 1, ix + 8, iy + 4, iz + 8, WALL);
        world.set(ix + 3, iy, iz + 3, FIRE);
    }

    // World-coordinate reads on a tile's run: cells outside the tile are left out (NaN if none is inside)

    private static double meanOffset(AirflowScene.Run r, int x0, int y0, int z0, int x1, int y1, int z1) {
        int[] b = clip(r.scene, x0, y0, z0, x1, y1, z1);
        return b == null ? Double.NaN : r.meanOffset(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static double meanTemperature(AirflowScene.Run r, int x0, int y0, int z0, int x1, int y1, int z1) {
        int[] b = clip(r.scene, x0, y0, z0, x1, y1, z1);
        return b == null ? Double.NaN : r.meanTemperature(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static double meanFlow(AirflowScene.Run r, int axis, int x0, int y0, int z0, int x1, int y1, int z1) {
        int[] b = clip(r.scene, x0, y0, z0, x1, y1, z1);
        return b == null ? Double.NaN : r.meanFlow(axis, b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static int[] clip(AirflowScene s, int x0, int y0, int z0, int x1, int y1, int z1) {
        int[] b = {Math.max(x0 - s.ox, 0), Math.max(y0 - s.oy, 0), Math.max(z0 - s.oz, 0),
                Math.min(x1 - s.ox, s.nx - 1), Math.min(y1 - s.oy, s.ny - 1), Math.min(z1 - s.oz, s.nz - 1)};
        return b[0] > b[3] || b[1] > b[4] || b[2] > b[5] ? null : b;
    }

    /**
     * Decision 10's bars on one warm cut at 10 min (after its report is logged): the live fire side over the
     * reference's, the live far side over the reference's, the energy line, and the tiles alone finite.
     */
    private static void bars(GameTestHelper helper, String name, double refFire, double liveFire, double refFar,
            double liveFar, double out, double in, double... alone) {
        List<String> wrong = new ArrayList<>();
        ratio(wrong, "fire side", liveFire, refFire, 0.8, 1.6);
        ratio(wrong, "far side", liveFar, refFar, 0.5, 1.2);
        if (!(Math.abs(out - in) <= 0.02 * Math.abs(out))) {
            wrong.add(String.format(Locale.ROOT, "energy line: %.1f kJ out, %.1f kJ in (bar: within 2 %%)", out / 1e3,
                    in / 1e3));
        }
        for (double v : alone) {
            if (!Double.isFinite(v)) {
                wrong.add("a tile alone read " + v);
            }
        }
        helper.assertTrue(wrong.isEmpty(), "straddle " + name + ": " + String.join("; ", wrong));
    }

    private static void ratio(List<String> wrong, String what, double live, double ref, double lo, double hi) {
        double r = live / ref;
        if (!(r >= lo && r <= hi)) {
            wrong.add(String.format(Locale.ROOT, "%s %.2f against %.2f uncut = %.2f× (bar %.1f–%.1f×)", what, live, ref,
                    r, lo, hi));
        }
    }

    private static void finite(GameTestHelper helper, String name, double... values) {
        for (double v : values) {
            helper.assertTrue(Double.isFinite(v), "straddle " + name + ": a value read " + v);
        }
    }

    /**
     * A house cut by the x = 32 border, inside the left tile from its west wall up to the cut ({@code near} columns of
     * its 8), the fire among them; the right tile holds the rest. The reference tile (x 16–47) holds the whole house.
     * 10 min, the fire burning.
     */
    private static void xCut(GameTestHelper helper, int near) {
        int ix = 32 - near;
        AirflowScene world = world(64, 32, 32, G);
        house(world, ix, G, 12);
        AirflowScene.Run ref = world.tile(16, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run left = world.tile(0, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run right = world.tile(32, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run liveLeft = world.tile(0, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run liveRight = world.tile(32, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("straddle x, " + near + " of 8 columns with the fire");
        double[] at10 = new double[8];
        for (int t : new int[] {120, 600}) {
            ref.until(t);
            left.until(t);
            right.until(t);
            AirflowScene.Run.together(t, liveLeft, liveRight);
            double refNear = meanOffset(ref, ix, G, 12, 31, G + 3, 19);
            double refFar = meanOffset(ref, 32, G, 12, ix + 7, G + 3, 19);
            at10 = new double[] {refNear, meanOffset(liveLeft, ix, G, 12, 31, G + 3, 19), refFar,
                    meanOffset(liveRight, 32, G, 12, ix + 7, G + 3, 19), liveLeft.exchanged(), -liveRight.exchanged(),
                    meanOffset(left, ix, G, 12, 31, G + 3, 19), meanOffset(right, 32, G, 12, ix + 7, G + 3, 19)};
            report.add("t" + t + " whole room (ref)", meanOffset(ref, ix, G, 12, ix + 7, G + 3, 19))
                    .add("fire side (ref)", refNear).add("fire side (cut)", meanOffset(left, ix, G, 12, 31, G + 3, 19))
                    .add("fire side (both live)", meanOffset(liveLeft, ix, G, 12, 31, G + 3, 19))
                    .add("far side (ref)", refFar).add("far side (cut)", meanOffset(right, 32, G, 12, ix + 7, G + 3, 19))
                    .add("far side (both live)", meanOffset(liveRight, 32, G, 12, ix + 7, G + 3, 19))
                    .add("head height at the cut (ref)", ref.offset(31 - 16, G + 1, 15))
                    .add("same (cut)", left.offset(31, G + 1, 15))
                    .add("flow out through the cut m/s", meanFlow(left, 0, 31, G, 12, 31, G + 3, 19))
                    .add("across kJ: out of the fire side", liveLeft.exchanged() / 1e3)
                    .add("into the far side", -liveRight.exchanged() / 1e3);
        }
        report.log(left);
        bars(helper, "x, " + near + " of 8", at10[0], at10[1], at10[2], at10[3], at10[4], at10[5], at10[6], at10[7]);
        helper.succeed();
    }

    /** x cut through the middle: the fire's column is the last before the border. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_straddle_x4")
    public static void houseCutThroughTheMiddle(GameTestHelper helper) {
        xCut(helper, 4);
    }

    /** x cut near the far wall: 6 of the 8 columns, the fire two from the border. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_straddle_x6")
    public static void houseCutNearItsFarWall(GameTestHelper helper) {
        xCut(helper, 6);
    }

    /**
     * A house cut at a band border (y = 16 here, as y 64 and 96 in the world): its inside y 14–17, fire on the floor;
     * the lower tile (y 0–15) holds the bottom two layers and sees an OPEN top, the upper tile (y 16–31) the top two
     * and a closed floor. The reference is the whole 32-high world. 10 min.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_straddle_y")
    public static void houseCutAtABandBorder(GameTestHelper helper) {
        int floor = 14;
        AirflowScene world = world(32, 32, 32, floor);
        house(world, 12, floor, 12);
        AirflowScene.Run ref = world.tile(0, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run lower = world.tile(0, 0, 0, 32, 16, 32).build(STILL, 0, 0);
        AirflowScene.Run upper = world.tile(0, 16, 0, 32, 16, 32).build(STILL, 0, 0);
        AirflowScene.Run liveLower = world.tile(0, 0, 0, 32, 16, 32).build(STILL, 0, 0);
        AirflowScene.Run liveUpper = world.tile(0, 16, 0, 32, 16, 32).build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("straddle y, cut between the second and third layers");
        double[] at10 = new double[8];
        for (int t : new int[] {120, 600}) {
            ref.until(t);
            lower.until(t);
            upper.until(t);
            AirflowScene.Run.together(t, liveLower, liveUpper);
            at10 = new double[] {meanOffset(ref, 12, floor, 12, 19, floor + 1, 19),
                    meanOffset(liveLower, 12, floor, 12, 19, floor + 1, 19),
                    meanOffset(ref, 12, floor + 2, 12, 19, floor + 3, 19),
                    meanOffset(liveUpper, 12, floor + 2, 12, 19, floor + 3, 19), liveLower.exchanged(),
                    -liveUpper.exchanged(), meanOffset(lower, 12, floor, 12, 19, floor + 1, 19),
                    meanOffset(upper, 12, floor + 2, 12, 19, floor + 3, 19)};
            report.add("t" + t + " whole room (ref)", meanOffset(ref, 12, floor, 12, 19, floor + 3, 19))
                    .add("lower two layers (ref)", meanOffset(ref, 12, floor, 12, 19, floor + 1, 19))
                    .add("lower two layers (cut)", meanOffset(lower, 12, floor, 12, 19, floor + 1, 19))
                    .add("lower two layers (both live)", meanOffset(liveLower, 12, floor, 12, 19, floor + 1, 19))
                    .add("upper two layers (ref)", meanOffset(ref, 12, floor + 2, 12, 19, floor + 3, 19))
                    .add("upper two layers (cut)", meanOffset(upper, 12, floor + 2, 12, 19, floor + 3, 19))
                    .add("upper two layers (both live)", meanOffset(liveUpper, 12, floor + 2, 12, 19, floor + 3, 19))
                    .add("flow up through the cut m/s", meanFlow(lower, 1, 12, floor + 1, 12, 19, floor + 1, 19))
                    .add("across kJ: out of the lower tile", liveLower.exchanged() / 1e3)
                    .add("into the upper", -liveUpper.exchanged() / 1e3);
        }
        report.log(lower);
        bars(helper, "y", at10[0], at10[1], at10[2], at10[3], at10[4], at10[5], at10[6], at10[7]);
        helper.succeed();
    }

    /** A house at a corner of four tiles: the fire's quarter (4 × 4 columns) in one tile. 10 min. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_straddle_corner")
    public static void houseAtATileCorner(GameTestHelper helper) {
        AirflowScene world = world(64, 32, 64, G);
        house(world, 28, G, 28);
        AirflowScene.Run ref = world.tile(16, 0, 16, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run quarter = world.tile(0, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run[] live = new AirflowScene.Run[4];
        for (int i = 0; i < 4; i++) {
            live[i] = world.tile(32 * (i % 2), 0, 32 * (i / 2), 32, 32, 32).build(STILL, 0, 0);
        }
        AirflowScene.Report report = new AirflowScene.Report("straddle corner, the fire's quarter");
        double[] at10 = new double[7];
        for (int t : new int[] {120, 600}) {
            ref.until(t);
            quarter.until(t);
            AirflowScene.Run.together(t, live);
            at10 = new double[] {meanOffset(ref, 28, G, 28, 31, G + 3, 31), meanOffset(live[0], 28, G, 28, 31, G + 3, 31),
                    meanOffset(ref, 32, G, 32, 35, G + 3, 35), meanOffset(live[3], 32, G, 32, 35, G + 3, 35),
                    live[0].exchanged(), -(live[1].exchanged() + live[2].exchanged() + live[3].exchanged()),
                    meanOffset(quarter, 28, G, 28, 31, G + 3, 31)};
            report.add("t" + t + " whole room (ref)", meanOffset(ref, 28, G, 28, 35, G + 3, 35))
                    .add("fire quarter (ref)", meanOffset(ref, 28, G, 28, 31, G + 3, 31))
                    .add("fire quarter (cut)", meanOffset(quarter, 28, G, 28, 31, G + 3, 31))
                    .add("fire quarter (all live)", meanOffset(live[0], 28, G, 28, 31, G + 3, 31))
                    .add("opposite quarter (ref)", meanOffset(ref, 32, G, 32, 35, G + 3, 35))
                    .add("opposite quarter (all live)", meanOffset(live[3], 32, G, 32, 35, G + 3, 35))
                    .add("across kJ: out of the fire quarter", live[0].exchanged() / 1e3)
                    .add("into the other three", -(live[1].exchanged() + live[2].exchanged() + live[3].exchanged())
                            / 1e3);
        }
        report.log(quarter);
        bars(helper, "corner", at10[0], at10[1], at10[2], at10[3], at10[4], at10[5], at10[6]);
        helper.succeed();
    }

    /**
     * Scenario e's cellar cut at a band border: 22 blocks of cover over an 8 × 4 × 8 cellar (inside y 14–17, the
     * border at 16) and a 1 × 1 way down, the outside air swinging 16 C over a 20-min day. The lower tile holds the
     * cellar's bottom half, the middle tile its top half and most of the way down; the reference is the whole world.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_straddle_cellar")
    public static void cellarCutAtABandBorder(GameTestHelper helper) {
        int ground = 40;
        AirflowScene world = world(32, 48, 32, ground);
        world.fill(12, 14, 12, 19, 17, 19, AIR).fill(19, 18, 19, 19, ground - 1, 19, AIR);
        AirflowScene.Outside day = AirflowScenarios.day(ground);
        AirflowScene.Run ref = world.build(day, 0, 0).weatherEvery(30);
        AirflowScene.Run lower = world.tile(0, 0, 0, 32, 16, 32).build(day, 0, 0).weatherEvery(30);
        AirflowScene.Run middle = world.tile(0, 16, 0, 32, 16, 32).build(day, 0, 0).weatherEvery(30);
        AirflowScene.Run[] live = {world.tile(0, 0, 0, 32, 16, 32).build(day, 0, 0).weatherEvery(30),
                world.tile(0, 16, 0, 32, 16, 32).build(day, 0, 0).weatherEvery(30),
                world.tile(0, 32, 0, 32, 16, 32).build(day, 0, 0).weatherEvery(30)};
        double[][] range = new double[6][];
        for (int i = 0; i < range.length; i++) {
            range[i] = new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        }
        for (int t = 30; t <= 1200; t += 30) {
            ref.until(t);
            lower.until(t);
            middle.until(t);
            AirflowScene.Run.together(t, live);
            double[] m = {meanTemperature(ref, 12, 14, 12, 19, 15, 19), meanTemperature(lower, 12, 14, 12, 19, 15, 19),
                    meanTemperature(ref, 12, 16, 12, 19, 17, 19), meanTemperature(middle, 12, 16, 12, 19, 17, 19),
                    meanTemperature(live[0], 12, 14, 12, 19, 15, 19), meanTemperature(live[1], 12, 16, 12, 19, 17, 19)};
            for (int i = 0; i < m.length; i++) {
                range[i][0] = Math.min(range[i][0], m[i]);
                range[i][1] = Math.max(range[i][1], m[i]);
            }
        }
        new AirflowScene.Report("straddle cellar, cut between its second and third layers").add("outside swing", 16)
                .add("bottom half swing (ref)", range[0][1] - range[0][0])
                .add("bottom half swing (cut)", range[1][1] - range[1][0])
                .add("bottom half swing (all live)", range[4][1] - range[4][0])
                .add("top half swing (ref)", range[2][1] - range[2][0])
                .add("top half swing (cut)", range[3][1] - range[3][0])
                .add("top half swing (all live)", range[5][1] - range[5][0]).log(lower);
        double[] swings = new double[range.length];
        for (int i = 0; i < range.length; i++) {
            swings[i] = range[i][1] - range[i][0];
        }
        finite(helper, "cellar", swings);
        helper.succeed();
    }

    /**
     * Scenario b's chimney cut at a band border: the house (inside y 8–11) with the campfire under a 1 × 1 shaft from
     * y 12 to 25 and an open doorway; the lower tile (y 0–15) holds the house and the shaft's first four blocks, its
     * top OPEN at 16. The reference is the whole 32-high world. 5 min.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_straddle_flue")
    public static void chimneyCutAtABandBorder(GameTestHelper helper) {
        AirflowScene world = world(32, 32, 32, G);
        world.shell(11, G - 1, 11, 20, G + 4, 20, WALL);
        world.fill(15, G, 20, 15, G + 1, 20, AIR); // the doorway
        world.fill(11, G + 4, 14, 13, G + 17, 16, WALL).fill(12, G + 4, 15, 12, G + 17, 15, AIR);
        world.set(12, G, 15, FIRE);
        AirflowScene.Run ref = world.build(STILL, 0, 0);
        AirflowScene.Run lower = world.tile(0, 0, 0, 32, 16, 32).build(STILL, 0, 0);
        AirflowScene.Run liveLower = world.tile(0, 0, 0, 32, 16, 32).build(STILL, 0, 0);
        AirflowScene.Run liveUpper = world.tile(0, 16, 0, 32, 16, 32).build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("straddle flue, the shaft cut 4 blocks up");
        double[] at5 = new double[0];
        for (int t : new int[] {120, 300}) {
            ref.until(t);
            lower.until(t);
            AirflowScene.Run.together(t, liveLower, liveUpper);
            at5 = new double[] {meanFlow(lower, 1, 12, G + 4, 15, 12, 15, 15), meanFlow(liveLower, 1, 12, G + 4, 15, 12, 15, 15),
                    meanOffset(lower, 12, G, 12, 19, G + 3, 19), meanOffset(liveLower, 12, G, 12, 19, G + 3, 19)};
            report.add("t" + t + " shaft rise below the cut (ref) m/s", meanFlow(ref, 1, 12, G + 4, 15, 12, 15, 15))
                    .add("same (cut) m/s", meanFlow(lower, 1, 12, G + 4, 15, 12, 15, 15))
                    .add("same (both live) m/s", meanFlow(liveLower, 1, 12, G + 4, 15, 12, 15, 15))
                    .add("doorway inflow (ref) m/s", -meanFlow(ref, 2, 15, G, 20, 15, G + 1, 20))
                    .add("same (cut) m/s", -meanFlow(lower, 2, 15, G, 20, 15, G + 1, 20))
                    .add("inside (ref)", meanOffset(ref, 12, G, 12, 19, G + 3, 19))
                    .add("inside (cut)", meanOffset(lower, 12, G, 12, 19, G + 3, 19))
                    .add("inside (both live)", meanOffset(liveLower, 12, G, 12, 19, G + 3, 19));
        }
        report.log(lower);
        finite(helper, "flue", at5);
        helper.succeed();
    }

    private AirflowStraddleGameTests() {
    }
}
