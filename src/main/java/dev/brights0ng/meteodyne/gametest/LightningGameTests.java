package dev.brights0ng.meteodyne.gametest;

import java.util.SplittableRandom;

import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.cloud.CloudType;
import dev.brights0ng.meteodyne.cloud.sim.SimCloud;
import dev.brights0ng.meteodyne.lightning.Lightning;
import dev.brights0ng.meteodyne.lightning.LightningModel;
import dev.brights0ng.meteodyne.lightning.LightningPayload;
import dev.brights0ng.meteodyne.sim.world.CloudWorld;
import dev.brights0ng.meteodyne.sim.world.WeatherSim;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Weather phase 6b: where lightning goes. A ground strike takes the tallest thing near the spot under its flash (a
 * pillar, a ship's deck); an in-cloud flash takes a flier inside the cloud within reach; a storm's flash comes out as
 * a payload, with a real bolt where it struck. The ship case needs Sable: {@code ShipLightningGameTests}.
 */
@GameTestHolder(Meteodyne.MODID)
@PrefixGameTestTemplate(false)
public final class LightningGameTests {

    private static final String SCRATCH = GameTestStructures.EMPTY;

    @GameTest(template = SCRATCH, batch = "lightning_pillar")
    public static void theTallestThingNearbyTakesTheStrike(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameTestSky.openSky(helper);
        for (int y = 1; y <= 20; y++) {
            helper.setBlock(new BlockPos(6, y, 6), Blocks.STONE);
        }
        BlockPos pillar = helper.absolutePos(new BlockPos(6, 20, 6));
        Lightning.Strike strike = Lightning.groundTarget(level, pillar.getX() - 4 + 0.5, pillar.getY() + 100,
                pillar.getZ() - 4 + 0.5, 16);
        helper.assertTrue(strike != null, "a strike");
        helper.assertTrue(strike.terrain(), "on terrain");
        BlockPos at = BlockPos.containing(strike.at());
        helper.assertTrue(at.getX() == pillar.getX() && at.getZ() == pillar.getZ() && at.getY() == pillar.getY() + 1,
                "on top of the pillar " + pillar + ", not the ground under the flash: " + at);
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "lightning_sky")
    public static void aFlierInsideTheCloudIsHit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameTestSky.openSky(helper);
        Pig pig = EntityType.PIG.create(level);
        helper.assertTrue(pig != null, "a pig");
        pig.setNoAi(true);
        pig.setNoGravity(true);
        BlockPos up = helper.absolutePos(new BlockPos(4, 60, 4));
        pig.moveTo(Vec3.atBottomCenterOf(up));
        level.addFreshEntity(pig);
        try {
            Lightning.Strike hit = Lightning.skyTarget(level, up.getX() + 6, up.getY() + 3, up.getZ(), up.getY() - 10,
                    up.getY() + 20, 24);
            helper.assertTrue(hit != null && hit.at().distanceTo(pig.position()) < 0.01,
                    "the flying pig 6 blocks from the flash is hit: " + hit);
            helper.assertTrue(Lightning.skyTarget(level, up.getX() + 6, up.getY() + 3, up.getZ(), up.getY() - 10,
                    up.getY() + 20, 2) == null, "but not from beyond reach");
            helper.assertTrue(Lightning.skyTarget(level, up.getX() + 6, up.getY() + 3, up.getZ(), up.getY() + 5,
                    up.getY() + 20, 24) == null, "nor when it is below the cloud");
        } finally {
            pig.discard();
        }
        helper.succeed();
    }

    @GameTest(template = SCRATCH, batch = "lightning_storm", timeoutTicks = 60)
    public static void aStormFlashes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WeatherSim sim = WeatherSim.of(level);
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        sim.field().ensure(at.getX(), at.getZ(), 2000, sim.readEnv(), sim.time());
        SimCloud cloud = CloudWorld.spawn(level, CloudType.CUMULONIMBUS_CAPILLATUS, at.getX(), at.getZ());
        helper.assertTrue(cloud != null, "a storm");
        cloud.lightning = 1;
        helper.runAfterDelay(2, () -> {
            try {
                long now = level.getGameTime();
                LightningModel.Settings s = LightningModel.Settings.DEFAULT;
                SplittableRandom rng = new SplittableRandom(11);
                LightningPayload inCloud = Lightning.flash(level, cloud, now, LightningModel.Kind.CLOUD, s, rng);
                helper.assertTrue(inCloud != null, "it flashes");
                helper.assertTrue(inCloud.y() > cloud.baseY, "inside the cloud: " + inCloud.y());
                LightningPayload ground = Lightning.flash(level, cloud, now, LightningModel.Kind.GROUND, s, rng);
                helper.assertTrue(ground != null, "and again");
                if (ground.kind() == LightningModel.Kind.GROUND) {
                    helper.assertTrue(ground.struck(), "a ground strike says where");
                    helper.assertTrue(Math.hypot(ground.targetX() - ground.x(), ground.targetZ() - ground.z())
                            <= s.attachRadius() + 1.5, "within reach of the flash");
                } else {
                    // The spot under the flash wasn't loaded: it stays in the cloud.
                    helper.assertFalse(ground.struck(), "nothing struck");
                }
            } finally {
                sim.cloudSim().clouds().remove(cloud);
            }
            helper.succeed();
        });
    }

    private LightningGameTests() {
    }
}
