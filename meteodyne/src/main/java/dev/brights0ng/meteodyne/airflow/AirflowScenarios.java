// Written by Claude (GreedyFluids M7f: the acceptance scenarios); edited by Claude (M7f: loose regression bars; M8a:
// the two bars the realistic surface film moved out of, re-centred; M8b4: a block broken beside lava, with its bar
// under F1; M8b5a: a tall cave, whole and cut by a tile border; M8e: lava under each fixed-wall surface).
package dev.brights0ng.meteodyne.airflow;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.greedyfluids.grid.CellGrid3D.FixedSurface;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.climate.Underground;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GreedyFluids M7f, the acceptance scenarios: Bright's must-work list (2026-10-28) run by direct stepping
 * ({@link AirflowScene}; Bright, 2026-11-15). Each builds a 32-block window from real block states, read and powered by
 * the game's own rules, under an outside air it chooses, and steps the model faster than real time.
 * <p><b>Measured first</b> (Bright, 2026-11-15): the numbers are logged as {@code Airflow scenario <name>: ...}, with
 * shape checks (warmer than outside, warmer up than down, downwind beats upwind) and, since Bright's option 3 the same
 * day, loose regression bars ({@link #bar}): about 0.5 to 1.5 times the value measured with kappa 0.05, real stone
 * walls and keep-fine following sources. They guard against a change that quietly weakens the physics; they are not
 * design targets, and they hold for the default {@code [heat]} config only.
 * <p>M8a (Bright, 2026-11-16: a realistic surface film, 0.13 m²·K/W): walls and doors now hold heat better, so every
 * value moved; the two that left their bars (the house's share kept 10 min after the fire, body heat) are re-centred at
 * 0.5 to 1.5 times their new values, and the rest still sit inside theirs.
 * <p>The scenes stand on 8 blocks of stone (the air starts at y 8); the outside air is 5 C unless a scenario says
 * otherwise. Every step runs inside the test's first tick, so the game test clock doesn't matter.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class AirflowScenarios {

    private static final int N = 32;
    /** The first air layer above the stone floor. */
    private static final int G = 8;
    private static final double OUT = 5;
    private static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState FIRE = Blocks.CAMPFIRE.defaultBlockState();
    private static final AirflowScene.Outside STILL = (x, y, z, t) -> OUT;

    /** A window with its stone floor. */
    private static AirflowScene ground(int top) {
        return new AirflowScene(N, N, N).fill(0, 0, 0, N - 1, top - 1, N - 1, STONE);
    }

    /** The 10 × 10 house (8 × 4 × 8 inside, x and z 12–19, y 8–11), stone brick walls and roof. */
    private static AirflowScene house() {
        return ground(G).shell(11, G - 1, 11, 20, G + 4, 20, WALL);
    }

    /** The house's inside, for means: {x0, y0, z0, x1, y1, z1}. */
    private static final int[] INSIDE = {12, G, 12, 19, G + 3, 19};

    private static double inside(AirflowScene.Run run) {
        return run.meanOffset(INSIDE[0], INSIDE[1], INSIDE[2], INSIDE[3], INSIDE[4], INSIDE[5]);
    }

    /** The corner farthest from the middle, at head height (1.5 above the floor). */
    private static double corner(AirflowScene.Run run) {
        return run.offset(12, G + 1, 12);
    }

    private static BlockState door(boolean upper, boolean open) {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, open);
    }

    /**
     * A loose regression bar (M7f, Bright's option 3): value must lie in [lo, hi]. measured is the value the bar was set
     * around (2026-11-15), for the message.
     */
    private static void bar(GameTestHelper helper, String what, double value, double measured, double lo, double hi) {
        helper.assertTrue(value >= lo && value <= hi, what + ": " + value + " is outside its bar [" + lo + ", " + hi
                + "] (measured " + measured + " when the bar was set)");
    }

    /**
     * a. A campfire warms a closed room; c. an open door lets cold in and shutting it stops that; e. the room holds its
     * heat after the fire goes out. One house with a shut door in its south wall: the fire burns 10 min, the door is
     * open 2 min and shut again 2 min, then the fire goes out for 10 min.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_house")
    public static void houseWarmsLetsColdInAtTheDoorAndHoldsItsHeat(GameTestHelper helper) {
        AirflowScene scene = house();
        scene.set(15, G, 20, door(false, false)).set(15, G + 1, 20, door(true, false));
        scene.set(15, G, 15, FIRE);
        AirflowScene.Run run = scene.build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("a room warms (10 kW campfire, 8x4x8 stone brick house)");
        List<double[]> corners = new ArrayList<>();
        for (int t = 30; t <= 600; t += 30) {
            run.until(t);
            corners.add(new double[] {t, corner(run)});
            if (t == 60 || t == 120 || t == 300 || t == 600) {
                report.add("t" + t + " inside", inside(run)).add("corner", corner(run));
            }
        }
        double warm = inside(run), warmCorner = corner(run);
        double ninety = Double.NaN;
        for (double[] c : corners) {
            if (c[1] >= 0.9 * warmCorner) {
                ninety = c[0];
                break;
            }
        }
        report.add("corner reaches 90% of its 10-min value at s", ninety).log(run);
        helper.assertTrue(warmCorner > 0 && warm > 0, "the room warms: inside " + warm + ", corner " + warmCorner);
        bar(helper, "a. inside at 10 min, C", warm, 8.61, 5, 13);
        bar(helper, "a. corner at 10 min, C", warmCorner, 7.83, 4.5, 12);

        AirflowScene.Report door = new AirflowScene.Report("c open door (south wall, 2 min open, 2 min shut)");
        run.change(15, G, 20, door(false, true)).change(15, G + 1, 20, door(true, true));
        run.until(660);
        door.add("open 60 s inside", inside(run)).add("by the door", run.offset(15, G + 1, 19));
        run.until(720);
        double open = inside(run);
        door.add("open 120 s inside", open).add("by the door", run.offset(15, G + 1, 19))
                .add("loss while open C/min", (warm - open) / 2);
        run.change(15, G, 20, door(false, false)).change(15, G + 1, 20, door(true, false));
        run.until(840);
        double shut = inside(run);
        door.add("shut 120 s inside", shut).add("by the door", run.offset(15, G + 1, 19))
                .add("regained C/min", (shut - open) / 2).log(run);
        helper.assertTrue(open < warm, "an open door lets cold in: " + open + " against " + warm);
        helper.assertTrue(shut > open, "shutting it stops that: " + shut + " against " + open);
        bar(helper, "c. loss while the door is open, C/min", (warm - open) / 2, 0.303, 0.15, 0.6);
        bar(helper, "c. regained after it shuts, C/min", (shut - open) / 2, 0.334, 0.15, 0.6);

        AirflowScene.Report hold = new AirflowScene.Report("e holds heat (fire out, door shut)");
        run.power(15, G, 15, FIRE.setValue(BlockStateProperties.LIT, false));
        int[] outAt = {900, 1140, 1440};
        double[] kept = new double[outAt.length];
        for (int i = 0; i < outAt.length; i++) {
            int t = outAt[i];
            run.until(t);
            double now = inside(run);
            kept[i] = now / shut;
            hold.add("out " + (t - 840) + " s inside", now).add("kept", kept[i]);
        }
        hold.log(run);
        double cold = inside(run);
        helper.assertTrue(cold > 0 && cold < shut, "the room cools but stays warmer than outside: " + cold);
        bar(helper, "e. share kept 1 min after the fire goes out", kept[0], 0.825, 0.6, 0.95);
        bar(helper, "e. share kept after 5 min", kept[1], 0.387, 0.2, 0.6);
        bar(helper, "e. share kept after 10 min", kept[2], 0.307, 0.15, 0.46);
        helper.succeed();
    }

    /**
     * b. Hot air rises: a two-storey house (two 8 × 3 × 8 rooms) with a 2 × 1 hole in the floor between them in one
     * corner and the campfire in the opposite corner downstairs, 10 min.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_storeys")
    public static void hotAirRisesToTheUpperFloor(GameTestHelper helper) {
        AirflowScene scene = ground(G).shell(11, G - 1, 11, 20, G + 7, 20, WALL);
        scene.fill(12, G + 3, 12, 19, G + 3, 19, WALL).fill(18, G + 3, 18, 19, G + 3, 18, Blocks.AIR.defaultBlockState());
        scene.set(13, G, 13, FIRE);
        AirflowScene.Run run = scene.build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("b hot air rises (two storeys, fire downstairs)");
        double bottom = 0, top = 0, upper = 0;
        for (int t : new int[] {300, 600}) {
            run.until(t);
            // the quarter of the lower room away from the fire and the hole
            bottom = run.meanOffset(16, G, 12, 19, G, 15);
            top = run.meanOffset(16, G + 2, 12, 19, G + 2, 15);
            double lower = run.meanOffset(12, G, 12, 19, G + 2, 19);
            upper = run.meanOffset(12, G + 4, 12, 19, G + 6, 19);
            int[] boxes = run.boxesIn(12, G, 12, 19, G + 2, 19);
            report.add("t" + t + " lower", lower).add("upper", upper).add("lower floor layer", bottom)
                    .add("lower ceiling layer", top).add("lower room boxes", boxes[0]).add("tallest", boxes[1])
                    .add("widest", boxes[2]);
        }
        report.log(run);
        helper.assertTrue(top > bottom, "warmer under the ceiling than at the floor: " + top + " against " + bottom);
        helper.assertTrue(upper > 0, "the upper floor warms from below: " + upper);
        bar(helper, "b. lower room, ceiling layer above floor layer at 10 min, C", top - bottom, 2.11, 1, 4);
        bar(helper, "b. upper floor at 10 min, C", upper, 1.06, 0.5, 2.5);
        helper.succeed();
    }

    /**
     * b. Hot air rises, by layer (barred at 10 min: the top layer above the bottom one, and the rise over the hall's
     * fire): each layer of the two-storey house's lower room and of a tall hall
     * (8 × 8 × 8 inside, campfire in the middle of the floor), with the mean air speed, at 2 and 10 min.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_layers")
    public static void layersOfAHeatedRoom(GameTestHelper helper) {
        for (int which = 0; which < 2; which++) {
            boolean hall = which == 1;
            AirflowScene scene;
            int top;
            if (hall) {
                scene = ground(G).shell(11, G - 1, 11, 20, G + 8, 20, WALL);
                scene.set(15, G, 15, FIRE);
                top = G + 7;
            } else {
                scene = ground(G).shell(11, G - 1, 11, 20, G + 7, 20, WALL);
                scene.fill(12, G + 3, 12, 19, G + 3, 19, WALL).fill(18, G + 3, 18, 19, G + 3, 18, Blocks.AIR.defaultBlockState());
                scene.set(13, G, 13, FIRE);
                top = G + 2;
            }
            AirflowScene.Run run = scene.build(STILL, 0, 0);
            for (int t : new int[] {120, 600}) {
                run.until(t);
                AirflowScene.Report report = new AirflowScene.Report((hall ? "b layers, tall hall" : "b layers, lower room")
                        + " t" + t);
                for (int y = G; y <= top; y++) {
                    report.add("y+" + (y - G), run.meanOffset(12, y, 12, 19, y, 19));
                }
                int fx = hall ? 15 : 13;
                double rise = run.meanFlow(1, fx, G + 1, fx, fx, top, fx);
                report.add("mean speed m/s", run.meanSpeed(12, G, 12, 19, top, 19))
                        .add("mean rise over the fire m/s", rise)
                        .add("boxes", run.boxesIn(12, G, 12, 19, top, 19)[0])
                        .add("tallest", run.boxesIn(12, G, 12, 19, top, 19)[1]).log(run);
                if (t == 600) {
                    double layering = run.meanOffset(12, top, 12, 19, top, 19) - run.meanOffset(12, G, 12, 19, G, 19);
                    if (hall) {
                        bar(helper, "b. tall hall, top layer above bottom layer at 10 min, C", layering, 1.68, 0.8, 3);
                        bar(helper, "b. tall hall, rise over the fire at 10 min, m/s", rise, 0.94, 0.5, 1.5);
                    } else {
                        bar(helper, "b. lower room, top layer above bottom layer at 10 min, C", layering, 2.41, 1.2, 4);
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * b. A chimney draws: the house with the campfire against its west wall under a 1 × 1 shaft 14 blocks tall, open at
     * the top, and an open doorway in the south wall to let air in; 5 min.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_chimney")
    public static void chimneyDraws(GameTestHelper helper) {
        AirflowScene scene = house();
        scene.fill(15, G, 20, 15, G + 1, 20, Blocks.AIR.defaultBlockState()); // the doorway
        scene.fill(11, G + 4, 14, 13, G + 17, 16, WALL).fill(12, G + 4, 15, 12, G + 17, 15, Blocks.AIR.defaultBlockState());
        scene.set(12, G, 15, FIRE);
        AirflowScene.Run run = scene.build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("b chimney draws (1x1 shaft, open doorway)");
        double rise = 0, inflow = 0;
        for (int t : new int[] {120, 300}) {
            run.until(t);
            rise = run.meanFlow(1, 12, G + 7, 15, 12, G + 14, 15);
            inflow = -run.meanFlow(2, 15, G, 20, 15, G + 1, 20);
            report.add("t" + t + " shaft rise m/s", rise).add("shaft", run.meanOffset(12, G + 7, 15, 12, G + 14, 15))
                    .add("doorway inflow m/s", inflow).add("inside", inside(run));
        }
        report.log(run);
        helper.assertTrue(rise > 0, "air goes up the chimney: " + rise + " m/s");
        bar(helper, "b. chimney shaft rise at 5 min, m/s", rise, 1.53, 0.8, 2.5);
        bar(helper, "b. doorway inflow at 5 min, m/s", inflow, 0.75, 0.4, 1.2);
        helper.succeed();
    }

    /** d. Wind carries heat downwind: a campfire on open ground, calm and in 5 m/s wind toward +x, 2 min each. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_wind")
    public static void windCarriesHeatDownwind(GameTestHelper helper) {
        double[] windy = new double[4], calm = new double[4];
        double calmAbove = 0;
        for (double wind : new double[] {0, 5}) {
            AirflowScene scene = ground(G).set(16, G, 16, FIRE);
            AirflowScene.Run run = scene.build(STILL, wind, 0);
            AirflowScene.Report report = new AirflowScene.Report("d wind " + (int) wind + " m/s (open campfire)");
            double[] sum = new double[4];
            int n = 0;
            for (int t = 60; t <= 120; t += 10) {
                run.until(t);
                sum[0] += run.offset(18, G + 1, 16);
                sum[1] += run.offset(14, G + 1, 16);
                sum[2] += run.offset(20, G + 1, 16);
                sum[3] += run.offset(12, G + 1, 16);
                n++;
            }
            for (int i = 0; i < 4; i++) {
                sum[i] /= n;
            }
            report.add("2 downwind", sum[0]).add("2 upwind", sum[1]).add("4 downwind", sum[2]).add("4 upwind", sum[3])
                    .add("over the fire", run.offset(16, G + 2, 16)).log(run);
            if (wind > 0) {
                windy = sum;
            } else {
                calm = sum;
                calmAbove = run.offset(16, G + 2, 16);
            }
        }
        helper.assertTrue(windy[2] > windy[3], "4 blocks downwind is warmer than 4 upwind: " + windy[2] + " against "
                + windy[3]);
        bar(helper, "d. calm, 2 blocks out on +x, C", calm[0], 0.246, 0.12, 0.5);
        bar(helper, "d. calm, 2 blocks out on -x, C", calm[1], 0.231, 0.12, 0.5);
        bar(helper, "d. calm, over the fire, C", calmAbove, 6.51, 3.5, 10);
        bar(helper, "d. 5 m/s, 4 blocks downwind, C", windy[2], 0.073, 0.035, 0.15);
        bar(helper, "d. 5 m/s, 4 blocks upwind, C", windy[3], 0, -0.02, 0.02);
        helper.succeed();
    }

    /** Outside air swinging over one day (20 min) about 10 C, and the underground rule below the ground's top. */
    static AirflowScene.Outside day(int groundTop) {
        Underground.Settings s = WeatherConfig.underground();
        return (x, y, z, t) -> {
            double outside = 10 + 8 * Math.sin(2 * Math.PI * t / 1200);
            if (y >= groundTop) {
                return outside;
            }
            // world y 40 + y: above the band, so the rock is the surface's annual mean (10 C)
            return Underground.read(outside, 10, groundTop - y, 0, 40 + y, -64, 0, null, s).temperature();
        };
    }

    /** e. A cellar stays cool: the outside air swings 16 C over a day; a cellar 22 blocks down against a house. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_cellar")
    public static void cellarSwingsLessThanAHouse(GameTestHelper helper) {
        double[] swing = new double[2];
        for (int which = 0; which < 2; which++) {
            boolean cellar = which == 1;
            int top = cellar ? 28 : G;
            AirflowScene scene = cellar ? ground(top) : house();
            int[] box = cellar ? new int[] {12, 3, 12, 19, 5, 19} : INSIDE;
            if (cellar) {
                scene.fill(12, 3, 12, 19, 5, 19, Blocks.AIR.defaultBlockState())
                        .fill(19, 6, 19, 19, top - 1, 19, Blocks.AIR.defaultBlockState()); // the way down
            }
            AirflowScene.Outside outside = day(top);
            AirflowScene.Run run = scene.build(outside, 0, 0).weatherEvery(30);
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (int t = 30; t <= 1200; t += 30) {
                run.until(t);
                double m = run.meanTemperature(box[0], box[1], box[2], box[3], box[4], box[5]);
                min = Math.min(min, m);
                max = Math.max(max, m);
            }
            swing[which] = max - min;
            new AirflowScene.Report(cellar ? "e cellar (22 blocks of cover, a 1x1 way down)" : "e house (same day)")
                    .add("outside swing", 16).add("inside swing", swing[which]).add("inside min", min)
                    .add("inside max", max).log(run);
        }
        helper.assertTrue(swing[1] < swing[0], "the cellar swings less than the house: " + swing[1] + " against "
                + swing[0]);
        bar(helper, "e. house swing over a 16 C day, C", swing[0], 4.35, 2.5, 7);
        bar(helper, "e. cellar swing over the same day, C", swing[1], 0.11, 0, 0.3);
        helper.succeed();
    }

    /** h. Machines and lava heat a room: the house with a lit furnace, then with a 2 × 2 lava pool in its floor. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_machines")
    public static void furnaceAndLavaHeatARoom(GameTestHelper helper) {
        double[] warm = new double[2];
        for (int which = 0; which < 2; which++) {
            AirflowScene scene = house();
            String name;
            if (which == 0) {
                scene.set(15, G, 15, Blocks.FURNACE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
                name = "h lit furnace (5 kW)";
            } else {
                scene.fill(15, G - 1, 15, 16, G - 1, 16, Blocks.LAVA.defaultBlockState());
                name = "h lava pool (2x2 in the floor)";
            }
            AirflowScene.Run run = scene.build(STILL, 0, 0);
            AirflowScene.Report report = new AirflowScene.Report(name);
            for (int t : new int[] {60, 300, 600}) {
                run.until(t);
                report.add("t" + t + " inside", inside(run)).add("corner", corner(run));
            }
            report.log(run);
            warm[which] = inside(run);
        }
        helper.assertTrue(warm[0] > 0, "a lit furnace warms the room: " + warm[0]);
        helper.assertTrue(warm[1] > 0, "lava warms the room: " + warm[1]);
        bar(helper, "h. lit furnace, inside at 10 min, C", warm[0], 4.29, 2.5, 6.5);
        bar(helper, "h. lava pool, inside at 10 min, C", warm[1], 2.13, 1.2, 3.2);
        helper.succeed();
    }

    /** i. Body heat: one player (500 W) in a closed 3 × 2 × 3 stone brick room, 10 min. */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_body")
    public static void bodyHeatWarmsASmallRoom(GameTestHelper helper) {
        AirflowScene scene = ground(G).shell(14, G - 1, 14, 18, G + 2, 18, WALL);
        AirflowScene.Run run = scene.build(STILL, 0, 0);
        run.post(new AirflowRegionModel.Bodies(new double[] {16.5, G + 0.9, 16.5, AirflowSources.PLAYER_WATTS}));
        AirflowScene.Report report = new AirflowScene.Report("i body heat (500 W, 3x2x3 room)");
        double first = 0, last = 0;
        for (int t : new int[] {60, 300, 600}) {
            run.until(t);
            last = run.meanOffset(15, G, 15, 17, G + 1, 17);
            if (t == 60) {
                first = last;
            }
            report.add("t" + t + " inside", last);
        }
        report.log(run);
        helper.assertTrue(last > 0 && last > first, "body heat warms the room: " + first + " then " + last);
        bar(helper, "i. body heat, inside at 10 min, C", last, 4.57, 2.3, 6.9);
        helper.succeed();
    }

    /**
     * j (M8b4, Bright's report 2026-11-17: breaking a block by a lava stream sends the cells around it wild in the
     * view). In the house, a 2-block lava stream on the floor (x 15–16, z 15) ends against a stone block (x 17); air
     * lies past it (x 18). After 2 min the stone block is broken; then a stone block away from the lava (x 13, z 18,
     * standing on the floor) as a control. Logs the broken cell's offset and the lowest and highest offset among the
     * air cells within 2 blocks of it, 1 step, 5 s and 30 s after.
     * <p><b>Bar (Bright, 2026-11-17: F1, a broken block's heat leaves with it):</b> one step after each break, the
     * broken cell reads within the range of the air within 2 blocks before the break, ±0.5 C. Before F1 the cell by
     * the lava read 175 C over the background against 4–11 around it, and the control 0.75 C against 6–12.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_break_lava")
    public static void breakingABlockBesideLava(GameTestHelper helper) {
        AirflowScene scene = house();
        scene.fill(15, G, 15, 16, G, 15, Blocks.LAVA.defaultBlockState()).set(17, G, 15, STONE).set(13, G, 18, STONE);
        AirflowScene.Run run = scene.build(STILL, 0, 0);
        run.until(120);
        AirflowScene.Report report = new AirflowScene.Report("j breaking a block beside lava");
        List<String> breaks = new ArrayList<>();
        boolean ok = true;
        for (int[] at : new int[][] {{17, G, 15}, {13, G, 18}}) {
            String which = at[0] == 17 ? "by lava" : "control";
            double[] before = around(run, at, 2);
            report.add(which + " before: nearest air", around(run, at, 1)[1]).add("lowest within 2", before[0])
                    .add("highest within 2", before[1]);
            run.change(at[0], at[1], at[2], Blocks.AIR.defaultBlockState());
            double t0 = run.time();
            for (double after : new double[] {run.model.dt(), 5, 30}) {
                run.until(t0 + after);
                double[] r = around(run, at, 2);
                finiteCheck(helper, which, r);
                double cell = run.offset(at[0], at[1], at[2]);
                report.add(which + " +" + after + " s: broken cell", cell).add("lowest within 2", r[0])
                        .add("highest within 2", r[1]);
                if (after == run.model.dt()) {
                    breaks.add(which + ": " + String.format(java.util.Locale.ROOT, "%.2f in [%.2f, %.2f]", cell,
                            before[0], before[1]));
                    ok &= cell >= before[0] - 0.5 && cell <= before[1] + 0.5;
                }
            }
        }
        report.log(run);
        helper.assertTrue(ok, "j. the broken cell opens within the air around it (F1): " + String.join("; ", breaks));
        helper.succeed();
    }

    /** The lowest and highest air offset within {@code reach} blocks of {@code at} (the cell itself included), C. */
    private static double[] around(AirflowScene.Run run, int[] at, int reach) {
        var f = run.frame();
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int x = at[0] - reach; x <= at[0] + reach; x++) {
            for (int y = at[1] - reach; y <= at[1] + reach; y++) {
                for (int z = at[2] - reach; z <= at[2] + reach; z++) {
                    double o = AirflowScene.Run.offset(f, x, y, z);
                    if (!Double.isNaN(o)) {
                        lo = Math.min(lo, o);
                        hi = Math.max(hi, o);
                    }
                }
            }
        }
        return new double[] {lo, hi};
    }

    private static void finiteCheck(GameTestHelper helper, String which, double[] r) {
        helper.assertTrue(Double.isFinite(r[0]) && Double.isFinite(r[1]), "j " + which + ": " + r[0] + ", " + r[1]);
    }

    /** k's scene: 32 × 48 × 32, its world y the scene's, the ground's surface at y 48. */
    private static final int K_TOP = 48;
    /** k's year's mean at the ground, C. */
    private static final double K_ANNUAL = 10;

    /**
     * k's background, as the manager's lattice gives it: {@link Underground#read} at the scene's y as world y, under
     * the cover up to {@link #K_TOP}, in the band (deeper is warmer), outside air {@link #OUT}, no texture or cave rule.
     */
    private static final AirflowScene.Outside K_BACKGROUND = (x, y, z, t) -> Underground.read(OUT, K_ANNUAL,
            K_TOP - y, 0, y, -64, 0, null, Underground.Settings.DEFAULT).temperature();

    /**
     * k (M8b5a, Bright's report 2026-11-17: in a tall cave with lava to one side, the view showed warm air at the top and
     * cool at the floor, but the thermometer read about 10 C at the top and 12 C at the floor). A cave 16 × 30 × 16
     * (x 4–19, y 4–33, z 8–23) in solid stone up to y 48, its background the underground's (9 C warmer at the floor
     * than at the top), heated by a lava face 4 wide and 3 high in its west wall (x 3, z 14–17, y 4–6), by one
     * campfire on its floor by that wall (x 5, y 4, z 15), or by nothing. Each runs as one region, then cut into tiles
     * 16 high stepped together, as a tile border cuts a cave taller than half a tile (tiles are 32 high). 20 min; logs
     * the air temperature (what a reading gives) in the cave's top and bottom 3 layers (y 29–31,
     * y 4–6) and either side of the cut at y 16 (y 13–15, y 16–18), the top's offset over the background, the heated
     * side's against the far side's at mid height, the speed in the cave's lower half, and the cost.
     * <p><b>Report only</b> (Bright, 2026-11-17): no bars beyond finite values. As one region the top ends warmer; cut,
     * it reads colder than the floor, as Bright saw in game: b5b's merging is the fix. (Two buoyancy references, P1
     * stable and P4 the outside air, were tried here and changed nothing measurable; dropped, Bright 2026-11-17.)
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_cave")
    public static void aTallCaveBesideLava(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        for (String heat : new String[] {"lava face", "campfire", "no source"}) {
            for (boolean cut : new boolean[] {false, true}) {
                cave(heat, cut, failures);
            }
        }
        helper.assertTrue(failures.isEmpty(), "k. finite readings: " + String.join("; ", failures));
        helper.succeed();
    }

    /** One run of k: the cave heated by {@code heat}, as one region or cut into tiles 16 high. */
    private static void cave(String heat, boolean cut, List<String> failures) {
        AirflowScene scene = new AirflowScene(N, K_TOP, N).fill(0, 0, 0, N - 1, K_TOP - 1, N - 1, STONE)
                .fill(4, 4, 8, 19, 33, 23, Blocks.AIR.defaultBlockState());
        if (heat.equals("lava face")) {
            scene.fill(3, 4, 14, 3, 6, 17, Blocks.LAVA.defaultBlockState());
        } else if (heat.equals("campfire")) {
            scene.set(5, 4, 15, FIRE);
        }
        AirflowScene.Run[] runs;
        if (cut) {
            runs = new AirflowScene.Run[K_TOP / 16];
            for (int j = 0; j < runs.length; j++) {
                runs[j] = scene.tile(0, 16 * j, 0, N, 16, N).build(K_BACKGROUND, 0, 0);
            }
        } else {
            runs = new AirflowScene.Run[] {scene.build(K_BACKGROUND, 0, 0)};
        }
        AirflowScene.Report report = new AirflowScene.Report("k tall cave, " + heat + ", "
                + (cut ? "cut into tiles 16 high" : "one region"));
        report.add("t0 top", caveMean(runs, false, 4, 29, 8, 19, 31, 23))
                .add("bottom", caveMean(runs, false, 4, 4, 8, 19, 6, 23));
        for (int t : new int[] {120, 600, 1200}) {
            if (cut) {
                AirflowScene.Run.together(t, runs);
            } else {
                runs[0].until(t);
            }
            double top = caveMean(runs, false, 4, 29, 8, 19, 31, 23);
            double bottom = caveMean(runs, false, 4, 4, 8, 19, 6, 23);
            report.add("t" + t + " top", top).add("bottom", bottom).add("top - bottom", top - bottom)
                    .add("below y16", caveMean(runs, false, 4, 13, 8, 19, 15, 23))
                    .add("above y16", caveMean(runs, false, 4, 16, 8, 19, 18, 23))
                    .add("offset top", caveMean(runs, true, 4, 29, 8, 19, 31, 23))
                    .add("heated side mid", caveMean(runs, false, 4, 20, 8, 6, 23, 23))
                    .add("far side mid", caveMean(runs, false, 17, 20, 8, 19, 23, 23))
                    .add("speed low half", runs[0].meanSpeed(4, 4, 8, 19, 15, 23));
            if (!Double.isFinite(top) || !Double.isFinite(bottom)) {
                failures.add(heat + (cut ? " cut" : "") + " t" + t + ": " + top + ", " + bottom);
            }
        }
        report.log(runs[0]);
    }

    private static final BlockState LAVA = Blocks.LAVA.defaultBlockState();

    /** One of l's fixed-wall settings: a surface and a keep-fine radius around lava facing air, cells. */
    private record LWalls(FixedSurface surface, int keepFine) {
        @Override
        public String toString() {
            return surface + (keepFine > 0 ? " K" + keepFine : "");
        }
    }

    /** l's settings: RAY as before; FILM and CONVECTION, with and without keep-fine cubes. */
    private static final LWalls[] L_WALLS = {new LWalls(FixedSurface.RAY, 0), new LWalls(FixedSurface.FILM, 0),
            new LWalls(FixedSurface.CONVECTION, 0), new LWalls(FixedSurface.FILM, 3),
            new LWalls(FixedSurface.CONVECTION, 1), new LWalls(FixedSurface.CONVECTION, 3)};

    /**
     * l (GreedyFluids M8e, Bright 2026-11-20: lava should heat its surroundings "pretty hot"; only the film (FILM) and
     * free convection off the hot face (CONVECTION) are tried, after the fix that couples a fixed wall through its own
     * face rather than half the stone behind it). Each case runs under RAY (as before), FILM and CONVECTION:
     * <ul>
     *   <li>h's house with its 2 × 2 lava pool in the floor, still outside air at 5 C, 10 min: the inside's offset, the
     *       air over the pool, the hottest inside air;</li>
     *   <li>k's tall cave with its lava face (4 × 3 in the west wall), one region, 20 min: top, bottom, the whole
     *       cave's mean and its hottest air;</li>
     *   <li>a closed cave 11 × 6 × 11 (x 10–20, y 10–15, z 10–20) under k's cover and background, a 3 × 3 lava pool in
     *       its floor (y 9), 30 min: the cave's mean, its top layer, the air over the pool, the hottest air;</li>
     *   <li>a 3 × 3 lava pool in open ground (x 14–16, z 14–16, the ground's top layer), outside air 5 C, still and
     *       in a 3 m/s wind along +x, 10 min: the air just over the pool and 3 blocks up, 3–5 blocks downwind, and
     *       the hottest.</li>
     * </ul>
     * Each runs as built so far, then (M8e, the lava-heated house and cave stayed one box: no plume) with keep-fine cubes
     * around the lava faces facing air: FILM with radius 3, CONVECTION with radius 1 and 3.
     * <b>Report only</b>: finite values, and FILM and CONVECTION at least as warm over the pool as RAY.
     */
    @GameTest(template = GameTestStructures.EMPTY, batch = "airflow_scenario_lava_surface")
    public static void lavaUnderEachSurface(GameTestHelper helper) {
        List<String> failures = new ArrayList<>();
        double[] overPool = new double[L_WALLS.length];
        for (int i = 0; i < L_WALLS.length; i++) {
            LWalls w = L_WALLS[i];
            overPool[i] = lavaPoolInTheHouse(w, failures);
            lavaFaceInTheTallCave(w, failures);
            closedLavaCave(w, failures);
            openLavaPool(w, 0, failures);
            openLavaPool(w, 3, failures);
        }
        helper.assertTrue(failures.isEmpty(), "l. finite readings: " + String.join("; ", failures));
        boolean warmer = true;
        for (int i = 1; i < L_WALLS.length; i++) {
            warmer &= overPool[i] >= overPool[0];
        }
        helper.assertTrue(warmer, "l. FILM and CONVECTION warm the air over the house's pool at least as much as RAY: "
                + java.util.Arrays.toString(overPool));
        helper.succeed();
    }

    /** l's house with h's lava pool; returns the air over the pool at 10 min, C. */
    private static double lavaPoolInTheHouse(LWalls w, List<String> failures) {
        AirflowScene scene = house().fill(15, G - 1, 15, 16, G - 1, 16, LAVA).fixedWalls(w.surface(), w.keepFine());
        AirflowScene.Run run = scene.build(STILL, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("l lava pool in the house, " + w);
        double over = Double.NaN;
        for (int t : new int[] {60, 300, 600}) {
            run.until(t);
            over = run.meanTemperature(15, G, 15, 16, G, 16);
            double max = run.maxTemperature(INSIDE[0], INSIDE[1], INSIDE[2], INSIDE[3], INSIDE[4], INSIDE[5]);
            report.add("t" + t + " inside", inside(run)).add("corner", corner(run)).add("over pool", over)
                    .add("hottest", max);
            lFinite(failures, "house " + w + " t" + t, inside(run), over, max);
        }
        report.log(run);
        return over;
    }

    /** l's tall cave with k's lava face, as one region. */
    private static void lavaFaceInTheTallCave(LWalls w, List<String> failures) {
        AirflowScene scene = new AirflowScene(N, K_TOP, N).fill(0, 0, 0, N - 1, K_TOP - 1, N - 1, STONE)
                .fill(4, 4, 8, 19, 33, 23, Blocks.AIR.defaultBlockState()).fill(3, 4, 14, 3, 6, 17, LAVA)
                .fixedWalls(w.surface(), w.keepFine());
        AirflowScene.Run run = scene.build(K_BACKGROUND, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("l lava face in the tall cave, " + w);
        for (int t : new int[] {120, 600, 1200}) {
            run.until(t);
            double top = run.meanTemperature(4, 29, 8, 19, 31, 23), bottom = run.meanTemperature(4, 4, 8, 19, 6, 23);
            double mean = run.meanTemperature(4, 4, 8, 19, 33, 23), max = run.maxTemperature(4, 4, 8, 19, 33, 23);
            report.add("t" + t + " top", top).add("bottom", bottom).add("mean", mean)
                    .add("by the face", run.meanTemperature(4, 4, 14, 4, 6, 17)).add("hottest", max);
            lFinite(failures, "tall cave " + w + " t" + t, top, bottom, max);
        }
        report.log(run);
    }

    /** l's closed cave with a 3 × 3 lava pool in its floor. */
    private static void closedLavaCave(LWalls w, List<String> failures) {
        AirflowScene scene = new AirflowScene(N, K_TOP, N).fill(0, 0, 0, N - 1, K_TOP - 1, N - 1, STONE)
                .fill(10, 10, 10, 20, 15, 20, Blocks.AIR.defaultBlockState()).fill(14, 9, 14, 16, 9, 16, LAVA)
                .fixedWalls(w.surface(), w.keepFine());
        AirflowScene.Run run = scene.build(K_BACKGROUND, 0, 0);
        AirflowScene.Report report = new AirflowScene.Report("l closed cave with a 3x3 lava pool, " + w);
        report.add("t0 mean", run.meanTemperature(10, 10, 10, 20, 15, 20));
        for (int t : new int[] {60, 600, 1800}) {
            run.until(t);
            double mean = run.meanTemperature(10, 10, 10, 20, 15, 20), max = run.maxTemperature(10, 10, 10, 20, 15, 20);
            report.add("t" + t + " mean", mean).add("top layer", run.meanTemperature(10, 15, 10, 20, 15, 20))
                    .add("floor layer", run.meanTemperature(10, 10, 10, 20, 10, 20))
                    .add("over pool", run.meanTemperature(14, 10, 14, 16, 10, 16)).add("hottest", max);
            lFinite(failures, "closed cave " + w + " t" + t, mean, max, mean);
        }
        report.log(run);
    }

    /** l's lava pool in open ground, in still air or a wind of {@code wind} m/s along +x. */
    private static void openLavaPool(LWalls w, double wind, List<String> failures) {
        AirflowScene scene = ground(G).fill(14, G - 1, 14, 16, G - 1, 16, LAVA).fixedWalls(w.surface(), w.keepFine());
        AirflowScene.Run run = scene.build(STILL, wind, 0);
        AirflowScene.Report report = new AirflowScene.Report("l open lava pool, wind " + (int) wind + " m/s, "
                + w);
        for (int t : new int[] {60, 300, 600}) {
            run.until(t);
            double over = run.meanOffset(14, G, 14, 16, G, 16), max = run.maxTemperature(10, G, 10, 22, G + 8, 22);
            report.add("t" + t + " over pool", over).add("3 up", run.meanOffset(14, G + 3, 14, 16, G + 3, 16))
                    .add("downwind", run.meanOffset(19, G, 14, 21, G + 1, 16)).add("hottest", max);
            lFinite(failures, "open pool " + w + " wind " + wind + " t" + t, over, max, over);
        }
        report.log(run);
    }

    private static void lFinite(List<String> failures, String what, double a, double b, double c) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || !Double.isFinite(c)) {
            failures.add(what + ": " + a + ", " + b + ", " + c);
        }
    }

    /**
     * The mean air temperature (or offset) in a box of the scene's cells, inclusive, read from the run whose tile holds
     * it whole (k: the boxes never straddle a cut).
     */
    private static double caveMean(AirflowScene.Run[] runs, boolean offset, int x0, int y0, int z0, int x1, int y1,
                                   int z1) {
        for (AirflowScene.Run r : runs) {
            AirflowScene s = r.scene;
            if (y0 >= s.oy && y1 < s.oy + s.ny) {
                return offset ? r.meanOffset(x0, y0 - s.oy, z0, x1, y1 - s.oy, z1)
                        : r.meanTemperature(x0, y0 - s.oy, z0, x1, y1 - s.oy, z1);
            }
        }
        return Double.NaN;
    }

    private AirflowScenarios() {
    }
}
