package dev.brights0ng.enginesandempires.gametest;

import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatDrivers;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatPowerData;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatRadiant;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatRegion;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatTables;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8f-3: what the heat simulation does to a person (radiant warmth) and the sources that are on by their
 * block entity (Cold Sweat's hearth). Built high above the test cell ({@link #Y0}), clear of the other heat tests'
 * fires and lava, and in the same batch as them.
 */
@GameTestHolder(EnginesAndEmpiresMod.MODID)
@PrefixGameTestTemplate(false)
public final class HeatPlayerGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final String BATCH = "heat_air_room";
    /** Height above the cell's floor everything is built at. */
    private static final int Y0 = 40;

    private static final BlockState LIT = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true);

    private static Vec3 body(GameTestHelper helper, double x, double y, double z) {
        return helper.absoluteVec(new Vec3(x, y, z));
    }

    /**
     * Takes down what was built above the cell (the structure's own clean-up doesn't reach that high; left standing it
     * would be the tallest thing about for the lightning tests), then succeeds.
     */
    private static void finish(GameTestHelper helper) {
        for (int y = Y0 + 3; y >= Y0 - 2; y--) {
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        helper.succeed();
    }

    // ---- radiant ----

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aCampfireRadiatesByLineOfSight(GameTestHelper helper) {
        BlockPos fire = new BlockPos(1, Y0, 3);
        helper.setBlock(fire.below(), Blocks.STONE);
        helper.setBlock(fire, LIT);
        // A body two blocks east of the fire, its middle level with the fire's middle: 1.5 blocks from its side
        Vec3 at = body(helper, 3.5, Y0 + 0.5, 3.5);
        double clear = HeatRadiant.at(helper.getLevel(), at);
        helper.setBlock(new BlockPos(2, Y0, 3), Blocks.GLASS);
        double glass = HeatRadiant.at(helper.getLevel(), at);
        helper.setBlock(new BlockPos(2, Y0, 3), Blocks.STONE);
        double wall = HeatRadiant.at(helper.getLevel(), at);
        EnginesAndEmpiresMod.LOGGER.info("heat radiant test: clear {}, glass {}, wall {}", clear, glass, wall);
        // 12 x 1 x 0.5 / 1.5^2 = 2.67 C
        helper.assertTrue(Math.abs(clear - 2.67) < 0.1, "a campfire's radiant warmth 1.5 blocks off: " + clear);
        helper.assertTrue(Math.abs(glass - clear / 4) < 0.05, "a quarter through glass: " + glass);
        helper.assertTrue(wall == 0, "none through stone: " + wall);
        finish(helper);
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aLavaLakesEdgeIsHotButNeverPastLavasLimit(GameTestHelper helper) {
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, Y0 - 2, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, Y0 - 1, z), Blocks.LAVA);
            }
        }
        // Standing on a stone ledge at the lake's edge, over the lava's level
        helper.setBlock(new BlockPos(0, Y0 - 1, 3), Blocks.STONE);
        double edge = HeatRadiant.at(helper.getLevel(), body(helper, 0.5, Y0 + 0.9, 3.5));
        EnginesAndEmpiresMod.LOGGER.info("heat radiant test: lava lake edge {}", edge);
        helper.assertTrue(edge > 25, "hot at a lava lake's edge: " + edge);
        helper.assertTrue(edge <= 60, "but eased toward lava's +60 limit, not past it: " + edge);
        finish(helper);
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void blueIceChillsByRadiationAlone(GameTestHelper helper) {
        helper.setBlock(new BlockPos(3, Y0, 3), Blocks.BLUE_ICE);
        HeatPowerData.Variant v = HeatTables.power(Blocks.BLUE_ICE.defaultBlockState());
        helper.assertTrue(v != null && !v.inAir(), "blue ice makes no region");
        double chill = HeatRadiant.at(helper.getLevel(), body(helper, 4.5, Y0 + 0.5, 3.5));
        EnginesAndEmpiresMod.LOGGER.info("heat radiant test: blue ice at 1 block {}", chill);
        // 12 x -0.8 / 1^2 = -9.6 C, eased toward -20
        helper.assertTrue(chill < -7 && chill > -10, "blue ice a block away chills: " + chill);
        finish(helper);
    }

    // ---- block-entity sources ----

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void enginesAreSourcesOnlyWhileRunning(GameTestHelper helper) {
        BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("createdieselgenerators:diesel_engine")).ifPresent(b -> {
            List<HeatPowerData.Variant> driven = HeatTables.driven(b.defaultBlockState());
            helper.assertTrue(driven.size() == 1 && driven.get(0).driver().equals(HeatDrivers.RUNNING)
                    && driven.get(0).power() == 0.8, "a diesel engine is driven by running: " + driven);
            BlockPos pos = new BlockPos(3, Y0, 3);
            helper.setBlock(pos, b.defaultBlockState());
            double share = HeatDrivers.share(helper.getLevel(), helper.absolutePos(pos), b.defaultBlockState(), driven.get(0));
            helper.assertTrue(share == 0, "an engine with no fuel is off: " + share);
        });
        BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("simulated:red_portable_engine")).ifPresent(b ->
                helper.assertTrue(HeatTables.driven(b.defaultBlockState()).size() == 1, "portable engines are driven"));
        finish(helper);
    }

    @GameTest(template = SCRATCH, batch = BATCH)
    public static void aHotAirBurnerHeatsLikeACampfireByItsOutput(GameTestHelper helper) {
        var burner = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse("aeronautics:adjustable_burner"));
        if (burner.isEmpty()) {
            finish(helper);
            return;
        }
        BlockState fire = burner.get().defaultBlockState();
        List<HeatPowerData.Variant> driven = HeatTables.driven(fire);
        helper.assertTrue(driven.size() == 1 && driven.get(0).power() == 1.0 && driven.get(0).radiant() == 0.5
                && driven.get(0).driver().equals(HeatDrivers.HOT_AIR_BURNER), "a burner: one campfire, driven: " + driven);
        for (var p : fire.getProperties()) {
            if (p.getName().equals("variant")) {
                BlockState soul = setValue(fire, p, "soulful");
                List<HeatPowerData.Variant> s = HeatTables.driven(soul);
                helper.assertTrue(s.size() == 1 && Math.abs(s.get(0).power() - 1.1) < 1e-9, "a soul burner: 1.1, once: " + s);
            }
        }
        BlockPos pos = new BlockPos(3, Y0, 3);
        helper.setBlock(pos.below(), Blocks.STONE);
        helper.setBlock(pos, fire);
        BlockPos abs = helper.absolutePos(pos);
        double off = HeatDrivers.share(helper.getLevel(), abs, fire, driven.get(0));
        helper.setBlock(pos.east(), Blocks.REDSTONE_BLOCK);
        if (helper.getLevel().getBlockEntity(abs)
                instanceof dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerBlockEntity be) {
            be.updateSignal();
        }
        double full = HeatDrivers.share(helper.getLevel(), abs, helper.getLevel().getBlockState(abs), driven.get(0));
        EnginesAndEmpiresMod.LOGGER.info("heat burner test: off {}, full signal {}", off, full);
        helper.assertTrue(off == 0, "no signal, no heat: " + off);
        helper.assertTrue(Math.abs(full - 1) < 1e-9, "full signal at the most hot air: a campfire's worth: " + full);
        finish(helper);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState setValue(BlockState s, net.minecraft.world.level.block.state.properties.Property p, String name) {
        return s.setValue(p, (Comparable) p.getValue(name).orElseThrow());
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void aHearthHeatsByColdSweatsControls(GameTestHelper helper) {
        if (!ModList.get().isLoaded("cold_sweat")) {
            finish(helper);
            return;
        }
        HeatWorld w = HeatWorld.of(helper.getLevel());
        BlockPos a = helper.absolutePos(new BlockPos(0, Y0 - 1, 0));
        BlockPos b = helper.absolutePos(new BlockPos(6, Y0 + 3, 6));
        int[] area = {Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), b.getY(), Math.max(a.getZ(), b.getZ())};
        w.setPaused(true);
        w.setExtraAnchors(List.of());
        w.setTestArea(area);
        w.clearSaved(area);
        w.forgetRegions();
        try {
            BlockPos hearth = new BlockPos(3, Y0, 3);
            for (int x = 0; x <= 6; x++) {
                for (int z = 0; z <= 6; z++) {
                    helper.setBlock(new BlockPos(x, Y0 - 1, z), Blocks.STONE);
                }
            }
            helper.setBlock(hearth.above(), com.momosoftworks.coldsweat.core.init.ModBlocks.HEARTH_TOP.value());
            helper.setBlock(hearth, com.momosoftworks.coldsweat.core.init.ModBlocks.HEARTH_BOTTOM.value().defaultBlockState()
                    .setValue(com.momosoftworks.coldsweat.common.block.HearthBottomBlock.FACING, net.minecraft.core.Direction.NORTH));
            var be = (com.momosoftworks.coldsweat.common.blockentity.HearthBlockEntity)
                    helper.getLevel().getBlockEntity(helper.absolutePos(hearth));
            helper.assertTrue(be != null, "the hearth's block entity");
            helper.assertFalse(be.isSmartEnabled(), "no smart mode where the heat simulation stands in");
            be.checkForSmokestack();
            be.setHotFuel(1000, true);
            be.checkInputSignal();
            w.runNow(1);
            HeatRegion off = region(w, helper.absolutePos(hearth));
            helper.assertTrue(off == null || off.sourceCount() == 0, "fuelled but switched off: no source");

            // A redstone signal on its heating side (east, facing north) switches it on
            helper.setBlock(hearth.east(), Blocks.REDSTONE_BLOCK);
            be.checkInputSignal();
            helper.assertTrue(be.isUsingHotFuel(), "Cold Sweat says it is using hot fuel");
            w.runNow(60);
            HeatRegion on = region(w, helper.absolutePos(hearth));
            EnginesAndEmpiresMod.LOGGER.info("heat hearth test: sources {}", on == null ? -1 : on.sourceCount());
            helper.assertTrue(on != null && on.sourceCount() > 0, "heating: the hearth is a source");
            BlockPos beside = helper.absolutePos(hearth.west());
            double warm = w.offsetAt(beside.getX() + 0.5, beside.getY() + 0.5, beside.getZ() + 0.5);
            helper.assertTrue(warm > 0.5, "the air beside it warms: " + warm);
        } finally {
            w.forgetRegions();
            w.clearSaved(area);
            w.setTestArea(null);
            w.setPaused(false);
        }
        finish(helper);
    }

    private static HeatRegion region(HeatWorld w, BlockPos abs) {
        for (HeatRegion r : w.regions()) {
            if (r.contains(abs.getX(), abs.getY(), abs.getZ())) {
                return r;
            }
        }
        return null;
    }
}
