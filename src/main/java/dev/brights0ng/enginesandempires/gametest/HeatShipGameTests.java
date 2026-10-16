package dev.brights0ng.enginesandempires.gametest;

import java.util.List;

import org.joml.Vector2i;

import dev.brights0ng.enginesandempires.weather.heat.world.HeatRegion;
import dev.brights0ng.enginesandempires.weather.heat.world.HeatWorld;
import dev.brights0ng.enginesandempires.weather.wind.WindColumn;
import dev.brights0ng.enginesandempires.weather.wind.WindSources;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 8f-5: the heat simulation on ships (Sable sub-levels). Each test spawns its own ship 60 blocks over the
 * test cell, plans only inside that ship's plot ({@link HeatWorld#setTestArea}) with the live tick paused, and removes
 * the ship (and anything built in the world) at the end. Same batch as the other heat tests.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class HeatShipGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;
    private static final String BATCH = "heat_air_room";
    private static final int HIGH = 60;

    private static final BlockState LIT = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true);

    // ---- ships ----

    /** An empty ship over the cell; blocks are set in its grid around its centre block. */
    private static ServerSubLevel spawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        Pose3d pose = new Pose3d();
        pose.position().set(origin.getX() + 3.5, origin.getY() + HIGH, origin.getZ() + 3.5);
        SubLevel ship = container.allocateNewSubLevel(pose);
        LevelPlot plot = ship.getPlot();
        plot.newEmptyChunk(plot.getCenterChunk());
        return (ServerSubLevel) ship;
    }

    private static void set(ServerSubLevel ship, int x, int y, int z, BlockState state) {
        ship.getPlot().getEmbeddedLevelAccessor().setBlock(new BlockPos(x, y, z), state, 3);
    }

    /** A plot block (in the ship's own grid) at a position relative to its centre block. */
    private static BlockPos plot(ServerSubLevel ship, int x, int y, int z) {
        return ship.getPlot().getCenterBlock().offset(x, y, z);
    }

    /** A stone cabin, 5 x 3 x 5 inside (floor y 0, roof y 4), with a lit campfire in the middle. */
    private static void cabin(ServerSubLevel ship) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 0; y <= 4; y++) {
                    boolean shell = x == -3 || x == 3 || z == -3 || z == 3 || y == 0 || y == 4;
                    if (shell) {
                        set(ship, x, y, z, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        set(ship, 0, 1, 0, LIT);
        ship.updateLastPose();
    }

    private static void remove(GameTestHelper helper, ServerSubLevel ship) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(helper.getLevel());
        LevelPlot plot = ship.getPlot();
        Vector2i origin = container.getOrigin();
        container.removeSubLevel(plot.plotPos.x - origin.x, plot.plotPos.z - origin.y, SubLevelRemovalReason.REMOVED);
    }

    /** Pauses the live tick and plans only inside the ship's plot. */
    private static HeatWorld world(GameTestHelper helper, ServerSubLevel ship) {
        ServerLevel level = helper.getLevel();
        HeatWorld w = HeatWorld.of(level);
        ChunkPos min = ship.getPlot().getChunkMin();
        ChunkPos max = ship.getPlot().getChunkMax();
        int[] area = {min.getMinBlockX(), level.getMinBuildHeight(), min.getMinBlockZ(), max.getMaxBlockX(),
                level.getMaxBuildHeight() - 1, max.getMaxBlockZ()};
        w.setPaused(true);
        w.setExtraAnchors(List.of());
        w.setTestArea(area);
        w.forgetRegions();
        return w;
    }

    private static void done(HeatWorld w) {
        w.forgetRegions();
        w.setTestArea(null);
        w.setExtraAnchors(List.of());
        w.setPaused(false);
    }

    private static HeatRegion regionAt(HeatWorld w, BlockPos p) {
        for (HeatRegion r : w.regions()) {
            if (r.contains(p.getX(), p.getY(), p.getZ())) {
                return r;
            }
        }
        return null;
    }

    private static double offset(HeatWorld w, BlockPos p) {
        return w.offsetAt(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
    }

    // ---- tests ----

    /** Its own batch: it waits a few ticks, and the other heat tests would forget its regions meanwhile. */
    @GameTest(template = SCRATCH, batch = "heat_ship_cabin", timeoutTicks = 200)
    public static void aShipsCabinWarmsAndIsFeltAboard(GameTestHelper helper) {
        ServerSubLevel ship = spawn(helper);
        HeatWorld w = world(helper, ship);
        boolean later = false;
        try {
            cabin(ship);
            w.runNow(600);
            BlockPos fire = plot(ship, 0, 1, 0);
            HeatRegion r = regionAt(w, fire);
            helper.assertTrue(r != null && r.ship() == ship && r.sourceCount() > 0,
                    "the campfire makes a region on its ship");
            BlockPos corner = plot(ship, -2, 2, -2);
            double inside = offset(w, corner);
            double outside = offset(w, plot(ship, 0, 6, 6));
            Meteodyne.LOGGER.info("heat ship test: cabin corner {}, outside the cabin {}", inside, outside);
            helper.assertTrue(inside > 3, "the cabin warms: " + inside);
            helper.assertTrue(outside < inside * 0.3, "outside the cabin, little: " + outside);

            // Near only with someone near the ship
            helper.assertFalse(w.shipAir(ship).near(), "nobody near the ship");

            // Saved with its chunk (Sable's plot saving, fixed): the attachment is in the chunk's own tag
            w.persistAll();
            CompoundTag saved = ((ServerLevelPlot) ship.getPlot()).save();
            CompoundTag chunks = saved.getCompound("chunks");
            boolean found = false;
            for (String key : chunks.getAllKeys()) {
                CompoundTag chunk = chunks.getCompound(key);
                found |= chunk.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY).contains(Meteodyne.id("heat").toString());
            }
            helper.assertTrue(found, "the room is saved in the ship chunk's own tag: " + chunks.getAllKeys());
            helper.assertFalse(saved.contains(AttachmentHolder.ATTACHMENTS_NBT_KEY), "not in the plot's tag");

            // A few ticks on (once Sable has the ship's world bounds), someone standing in the cabin, in the world,
            // feels the ship's room, and counts as near it
            later = true;
            helper.runAfterDelay(3, () -> {
                try {
                    Vec3 world = ship.logicalPose().transformPosition(Vec3.atCenterOf(corner));
                    double felt = w.roomAt(world);
                    Meteodyne.LOGGER.info("heat ship test: felt in the world aboard {}", felt);
                    helper.assertTrue(Math.abs(felt - inside) < 0.05,
                            "the world point aboard reads the cabin: " + felt + " / " + inside);
                    w.setExtraAnchors(List.of(world));
                    helper.assertTrue(w.shipAir(ship).near(), "someone in the cabin is near the ship");
                } finally {
                    done(w);
                    remove(helper, ship);
                }
                helper.succeed();
            });
        } finally {
            if (!later) {
                done(w);
                remove(helper, ship);
            }
        }
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void lavaAboardCountsWithNobodyNear(GameTestHelper helper) {
        ServerSubLevel ship = spawn(helper);
        HeatWorld w = world(helper, ship);
        try {
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    set(ship, x, 0, z, Blocks.STONE.defaultBlockState());
                    boolean rim = x != 0 || z != 0;
                    set(ship, x, 1, z, rim ? Blocks.STONE.defaultBlockState() : Blocks.LAVA.defaultBlockState());
                }
            }
            ship.updateLastPose();
            w.runNow(1);
            HeatRegion r = regionAt(w, plot(ship, 0, 1, 0));
            helper.assertTrue(r != null && r.naturalOnly() && r.ship() == ship,
                    "lava on a ship makes a region with nobody near (a ship is its own anchor)");
            double over = offset(w, plot(ship, 0, 2, 0));
            Meteodyne.LOGGER.info("heat ship test: over the lava aboard {}", over);
            helper.assertTrue(over > 5, "hot over it at once (its steady state): " + over);
        } finally {
            done(w);
            remove(helper, ship);
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = BATCH, timeoutTicks = 200)
    public static void aShipFeelsTheWindAndAHangarRoofsIt(GameTestHelper helper) {
        ServerSubLevel ship = spawn(helper);
        HeatWorld w = world(helper, ship);
        WindColumn before = WindSources.override();
        BlockPos roofAt = null;
        try {
            cabin(ship);
            WindSources.setOverride(WindColumn.uniform(5, 0));
            HeatWorld.ShipAir open = w.shipAir(ship);
            Meteodyne.LOGGER.info("heat ship test: open {}", open);
            helper.assertFalse(open.roofed(), "in the open: not roofed");
            helper.assertTrue(Math.hypot(open.windX(), open.windZ()) > 4, "the wind over it: " + open);

            // A world roof over the whole ship (a hangar), 3 blocks above its top
            BoundingBox3dc b = ship.boundingBox();
            int y = (int) Math.ceil(b.maxY()) + 3;
            roofAt = new BlockPos((int) Math.floor(b.minX()) - 2, y, (int) Math.floor(b.minZ()) - 2);
            for (int x = roofAt.getX(); x <= (int) Math.ceil(b.maxX()) + 2; x++) {
                for (int z = roofAt.getZ(); z <= (int) Math.ceil(b.maxZ()) + 2; z++) {
                    helper.getLevel().setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
                }
            }
            HeatWorld.ShipAir roofed = w.shipAir(ship);
            Meteodyne.LOGGER.info("heat ship test: under a hangar roof {}", roofed);
            helper.assertTrue(roofed.roofed(), "under a hangar roof: roofed");
            helper.assertTrue(Math.hypot(roofed.windX(), roofed.windZ()) < Math.hypot(open.windX(), open.windZ()),
                    "and less wind: " + roofed + " / " + open);
            w.runNow(1);
            HeatRegion r = regionAt(w, plot(ship, 0, 1, 0));
            byte above = r.published().kinds()[r.published().index(
                    plot(ship, 0, 6, 0).getX() - r.published().minX(), plot(ship, 0, 6, 0).getY() - r.published().minY(),
                    plot(ship, 0, 6, 0).getZ() - r.published().minZ())];
            helper.assertTrue(above == dev.brights0ng.enginesandempires.weather.heat.air.AirGrid.AIR,
                    "over its roof is indoors air under the hangar: " + above);
        } finally {
            WindSources.setOverride(before);
            if (roofAt != null) {
                BoundingBox3dc b = ship.boundingBox();
                for (int x = roofAt.getX(); x <= (int) Math.ceil(b.maxX()) + 2; x++) {
                    for (int z = roofAt.getZ(); z <= (int) Math.ceil(b.maxZ()) + 2; z++) {
                        helper.getLevel().setBlock(new BlockPos(x, roofAt.getY(), z), Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
            done(w);
            remove(helper, ship);
        }
        helper.succeed();
    }

    private HeatShipGameTests() {
    }
}
