// Written by Claude (GreedyFluids M7f f2: the soak runs).
package dev.brights0ng.meteodyne.airflow;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import dev.brights0ng.meteodyne.Meteodyne;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * GreedyFluids M7f f2, the soak runs (Bright, 2026-11-15: both, opt-in, never in the ordinary checks). Either property
 * limits the bare Meteodyne game-test run to these tests (namespace {@code meteodyne_airflow_soak}):
 * <pre>
 *   gradlew :meteodyne:runGameTestServer -Pmeteodyne.airflowSoakDays=30      the model soak
 *   gradlew :meteodyne:runGameTestServer -Pmeteodyne.airflowSoakMinutes=30   the runtime soak
 * </pre>
 * Reports go to {@code meteodyne/build/reports/airflow-soak/}.
 * <ul>
 *   <li><b>Model soak</b> ({@link #model}): a two-storey house ({@link AirflowScene}) stepped through many 20-minute
 *       days on the test thread, 20 simulated seconds a tick: the outside air swings 16 C a day with wind by day; each
 *       day the fire burns at night, the door opens and shuts, a window opens by day, a body sits inside, blocks are
 *       placed and broken and a furnace runs in the evening; the region is saved and rebuilt from its save twice a
 *       day. Checks: values stay finite; temperatures stay within bounds; a rebuild keeps each floor's mean within
 *       0.5 C and the heat of the air and of the wall stores each within 5 MJ; and the last day against the second:
 *       the end-of-day temperature within 0.5 C, boxes at most 1.25 times, step cost at most 1.5 times, heap after a
 *       collection at most 64 MB more. Reported: the heat the day's events add at once. Block edits rebuild the wall
 *       stores, whose capacity is capped per ray, so an edit can add or take away tens of MJ (M7f: about +32 MJ a
 *       day in this schedule, which warms the house about 0.1 C a day and grows its boxes; an open library
 *       question).</li>
 *   <li><b>Runtime soak</b> ({@link #runtime}): the real region manager, air thread and chunk data over a 3 × 2 field
 *       of tiles, in real time. Three walkers (4 blocks a second) go round six huts, staying 40 s in each, opening and
 *       shutting its door, lighting and putting out its campfire, placing and breaking a block; one jumps two huts
 *       ahead every 5 min; the time of day moves on every 2 min; chunks no walker needs for 2 min are let go and
 *       loaded again before a walker comes back. Recorded with {@link AirflowRecorder}. Checks: the smoke bar
 *       (server ≤ 0.2 ms mean and ≤ 1 ms p99 a tick, air thread ≤ 50 %, frame age p95 ≤ 2 s, no failures or
 *       desyncs); backlog drops only within 15 s after a jump; tiles reopen from their saves and no saved section is
 *       lost; each region's share of a core in the last 5 min at most 1.5 times (plus 0.5 points) that of minutes
 *       2–7; heap after a collection at most 64 MB more than at minute 2.</li>
 * </ul>
 * The walkers are fake anchors, not players, so no body heat there; the world is the throwaway game-test world.
 */
@GameTestHolder(Meteodyne.MODID)
public final class AirflowSoaks {

    static final String TEMPLATE = "meteodyne_airflow_soak:gametest/empty";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @GameTestGenerator
    public static List<TestFunction> soaks() {
        List<TestFunction> out = new ArrayList<>();
        int days = Integer.getInteger("meteodyne.airflowSoakDays", 0);
        if (days > 0) {
            out.add(new TestFunction("airflow_soak_model", "airflowsoak.model", TEMPLATE,
                    days * TICKS_PER_DAY + 400, 0, true, h -> model(h, days)));
        }
        int minutes = Integer.getInteger("meteodyne.airflowSoakMinutes", 0);
        if (minutes > 0) {
            out.add(new TestFunction("airflow_soak_runtime", "airflowsoak.runtime", TEMPLATE,
                    minutes * 1200 + 2400, 0, true, h -> runtime(h, minutes)));
        }
        return out;
    }

    private static Path output() throws IOException {
        Path dir = Path.of(System.getProperty("meteodyne.airflowSoakOutput", "build/reports/airflow-soak"));
        Files.createDirectories(dir);
        return dir;
    }

    private static void write(Path file, List<String> lines) {
        try {
            Files.writeString(file, String.join("\n", lines) + "\n");
        } catch (IOException e) {
            Meteodyne.LOGGER.warn("Airflow soak: could not write {}", file, e);
        }
    }

    private static double usedHeapAfterGc() {
        System.gc();
        Runtime jvm = Runtime.getRuntime();
        return (jvm.totalMemory() - jvm.freeMemory()) / 1048576.0;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ------------------------------------------------------------------------------------------------ model soak

    static final int DAY = 1200, CHUNK = 20, TICKS_PER_DAY = DAY / CHUNK;
    private static final int G = 8;
    private static final BlockState WALL = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState FIRE = Blocks.CAMPFIRE.defaultBlockState();
    private static final BlockState FURNACE = Blocks.FURNACE.defaultBlockState();
    /** The lower and upper rooms: {x0, y0, z0, x1, y1, z1}. */
    private static final int[] LOWER = {12, G, 12, 19, G + 2, 19}, UPPER = {12, G + 4, 12, 19, G + 6, 19};

    private static BlockState sceneDoor(boolean upper, boolean open) {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER)
                .setValue(DoorBlock.OPEN, open);
    }

    private static double mean(AirflowScene.Run run, int[] b) {
        return run.meanTemperature(b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    /** One day's numbers in the model soak. */
    private record Day(int day, double lowerMin, double lowerMax, double lowerEnd, double upperEnd, int boxesMax,
                       double msPerStep, double heapMb, double worstRebuild, double rebuildAirMj,
                       double rebuildWallMj, double eventAirMj, double eventWallMj) {
    }

    /** The region's heat content now: air, then wall stores, J (from the solver's own zero). */
    private static double[] energy(AirflowScene.Run run) {
        var solver = run.model.region().mesh().solver();
        double walls = solver.wallStore().energy();
        return new double[] {solver.totalEnergy() - walls, walls};
    }

    static void model(GameTestHelper helper, int days) {
        AirflowScene scene = new AirflowScene(32, 32, 32).fill(0, 0, 0, 31, G - 1, 31, Blocks.STONE.defaultBlockState())
                .shell(11, G - 1, 11, 20, G + 7, 20, WALL);
        scene.fill(12, G + 3, 12, 19, G + 3, 19, WALL).fill(18, G + 3, 18, 19, G + 3, 18, AIR);
        scene.set(15, G, 20, sceneDoor(false, false)).set(15, G + 1, 20, sceneDoor(true, false));
        scene.set(13, G, 13, FIRE.setValue(BlockStateProperties.LIT, false));
        scene.set(16, G + 4, 13, FURNACE.setValue(BlockStateProperties.LIT, false));
        AirflowScene.Outside outside = AirflowScenarios.day(G);
        AirflowScene.Run run = scene.build(outside, 0, 0).weatherEvery(30);

        List<Day> done = new ArrayList<>();
        double[] dayMin = {Double.POSITIVE_INFINITY}, dayMax = {Double.NEGATIVE_INFINITY}, worstRebuild = {0};
        double[] rebuildEnergy = new double[2];
        double[] eventEnergy = new double[2];
        int[] boxesMax = {0};
        long[] dayNanos = {0}, daySteps = {0};
        boolean[] over = {false};
        helper.onEachTick(() -> {
            if (over[0]) {
                return;
            }
            try {
                int t = (int) Math.round(run.time());
                int p = t % DAY, day = t / DAY;
                if (p == 0 && t > 0) {
                    done.add(new Day(day, dayMin[0], dayMax[0], mean(run, LOWER), mean(run, UPPER), boxesMax[0],
                            (run.stepNanos() - dayNanos[0]) / 1e6 / Math.max(1, run.steps() - daySteps[0]),
                            usedHeapAfterGc(), worstRebuild[0], rebuildEnergy[0] / 1e6, rebuildEnergy[1] / 1e6,
                            eventEnergy[0] / 1e6, eventEnergy[1] / 1e6));
                    Day d = done.get(done.size() - 1);
                    Meteodyne.LOGGER.info("Airflow soak (model): day {}: lower {} to {}, end {} / upper {}; {} boxes at "
                                    + "most; {} ms/step; heap {} MB; worst rebuild change {} C, {} MJ in the air and {} MJ "
                                    + "in the walls; the day's events added {} MJ to the air and {} MJ to the walls",
                            d.day(), f(d.lowerMin()),
                            f(d.lowerMax()), f(d.lowerEnd()), f(d.upperEnd()), d.boxesMax(), f(d.msPerStep()),
                            f(d.heapMb()), f(d.worstRebuild()), f(d.rebuildAirMj()), f(d.rebuildWallMj()),
                            f(d.eventAirMj()), f(d.eventWallMj()));
                    dayMin[0] = Double.POSITIVE_INFINITY;
                    dayMax[0] = Double.NEGATIVE_INFINITY;
                    boxesMax[0] = 0;
                    worstRebuild[0] = 0;
                    rebuildEnergy[0] = 0;
                    rebuildEnergy[1] = 0;
                    eventEnergy[0] = 0;
                    eventEnergy[1] = 0;
                    dayNanos[0] = run.stepNanos();
                    daySteps[0] = run.steps();
                    if (day >= days) {
                        over[0] = true;
                        finishModel(helper, done, days);
                        return;
                    }
                }
                if (p == 300 || p == 1060) { // the tile retires and reopens at once, from its save
                    run.weatherNow(); // as the manager keeps it: the save and the rebuild see the same outside air
                    double lower = mean(run, LOWER), upper = mean(run, UPPER);
                    double[] e0 = energy(run);
                    run.restart(20L * t);
                    double[] e1 = energy(run);
                    rebuildEnergy[0] = Math.max(rebuildEnergy[0], Math.abs(e1[0] - e0[0]));
                    rebuildEnergy[1] = Math.max(rebuildEnergy[1], Math.abs(e1[1] - e0[1]));
                    worstRebuild[0] = Math.max(worstRebuild[0],
                            Math.max(Math.abs(mean(run, LOWER) - lower), Math.abs(mean(run, UPPER) - upper)));
                }
                double[] ev0 = energy(run); // what the day's events themselves add (edits rebuild the wall stores)
                switch (p) {
                    case 0 -> run.light(13, G, 13, FIRE.setValue(BlockStateProperties.LIT, false))
                            .change(20, G + 5, 15, AIR);
                    case 160 -> run.change(15, G, 20, sceneDoor(false, true)).change(15, G + 1, 20, sceneDoor(true, true));
                    case 220 -> run.change(15, G, 20, sceneDoor(false, false)).change(15, G + 1, 20, sceneDoor(true, false));
                    case 300 -> run.post(new AirflowRegionModel.Bodies(
                            new double[] {15.5, G + 0.9, 15.5, AirflowSources.PLAYER_WATTS}));
                    case 460 -> run.change(16, G, 17, WALL).change(17, G, 17, WALL).change(18, G, 17, WALL);
                    case 600 -> run.change(20, G + 5, 15, WALL).light(16, G + 4, 13,
                            FURNACE.setValue(BlockStateProperties.LIT, true));
                    case 760 -> run.change(16, G, 17, AIR).change(17, G, 17, AIR).change(18, G, 17, AIR);
                    case 900 -> run.light(13, G, 13, FIRE.setValue(BlockStateProperties.LIT, true))
                            .light(16, G + 4, 13, FURNACE.setValue(BlockStateProperties.LIT, false))
                            .post(new AirflowRegionModel.Bodies(new double[0]));
                    default -> {
                    }
                }
                double[] ev1 = energy(run);
                eventEnergy[0] += ev1[0] - ev0[0];
                eventEnergy[1] += ev1[1] - ev0[1];
                if (p % 60 == 0) {
                    run.wind(4 * Math.max(0, Math.sin(2 * Math.PI * p / DAY)), 0);
                }
                for (int s = 0; s < CHUNK; s += 10) {
                    run.until(t + s + 10);
                    double lower = mean(run, LOWER);
                    dayMin[0] = Math.min(dayMin[0], lower);
                    dayMax[0] = Math.max(dayMax[0], lower);
                    boxesMax[0] = Math.max(boxesMax[0], run.boxes());
                    if (!(lower > -10 && lower < 60) || !(mean(run, UPPER) > -10 && mean(run, UPPER) < 60)) {
                        throw new IllegalStateException("a floor's mean left [-10, 60] C at " + run.time() + " s: lower "
                                + lower + ", upper " + mean(run, UPPER));
                    }
                }
            } catch (GameTestAssertException e) {
                throw e;
            } catch (RuntimeException e) {
                over[0] = true;
                helper.fail("model soak: " + e);
            }
        });
    }

    private static void finishModel(GameTestHelper helper, List<Day> done, int days) {
        List<String> out = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        out.add("# Airflow model soak (GreedyFluids M7f f2)");
        out.add("");
        out.add(days + " days of 20 min; a two-storey stone brick house; outside 10 ± 8 C, wind up to 4 m/s by day; "
                + "saved and rebuilt at 300 and 1060 s each day.");
        out.add("");
        out.add("| Day | Lower floor min / max C | End of day, lower / upper C | Boxes at most | ms per step | Heap MB "
                + "| Worst rebuild change: C / air MJ / walls MJ | Events added: air / walls MJ |");
        out.add("|---|---|---|---|---|---|---|---|");
        for (Day d : done) {
            out.add("| " + d.day() + " | " + f(d.lowerMin()) + " / " + f(d.lowerMax()) + " | " + f(d.lowerEnd()) + " / "
                    + f(d.upperEnd()) + " | " + d.boxesMax() + " | " + f(d.msPerStep()) + " | " + f(d.heapMb()) + " | "
                    + f(d.worstRebuild()) + " / " + f(d.rebuildAirMj()) + " / " + f(d.rebuildWallMj()) + " | "
                    + f(d.eventAirMj()) + " / " + f(d.eventWallMj()) + " |");
            if (d.worstRebuild() > 0.5) {
                failures.add("day " + d.day() + ": a rebuild moved a floor's mean by " + f(d.worstRebuild()) + " C");
            }
            if (d.rebuildAirMj() > 5 || d.rebuildWallMj() > 5) {
                failures.add("day " + d.day() + ": a rebuild moved " + f(d.rebuildAirMj()) + " MJ in the air and "
                        + f(d.rebuildWallMj()) + " MJ in the walls");
            }
        }
        if (done.size() >= 3) {
            Day second = done.get(1), last = done.get(done.size() - 1), before = done.get(done.size() - 2);
            if (Math.abs(last.lowerEnd() - before.lowerEnd()) > 0.5) {
                failures.add("still drifting: the lower floor ends the last day at " + f(last.lowerEnd()) + " C against "
                        + f(before.lowerEnd()) + " the day before");
            }
            if (last.boxesMax() > 1.25 * second.boxesMax() + 50) {
                failures.add("boxes grow: " + last.boxesMax() + " on the last day against " + second.boxesMax());
            }
            if (last.msPerStep() > 1.5 * second.msPerStep() + 0.1) {
                failures.add("steps slow down: " + f(last.msPerStep()) + " ms against " + f(second.msPerStep()));
            }
            if (last.heapMb() > second.heapMb() + 64) {
                failures.add("heap grows: " + f(last.heapMb()) + " MB against " + f(second.heapMb()));
            }
        } else {
            failures.add("fewer than 3 days ran");
        }
        out.add("");
        out.add(failures.isEmpty() ? "**PASS**" : "**FAIL:** " + String.join("; ", failures));
        try {
            write(output().resolve("model-" + LocalDateTime.now().format(STAMP) + ".md"), out);
        } catch (IOException e) {
            Meteodyne.LOGGER.warn("Airflow soak: no report folder", e);
        }
        Meteodyne.LOGGER.info("Airflow soak (model):\n{}", String.join("\n", out));
        if (failures.isEmpty()) {
            helper.succeed();
        } else {
            helper.fail("model soak: " + String.join("; ", failures));
        }
    }

    // ---------------------------------------------------------------------------------------------- runtime soak

    private static final int DWELL = 40, WALK = 16, LEG = DWELL + WALK, JUMP_SECONDS = 300;
    private static final int[][] ROUTE = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {1, 1}, {0, 1}};

    static void runtime(GameTestHelper helper, int minutes) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        AirflowRegions regions = new AirflowRegions(level);
        regions.alignForTests(origin);
        int[] t0 = regions.tileOf(origin.getX(), origin.getY(), origin.getZ());
        int floor = origin.getY() + 1;
        // the six huts, one in each tile, away from the test structure (16–31 blocks into the first tile)
        BlockPos[] huts = new BlockPos[ROUTE.length];
        for (int i = 0; i < ROUTE.length; i++) {
            huts[i] = new BlockPos(t0[0] + ROUTE[i][0] * AirflowRegions.WX + 40, floor,
                    t0[2] + ROUTE[i][1] * AirflowRegions.WZ + 40);
        }
        // the tiles' chunks: tile index → chunk keys
        Map<Integer, List<long[]>> chunks = new HashMap<>();
        for (int i = 0; i < ROUTE.length; i++) {
            List<long[]> list = new ArrayList<>();
            int x0 = t0[0] + ROUTE[i][0] * AirflowRegions.WX, z0 = t0[2] + ROUTE[i][1] * AirflowRegions.WZ;
            for (int cx = x0 >> 4; cx < (x0 + AirflowRegions.WX) >> 4; cx++) {
                for (int cz = z0 >> 4; cz < (z0 + AirflowRegions.WZ) >> 4; cz++) {
                    list.add(new long[] {cx, cz});
                }
            }
            chunks.put(i, list);
        }
        Set<Long> forced = new HashSet<>();
        long keep = net.minecraft.world.level.ChunkPos.asLong(origin.getX() >> 4, origin.getZ() >> 4);
        for (List<long[]> list : chunks.values()) {
            for (long[] c : list) {
                level.setChunkForced((int) c[0], (int) c[1], true);
                forced.add(net.minecraft.world.level.ChunkPos.asLong((int) c[0], (int) c[1]));
            }
        }
        for (BlockPos h : huts) {
            hut(level, h);
        }
        boolean opened = AirflowSections.get(level) == null;
        long start = level.getGameTime();
        regions.anchors(l -> {
            double tau = (l.getGameTime() - start) * AirflowRuntime.TICK_SECONDS;
            List<Vec3> out = new ArrayList<>(3);
            for (int a = 0; a < 3; a++) {
                out.add(walker(huts, a, tau));
            }
            return out;
        });
        AirflowRegions.useForTests(regions);
        regions.start();
        Path dir;
        try {
            dir = output().resolve("runtime-" + LocalDateTime.now().format(STAMP));
        } catch (IOException e) {
            helper.fail("runtime soak: no report folder: " + e);
            return;
        }
        AirflowRecorder rec = regions.record(dir);
        int[][] lastStep = new int[3][2]; // per walker: leg, dwell second last acted on
        for (int[] l : lastStep) {
            l[0] = -1;
        }
        long[] idleSince = new long[ROUTE.length];
        double[] heapStart = {Double.NaN};
        List<Integer> jumps = new ArrayList<>();
        boolean[] over = {false};
        Runnable cleanup = () -> {
            AirflowRegions.useForTests(null);
            regions.stop();
            for (long c : forced) {
                level.setChunkForced(net.minecraft.world.level.ChunkPos.getX(c), net.minecraft.world.level.ChunkPos.getZ(c),
                        false);
            }
            if (opened) {
                AirflowSections.close(level);
            }
        };
        helper.onEachTick(() -> {
            if (over[0]) {
                return;
            }
            try {
                long ticks = level.getGameTime() - start;
                double tau = ticks * AirflowRuntime.TICK_SECONDS;
                if (ticks > 0 && ticks % (20L * JUMP_SECONDS) == 0) {
                    jumps.add(rec.seconds());
                    rec.mark("jump (walker 0 skips two huts)");
                }
                if (ticks == 20L * 120) {
                    heapStart[0] = usedHeapAfterGc();
                    rec.mark("heap " + f(heapStart[0]) + " MB");
                }
                if (ticks > 0 && ticks % (20L * 120) == 0) {
                    level.setDayTime(level.getDayTime() + 2400);
                }
                for (int a = 0; a < 3; a++) {
                    act(level, huts, a, tau, lastStep[a]);
                }
                if (ticks % 200 == 0) {
                    chunks(level, huts, chunks, forced, keep, tau, ticks, idleSince);
                }
                if (tau >= minutes * 60.0) {
                    over[0] = true;
                    finishRuntime(helper, regions, rec, dir, jumps, heapStart[0], cleanup);
                }
            } catch (GameTestAssertException e) {
                throw e;
            } catch (RuntimeException e) {
                over[0] = true;
                cleanup.run();
                helper.fail("runtime soak: " + e);
            }
        });
    }

    /** A 7 × 7 cobblestone hut with a door on its south side and a campfire inside; (x, y, z) is its middle. */
    private static void hut(ServerLevel level, BlockPos mid) {
        BlockState wall = Blocks.COBBLESTONE.defaultBlockState();
        for (int dy = -1; dy <= 4; dy++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    boolean shell = dy == -1 || dy == 4 || Math.abs(dx) == 3 || Math.abs(dz) == 3;
                    level.setBlock(mid.offset(dx, dy, dz), shell ? wall : Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        BlockPos door = mid.offset(0, 0, 3);
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,
                Direction.SOUTH);
        level.setBlock(door, lower, 3);
        level.setBlock(door.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
        level.setBlock(mid.offset(-2, 0, -2), Blocks.CAMPFIRE.defaultBlockState()
                .setValue(BlockStateProperties.LIT, false), 3);
    }

    /** Where walker {@code a} is {@code tau} seconds in: 40 s in a hut, then 16 s to the next (4 blocks a second). */
    private static Vec3 walker(BlockPos[] huts, int a, double tau) {
        int[] where = leg(a, tau);
        BlockPos from = huts[where[0]], to = huts[(where[0] + 1) % huts.length];
        double s = where[1] + (tau - Math.floor(tau));
        if (s < DWELL) {
            return Vec3.atBottomCenterOf(from);
        }
        double u = (s - DWELL) / WALK;
        return new Vec3(from.getX() + 0.5 + u * (to.getX() - from.getX()), from.getY(),
                from.getZ() + 0.5 + u * (to.getZ() - from.getZ()));
    }

    /** Walker {@code a}'s hut and whole second into its leg; walker 0 jumps two huts ahead every 5 min. */
    private static int[] leg(int a, double tau) {
        int offset = 2 * a + (a == 0 ? 2 * (int) (tau / JUMP_SECONDS) : 0);
        int whole = (int) Math.floor(tau);
        return new int[] {Math.floorMod(whole / LEG + offset, ROUTE.length), whole % LEG};
    }

    /** What a walker does in a hut, by the second of its stay. */
    private static void act(ServerLevel level, BlockPos[] huts, int a, double tau, int[] last) {
        int[] where = leg(a, tau);
        if (where[0] == last[0] && where[1] == last[1]) {
            return;
        }
        last[0] = where[0];
        last[1] = where[1];
        BlockPos mid = huts[where[0]];
        BlockPos door = mid.offset(0, 0, 3), fire = mid.offset(-2, 0, -2), block = mid.offset(2, 0, -2);
        switch (where[1]) {
            case 2, 6 -> {
                boolean open = where[1] == 2;
                for (BlockPos p : new BlockPos[] {door, door.above()}) {
                    BlockState s = level.getBlockState(p);
                    if (s.hasProperty(DoorBlock.OPEN)) {
                        level.setBlock(p, s.setValue(DoorBlock.OPEN, open), 10);
                    }
                }
            }
            case 10, 34 -> {
                BlockState s = level.getBlockState(fire);
                if (s.hasProperty(BlockStateProperties.LIT)) {
                    level.setBlock(fire, s.setValue(BlockStateProperties.LIT, where[1] == 10), 3);
                }
            }
            case 14 -> level.setBlock(block, Blocks.COBBLESTONE.defaultBlockState(), 3);
            case 30 -> level.setBlock(block, Blocks.AIR.defaultBlockState(), 3);
            default -> {
            }
        }
    }

    /**
     * Lets go of the chunks of tiles no walker has needed for 2 min, and holds them again before one comes near
     * (within 48 blocks of the tile). The chunk holding the test structure stays.
     */
    private static void chunks(ServerLevel level, BlockPos[] huts, Map<Integer, List<long[]>> chunks, Set<Long> forced,
                               long keep, double tau, long ticks, long[] idleSince) {
        for (int i = 0; i < huts.length; i++) {
            boolean needed = false;
            for (int a = 0; a < 3 && !needed; a++) {
                for (double ahead = 0; ahead <= 20 && !needed; ahead += 5) {
                    Vec3 w = walker(huts, a, tau + ahead);
                    needed = Math.abs(w.x - (huts[i].getX() + 0.5)) <= 32 + 48
                            && Math.abs(w.z - (huts[i].getZ() + 0.5)) <= 32 + 48;
                }
            }
            if (needed) {
                idleSince[i] = ticks;
            }
            boolean hold = needed || ticks - idleSince[i] < 20L * 120;
            for (long[] c : chunks.get(i)) {
                long key = net.minecraft.world.level.ChunkPos.asLong((int) c[0], (int) c[1]);
                if (key == keep) {
                    continue;
                }
                if (hold && forced.add(key)) {
                    level.setChunkForced((int) c[0], (int) c[1], true);
                } else if (!hold && forced.remove(key)) {
                    level.setChunkForced((int) c[0], (int) c[1], false);
                }
            }
        }
    }

    private static void finishRuntime(GameTestHelper helper, AirflowRegions regions, AirflowRecorder rec, Path dir,
                                      List<Integer> jumps, double heapStart, Runnable cleanup) {
        int seconds = rec.seconds();
        double early = rec.coreShare(120, 420), late = rec.coreShare(Math.max(0, seconds - 300), seconds);
        double heapEnd = usedHeapAfterGc();
        long restores = regions.restores(), lost = regions.sectionsLost(), saves = regions.savesWritten();
        regions.stopRecording(); // closes the CSVs and writes summary.md
        AirflowRecorder.Measures m = rec.measures();
        List<Integer> drops = rec.dropSeconds();
        cleanup.run();
        List<String> failures = new ArrayList<>();
        if (m.serverMeanMs() > 0.2) {
            failures.add("server thread " + f(m.serverMeanMs()) + " ms a tick on average (bar 0.2)");
        }
        if (m.serverP99Ms() > 1) {
            failures.add("server thread p99 " + f(m.serverP99Ms()) + " ms (bar 1)");
        }
        if (m.airBusyPct() > 50) {
            failures.add("air thread " + f(m.airBusyPct()) + " % busy (bar 50)");
        }
        if (!(m.frameAgeP95() <= 2)) {
            failures.add("frame age p95 " + f(m.frameAgeP95()) + " s (bar 2)");
        }
        if (m.failures() > 0 || m.desyncs() > 0) {
            failures.add(m.failures() + " region failures, " + m.desyncs() + " desyncs");
        }
        for (int d : drops) {
            if (jumps.stream().noneMatch(j -> d >= j && d <= j + 15)) {
                failures.add("a backlog drop at " + d + " s, not after a jump");
            }
        }
        if (restores == 0) {
            failures.add("no tile reopened from its save");
        }
        if (lost > 0) {
            failures.add(lost + " saved sections lost to unloads");
        }
        if (late > 1.5 * early + 0.5) {
            failures.add("regions slow down: " + f(late) + " % of a core in the last 5 min against " + f(early) + " %");
        }
        if (!Double.isNaN(heapStart) && heapEnd > heapStart + 64) {
            failures.add("heap grows: " + f(heapEnd) + " MB against " + f(heapStart) + " MB at minute 2");
        }
        List<String> out = new ArrayList<>();
        out.add("# Airflow runtime soak (GreedyFluids M7f f2)");
        out.add("");
        out.add(seconds + " s recorded; recorder output and its summary in this folder.");
        out.add("");
        out.add("| Measure | Value |");
        out.add("|---|---|");
        out.add("| Server thread, mean / p99 / worst per tick | " + f(m.serverMeanMs()) + " / " + f(m.serverP99Ms())
                + " / " + f(m.serverMaxMs()) + " ms |");
        out.add("| Air thread, mean busy | " + f(m.airBusyPct()) + " % |");
        out.add("| Frame age p95 | " + f(m.frameAgeP95()) + " s |");
        out.add("| Failures / desyncs / drops | " + m.failures() + " / " + m.desyncs() + " / " + m.drops() + " |");
        out.add("| Jumps at (s) | " + jumps + " |");
        out.add("| Saves written / tiles restored / saved sections lost | " + saves + " / " + restores + " / " + lost + " |");
        out.add("| Core share per region, minutes 2–7 / last 5 | " + f(early) + " / " + f(late) + " % |");
        out.add("| Heap after a collection, minute 2 / end | " + f(heapStart) + " / " + f(heapEnd) + " MB |");
        out.add("");
        out.add(failures.isEmpty() ? "**PASS**" : "**FAIL:** " + String.join("; ", failures));
        write(dir.resolve("soak.md"), out);
        Meteodyne.LOGGER.info("Airflow soak (runtime):\n{}", String.join("\n", out));
        if (failures.isEmpty()) {
            helper.succeed();
        } else {
            helper.fail("runtime soak: " + String.join("; ", failures));
        }
    }

    private AirflowSoaks() {
    }
}
