package dev.brights0ng.meteodyne.sable.gametest;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.gametest.GameTestSky;
import dev.brights0ng.meteodyne.lightning.Lightning;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import dev.brights0ng.paradynelib.sable.ships.ShipCover;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 6b, on a ship: a ship's deck above the ground takes the ground strike, and its fire goes on the deck
 * (split from {@code LightningGameTests} in split step 2c).
 *
 * <p>Needs Sable: not a {@code @GameTestHolder} (NeoForge would load every holder, Sable or not), but registered by
 * {@code MeteodyneSable} only when Sable is loaded, each test naming Meteodyne's template namespace itself.
 */
@PrefixGameTestTemplate(false)
public final class ShipLightningGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(templateNamespace = Meteodyne.MODID, template = SCRATCH, batch = "lightning_ship", timeoutTicks = 60)
    public static void aShipAboveTheGroundTakesTheStrike(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameTestSky.openSky(helper);
        ServerSubLevel ship = ShipWeatherGameTests.spawnBlock(helper, 4.5, 14, 4.5, Blocks.OAK_PLANKS.defaultBlockState());
        helper.runAfterDelay(3, () -> {
            try {
                BoundingBox3dc box = ship.boundingBox();
                double cx = (box.minX() + box.maxX()) / 2;
                double cz = (box.minZ() + box.maxZ()) / 2;
                Lightning.Strike strike = Lightning.groundTarget(level, cx, box.maxY() + 100, cz, 16);
                helper.assertTrue(strike != null, "a strike");
                helper.assertFalse(strike.terrain(), "on the ship, not the ground: " + strike + "; box " + box
                        + ", ship top " + ShipCover.top(level, cx, cz, -1000) + ", area top "
                        + ShipCover.area(level, cx - 16, cz - 16, cx + 16, cz + 16).top((int) Math.floor(cx),
                        (int) Math.floor(cz)) + ", ground " + level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(cx),
                        (int) Math.floor(cz)));
                helper.assertTrue(Math.abs(strike.at().y - box.maxY()) < 0.2,
                        "on the deck: " + strike.at().y + " vs " + box.maxY());
                helper.assertTrue(strike.deckFire() != null && ShipCover.inPlot(level, strike.deckFire()),
                        "its fire goes on the deck in the ship's grid: " + strike.deckFire());
                Lightning.strike(level, strike);
                boolean bolt = !level.getEntitiesOfClass(LightningBolt.class, new AABB(strike.at(), strike.at())
                        .inflate(2)).isEmpty();
                helper.assertTrue(bolt, "a bolt where it struck");
            } finally {
                ShipWeatherGameTests.remove(level, ship);
            }
            helper.succeed();
        });
    }

    private ShipLightningGameTests() {
    }
}
