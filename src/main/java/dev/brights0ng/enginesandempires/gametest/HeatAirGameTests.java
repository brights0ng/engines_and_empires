package dev.brights0ng.enginesandempires.gametest;

import java.util.List;
import java.util.Optional;

import dev.brights0ng.enginesandempires.weather.heat.world.HeatPowerData;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatRegion;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatTables;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatWorld;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.heat.air.AirGrid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8f-2: the heat simulation in the world (tables, cells, regions, natural sources, saving). The live
 * tick is paused in each test, which drives the regions itself ({@link HeatWorld#runNow}), and only sources inside
 * the test's own cell are planned ({@link HeatWorld#setTestArea}), so neighbouring tests' fires don't mix in. Each test
 * runs to the end in its first tick, so tests of one batch never overlap in time.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class HeatAirGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    /** One batch for all (it runs before the default batch, which holds an unrelated hang as of 2026-10-15). */
    private static final String BATCH = "heat_air_room";

    private static Optional<Block> block(String id) {
        return BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(id));
    }

    private static HeatWorld world(GameTestHelper helper) {
        HeatWorld w = HeatWorld.of(helper.getLevel());
        w.setPaused(true);
        w.setExtraAnchors(List.of());
        w.setTestArea(area(helper));
        w.clearSaved(area(helper));
        w.forgetRegions();
        return w;
    }

    /** The test's cell, world blocks. */
    private static int[] area(GameTestHelper helper) {
        BlockPos a = helper.absolutePos(BlockPos.ZERO);
        BlockPos b = helper.absolutePos(new BlockPos(6, 6, 6));
        return new int[]{Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())};
    }

    private static void done(GameTestHelper helper, HeatWorld w) {
        w.forgetRegions();
        w.clearSaved(area(helper));
        w.setTestArea(null);
        w.setExtraAnchors(List.of());
        w.setPaused(false);
        helper.succeed();
    }

    private static double offset(GameTestHelper helper, HeatWorld w, BlockPos relative) {
        BlockPos abs = helper.absolutePos(relative);
        return w.offsetAt(abs.getX() + 0.5, abs.getY() + 0.5, abs.getZ() + 0.5);
    }

    private static HeatRegion regionAt(GameTestHelper helper, HeatWorld w, BlockPos relative) {
        BlockPos abs = helper.absolutePos(relative);
        for (HeatRegion r : w.regions()) {
            if (r.contains(abs.getX(), abs.getY(), abs.getZ())) {
                return r;
            }
        }
        return null;
    }

    /** A stone room filling the cell: floor y0, roof y4, walls at 0 and 6; inside 5 x 3 x 5. */
    private static void stoneRoom(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                for (int y = 0; y <= 4; y++) {
                    boolean shell = x == 0 || x == 6 || z == 0 || z == 6 || y == 0 || y == 4;
                    helper.setBlock(new BlockPos(x, y, z), shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static final BlockState LIT = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true);
    private static final BlockState OUT = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, false);

    // ---- tables ----

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void theTablesAreSeeded(GameTestHelper helper) {
        HeatPowerData.Variant fire = HeatTables.power(LIT);
        helper.assertTrue(fire != null && fire.power() == 1.0 && fire.radiant() == 0.5 && fire.inAir() && !fire.natural(),
                "a lit campfire: power 1, half radiant, placed");
        helper.assertTrue(HeatTables.power(OUT) == null, "an unlit campfire is nothing");
        HeatPowerData.Variant lava = HeatTables.power(Blocks.LAVA.defaultBlockState());
        helper.assertTrue(lava != null && lava.natural() && lava.perFace() && lava.power() == 3.0, "lava: natural, per face, 3");
        HeatPowerData.Variant glow = HeatTables.power(Blocks.GLOWSTONE.defaultBlockState());
        helper.assertTrue(glow != null && !glow.inAir(), "glowstone: radiant only");
        helper.assertTrue(HeatTables.power(Blocks.TORCH.defaultBlockState()) == null, "torches are not sources");
        HeatPowerData.Variant soul = HeatTables.power(Blocks.SOUL_CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        helper.assertTrue(soul != null && Math.abs(soul.power() - 1.1) < 1e-9, "soul campfire 10% hotter");

        helper.assertTrue(HeatTables.insulation(Blocks.WHITE_WOOL).conductance() == 0.1, "wool insulates");
        helper.assertTrue(HeatTables.insulation(Blocks.OAK_PLANKS).conductance() == 0.25, "wood");
        helper.assertTrue(HeatTables.insulation(Blocks.GLASS).conductance() == 2.5, "glass");
        helper.assertTrue(HeatTables.insulation(Blocks.STONE).conductance() == 1.0, "stone, by default");

        // Air limits (Bright, 2026-10-15): lava +60 above the outside air; the icebox never below 0 C
        helper.assertTrue(HeatTables.offsetLimit(Blocks.LAVA.defaultBlockState(), lava, 10) == 60, "lava at most +60");
        helper.assertTrue(HeatTables.offsetLimit(LIT, fire, 10) == 45, "a campfire at most +45");
        block("cold_sweat:icebox").ifPresent(box -> {
            BlockState frosted = box.defaultBlockState();
            for (var p : box.getStateDefinition().getProperties()) {
                if (p.getName().equals("frosted")) {
                    frosted = setTrue(frosted, p);
                }
            }
            HeatPowerData.Variant v = HeatTables.power(frosted);
            helper.assertTrue(v != null && HeatTables.offsetLimit(frosted, v, 10) == -10,
                    "an icebox on a 10 C day cools at most to 0 C");
        });
        helper.succeed();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setTrue(BlockState s, net.minecraft.world.level.block.state.properties.Property p) {
        return s.setValue(p, (Comparable) p.getValue("true").orElseThrow());
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void cellsFollowTheBlocksShapes(GameTestHelper helper) {
        HeatTables.Cell stone = HeatTables.cell(Blocks.STONE.defaultBlockState());
        helper.assertTrue(stone.kind() == AirGrid.SOLID && stone.openFaces() == 0, "stone: closed solid");
        helper.assertTrue(HeatTables.cell(Blocks.AIR.defaultBlockState()).kind() == AirGrid.AIR, "air");
        helper.assertTrue(HeatTables.cell(Blocks.TORCH.defaultBlockState()).kind() == AirGrid.AIR, "a torch lets air by");
        HeatTables.Cell slab = HeatTables.cell(Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        helper.assertTrue(slab.kind() == AirGrid.SOLID && (slab.openFaces() & AirGrid.DOWN) == 0
                        && (slab.openFaces() & AirGrid.UP) != 0 && (slab.openFaces() & AirGrid.EAST) != 0,
                "a bottom slab: closed below, open above and to the sides: " + slab.openFaces());
        HeatTables.Cell water = HeatTables.cell(Blocks.WATER.defaultBlockState());
        helper.assertTrue(water.kind() == AirGrid.SOLID && water.conductance() == 1.5f && water.capacity() == 2.0f,
                "water: a solid of water");
        HeatTables.Cell wool = HeatTables.cell(Blocks.WHITE_WOOL.defaultBlockState());
        helper.assertTrue(wool.conductance() == 0.1f, "wool's material");
        helper.succeed();
    }

    // ---- regions ----

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void aSealedRoomWarmsAroundALitCampfireAndCoolsWhenItGoesOut(GameTestHelper helper) {
        HeatWorld w = world(helper);
        stoneRoom(helper);
        BlockPos fire = new BlockPos(3, 1, 3);
        helper.setBlock(fire, LIT);
        w.runNow(600);
        HeatRegion r = regionAt(helper, w, fire);
        helper.assertTrue(r != null && !r.naturalOnly() && r.sourceCount() > 0, "the campfire makes a region of its own");
        helper.assertTrue(windShare(helper, r, new BlockPos(2, 2, 2)) == 0, "no wind inside a sealed room");
        // At the same height (warm air gathers under the ceiling, so a floor spot by the fire can be cooler)
        double corner = offset(helper, w, new BlockPos(1, 2, 1));
        double byFire = offset(helper, w, new BlockPos(3, 2, 3));
        double outside = offset(helper, w, new BlockPos(3, 7, -4));
        Meteodyne.LOGGER.info("heat room test: region {} sources {}; corner {}, by the fire {}, outside {}",
                r == null ? -1 : r.id(), r == null ? -1 : r.sourceCount(), corner, byFire, outside);
        helper.assertTrue(corner > 3, "the room is warm after 10 minutes: " + corner);
        helper.assertTrue(byFire > corner, "warmer by the fire: " + byFire + " / " + corner);
        helper.assertTrue(outside < corner * 0.3, "outside the room, little: " + outside);

        helper.setBlock(fire, OUT);
        w.runNow(10);
        w.runNow(600);
        double after = offset(helper, w, new BlockPos(1, 2, 1));
        Meteodyne.LOGGER.info("heat room test: after the fire went out {}", after);
        helper.assertTrue(after < corner * 0.2, "ten minutes after the fire went out, it has cooled: " + after);
        done(helper, w);
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200, skyAccess = true)
    public static void aHoleToTheSkyLetsWarmthOut(GameTestHelper helper) {
        HeatWorld w = world(helper);
        stoneRoom(helper);
        BlockPos fire = new BlockPos(3, 1, 3);
        helper.setBlock(fire, LIT);
        w.runNow(1200);
        double sealed = offset(helper, w, new BlockPos(1, 2, 3));
        // The roof's middle 3 x 3 taken off: open sky over the fire (the test cell's walls are barriers, its top open)
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 4, z), Blocks.AIR);
            }
        }
        w.runNow(1200);
        double open = offset(helper, w, new BlockPos(1, 2, 3));
        Meteodyne.LOGGER.info("heat roof-hole test: sealed {}, open {}", sealed, open);
        helper.assertTrue(open < sealed * 0.85, "a hole in the roof loses warmth: " + open + " of " + sealed);
        done(helper, w);
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void lavaHeatsTheAirOnlyWhileSomeoneIsNearAndAtOnce(GameTestHelper helper) {
        HeatWorld w = world(helper);
        // A pool of lava in a stone cup, open above
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                boolean rim = x == 2 || x == 4 || z == 2 || z == 4;
                helper.setBlock(new BlockPos(x, 1, z), rim ? Blocks.STONE : Blocks.LAVA);
            }
        }
        BlockPos lava = new BlockPos(3, 1, 3);
        w.runNow(10);
        helper.assertTrue(regionAt(helper, w, lava) == null, "nobody near: no region for lava");

        BlockPos abs = helper.absolutePos(lava);
        w.setExtraAnchors(List.of(new Vec3(abs.getX() + 10, abs.getY() + 2, abs.getZ())));
        w.runNow(1);
        HeatRegion r = regionAt(helper, w, lava);
        helper.assertTrue(r != null && r.naturalOnly(), "someone near: a natural region");
        double above = offset(helper, w, new BlockPos(3, 2, 3));
        Meteodyne.LOGGER.info("heat lava test: over the lava {}, strongest {}", above,
                r == null || r.published() == null ? -1 : r.published().maxMagnitude());
        helper.assertTrue(above > 5, "already hot over the lava (started at its steady state): " + above);
        helper.assertTrue(r.published().maxMagnitude() <= 60.5, "never past +60: " + r.published().maxMagnitude());

        w.setExtraAnchors(List.of());
        w.runNow(1);
        helper.assertTrue(regionAt(helper, w, lava) == null, "nobody near again: dropped");
        done(helper, w);
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void aPlacedSourceKeepsNaturalOnesInItsBoxGoing(GameTestHelper helper) {
        HeatWorld w = world(helper);
        BlockPos fire = new BlockPos(1, 1, 1);
        helper.setBlock(fire.below(), Blocks.STONE);
        helper.setBlock(fire, LIT);
        w.runNow(1);
        HeatRegion alone = regionAt(helper, w, fire);
        helper.assertTrue(alone != null, "the campfire's region");
        int count = alone.sourceCount();
        helper.setBlock(new BlockPos(5, 1, 5), Blocks.MAGMA_BLOCK);
        w.runNow(1);
        HeatRegion both = regionAt(helper, w, fire);
        Meteodyne.LOGGER.info("heat kept test: alone {}, with magma {}", count,
                both == null ? -1 : both.sourceCount());
        helper.assertTrue(both != null && !both.naturalOnly() && both.sourceCount() > count,
                "magma inside the campfire's region counts, with nobody near: " + (both == null ? -1 : both.sourceCount())
                        + " > " + count);
        done(helper, w);
    }

    // ---- saving ----

    private static float windShare(GameTestHelper helper, HeatRegion r, BlockPos relative) {
        BlockPos abs = helper.absolutePos(relative);
        HeatRegion.Published p = r.published();
        return p.wind()[p.index(abs.getX() - p.minX(), abs.getY() - p.minY(), abs.getZ() - p.minZ())];
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void theWindReachesAirAsItDoesShips(GameTestHelper helper) {
        HeatWorld w = world(helper);
        // High above the cell (its barrier walls would shelter everything): a wall across x = 3, a campfire to make a
        // region, and a wind blowing east (+x)
        int y0 = 20;
        for (int z = 0; z <= 6; z++) {
            for (int y = y0; y <= y0 + 2; y++) {
                helper.setBlock(new BlockPos(3, y, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(5, y0 - 1, 5), Blocks.STONE);
        helper.setBlock(new BlockPos(5, y0, 5), LIT);
        BlockPos abs = helper.absolutePos(BlockPos.ZERO);
        w.setTestArea(new int[]{abs.getX() - 1, abs.getY(), abs.getZ() - 1, abs.getX() + 7, abs.getY() + y0 + 4, abs.getZ() + 7});
        dev.brights0ng.paradynelib.wind.WindColumn before =
                dev.brights0ng.enginesandempires.weather.wind.WindSources.override();
        dev.brights0ng.enginesandempires.weather.wind.WindSources.setOverride(
                dev.brights0ng.paradynelib.wind.WindColumn.uniform(5, 0));
        try {
            w.runNow(1);
            HeatRegion r = regionAt(helper, w, new BlockPos(5, y0, 5));
            helper.assertTrue(r != null && r.published() != null, "the campfire's region");
            float lee = windShare(helper, r, new BlockPos(4, y0 + 1, 2));
            float windward = windShare(helper, r, new BlockPos(2, y0 + 1, 2));
            float above = windShare(helper, r, new BlockPos(2, y0 + 6, 2));
            Meteodyne.LOGGER.info("heat wind test: lee {}, windward {}, high above {}", lee, windward, above);
            helper.assertTrue(lee < 0.05, "in the wall's lee, no wind: " + lee);
            helper.assertTrue(windward > 0.95, "on its windward side (the wall downwind), all of it: " + windward);
            helper.assertTrue(above > 0.95, "above the wall, all of it: " + above);
        } finally {
            dev.brights0ng.enginesandempires.weather.wind.WindSources.setOverride(before);
        }
        done(helper, w);
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void aRoomComesBackAsItWasAfterAReload(GameTestHelper helper) {
        HeatWorld w = world(helper);
        stoneRoom(helper);
        BlockPos fire = new BlockPos(3, 1, 3);
        helper.setBlock(fire, LIT);
        w.runNow(300);
        BlockPos probe = new BlockPos(1, 3, 1);
        double warm = offset(helper, w, probe);
        helper.assertTrue(warm > 2, "warming: " + warm);
        w.persistAll();
        w.forgetRegions();
        helper.assertTrue(offset(helper, w, probe) == 0, "forgotten");
        w.runNow(0);
        double back = offset(helper, w, probe);
        Meteodyne.LOGGER.info("heat saved test: warm {}, back {}", warm, back);
        helper.assertTrue(Math.abs(back - warm) < 0.1, "back as it was: " + back + " / " + warm);

        // A cooling room (fire out) comes back too, though nothing in it is a source any more
        helper.setBlock(fire, OUT);
        w.runNow(30);
        double cooling = offset(helper, w, probe);
        w.persistAll();
        w.forgetRegions();
        w.runNow(0);
        double cooledBack = offset(helper, w, probe);
        Meteodyne.LOGGER.info("heat saved test: cooling {}, back {}", cooling, cooledBack);
        helper.assertTrue(cooling > 0.5 && Math.abs(cooledBack - cooling) < 0.1,
                "a cooling room comes back: " + cooledBack + " / " + cooling);
        done(helper, w);
    }
}
