// Written by Claude (GreedyFluids M8a: the straddle scenarios).
package dev.brights0ng.meteodyne.airflow;

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
 * and steps each tile alone, as the only live tile, beside a reference tile that holds the whole room. A tile's cut
 * faces are OPEN today (M7: every side but the floor), so a cut room leaks as if the cut were an open window to its
 * background. These scenes size that leak and are M8b's acceptance set (bars set from their numbers, Bright to approve).
 * <ul>
 *   <li>A tile without the fire sees nothing of it, today and under B2 (insulated where the neighbour isn't running,
 *       Bright 2026-11-16); its numbers are the case for or against B3.</li>
 *   <li>Logged as {@code Airflow scenario straddle ...}; nothing is asserted beyond finite values.</li>
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
        AirflowScene.Report report = new AirflowScene.Report("straddle x, " + near + " of 8 columns with the fire");
        for (int t : new int[] {120, 600}) {
            ref.until(t);
            left.until(t);
            right.until(t);
            double refNear = meanOffset(ref, ix, G, 12, 31, G + 3, 19);
            double refFar = meanOffset(ref, 32, G, 12, ix + 7, G + 3, 19);
            report.add("t" + t + " whole room (ref)", meanOffset(ref, ix, G, 12, ix + 7, G + 3, 19))
                    .add("fire side (ref)", refNear).add("fire side (cut)", meanOffset(left, ix, G, 12, 31, G + 3, 19))
                    .add("far side (ref)", refFar).add("far side (cut)", meanOffset(right, 32, G, 12, ix + 7, G + 3, 19))
                    .add("head height at the cut (ref)", ref.offset(31 - 16, G + 1, 15))
                    .add("same (cut)", left.offset(31, G + 1, 15))
                    .add("flow out through the cut m/s", meanFlow(left, 0, 31, G, 12, 31, G + 3, 19));
        }
        report.log(left);
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
        AirflowScene.Report report = new AirflowScene.Report("straddle y, cut between the second and third layers");
        for (int t : new int[] {120, 600}) {
            ref.until(t);
            lower.until(t);
            upper.until(t);
            report.add("t" + t + " whole room (ref)", meanOffset(ref, 12, floor, 12, 19, floor + 3, 19))
                    .add("lower two layers (ref)", meanOffset(ref, 12, floor, 12, 19, floor + 1, 19))
                    .add("lower two layers (cut)", meanOffset(lower, 12, floor, 12, 19, floor + 1, 19))
                    .add("upper two layers (ref)", meanOffset(ref, 12, floor + 2, 12, 19, floor + 3, 19))
                    .add("upper two layers (cut)", meanOffset(upper, 12, floor + 2, 12, 19, floor + 3, 19))
                    .add("flow up through the cut m/s", meanFlow(lower, 1, 12, floor + 1, 12, 19, floor + 1, 19));
        }
        report.log(lower);
        helper.succeed();
    }

    /** A house at a corner of four tiles: the fire's quarter (4 × 4 columns) in one tile. 10 min. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_straddle_corner")
    public static void houseAtATileCorner(GameTestHelper helper) {
        AirflowScene world = world(64, 32, 64, G);
        house(world, 28, G, 28);
        AirflowScene.Run ref = world.tile(16, 0, 16, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Run quarter = world.tile(0, 0, 0, 32, 32, 32).build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("straddle corner, the fire's quarter");
        for (int t : new int[] {120, 600}) {
            ref.until(t);
            quarter.until(t);
            report.add("t" + t + " whole room (ref)", meanOffset(ref, 28, G, 28, 35, G + 3, 35))
                    .add("fire quarter (ref)", meanOffset(ref, 28, G, 28, 31, G + 3, 31))
                    .add("fire quarter (cut)", meanOffset(quarter, 28, G, 28, 31, G + 3, 31));
        }
        report.log(quarter);
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
        double[][] range = new double[4][];
        for (int i = 0; i < range.length; i++) {
            range[i] = new double[] {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        }
        for (int t = 30; t <= 1200; t += 30) {
            ref.until(t);
            lower.until(t);
            middle.until(t);
            double[] m = {meanTemperature(ref, 12, 14, 12, 19, 15, 19), meanTemperature(lower, 12, 14, 12, 19, 15, 19),
                    meanTemperature(ref, 12, 16, 12, 19, 17, 19), meanTemperature(middle, 12, 16, 12, 19, 17, 19)};
            for (int i = 0; i < m.length; i++) {
                range[i][0] = Math.min(range[i][0], m[i]);
                range[i][1] = Math.max(range[i][1], m[i]);
            }
        }
        new AirflowScene.Report("straddle cellar, cut between its second and third layers").add("outside swing", 16)
                .add("bottom half swing (ref)", range[0][1] - range[0][0])
                .add("bottom half swing (cut)", range[1][1] - range[1][0])
                .add("top half swing (ref)", range[2][1] - range[2][0])
                .add("top half swing (cut)", range[3][1] - range[3][0]).log(lower);
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
        AirflowScene.Report report = new AirflowScene.Report("straddle flue, the shaft cut 4 blocks up");
        for (int t : new int[] {120, 300}) {
            ref.until(t);
            lower.until(t);
            report.add("t" + t + " shaft rise below the cut (ref) m/s", meanFlow(ref, 1, 12, G + 4, 15, 12, 15, 15))
                    .add("same (cut) m/s", meanFlow(lower, 1, 12, G + 4, 15, 12, 15, 15))
                    .add("doorway inflow (ref) m/s", -meanFlow(ref, 2, 15, G, 20, 15, G + 1, 20))
                    .add("same (cut) m/s", -meanFlow(lower, 2, 15, G, 20, 15, G + 1, 20))
                    .add("inside (ref)", meanOffset(ref, 12, G, 12, 19, G + 3, 19))
                    .add("inside (cut)", meanOffset(lower, 12, G, 12, 19, G + 3, 19));
        }
        report.log(lower);
        helper.succeed();
    }

    private AirflowStraddleGameTests() {
    }
}
