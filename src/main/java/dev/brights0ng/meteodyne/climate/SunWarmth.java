package dev.brights0ng.meteodyne.climate;

import dev.brights0ng.meteodyne.WeatherOwnership;
import dev.brights0ng.meteodyne.rain.WeatherConfig;
import dev.brights0ng.meteodyne.sky.StormLight;
import dev.brights0ng.meteodyne.sky.StormShade;
import dev.brights0ng.paradynelib.ships.ShipCover;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The sun's warmth on a person (weather phase 8e-2; Bright, 2026-10-15), the world side of {@link SunLight}: a ray from
 * the person toward the sun, through the world's blocks, the ships' blocks (the same rule) and the clouds.
 *
 * <ul>
 *   <li><b>Blocks and ships:</b> walked up to {@code sunBlockReach} blocks (config) out in half-block steps. A block
 *       that stops light stops the sun; anything else that lets some light through (it has a collision shape, a fluid
 *       or dims light a little: glass, leaves, slabs, water) lets a quarter through, however many are met. Flowers,
 *       torches and the like don't count. Unloaded ground is open sky.</li>
 *   <li><b>Clouds:</b> the optical depth along the whole ray ({@link StormShade#opticalDepthAlong}), the same model
 *       as the sky's own shade.</li>
 * </ul>
 * Only where the pack owns the weather (the Overworld); 0 elsewhere.
 */
public final class SunWarmth {

    private static final double STEP = 0.5;

    /** The warmth, C, on {@code entity} now, from its eye. */
    public static double on(ServerLevel level, Entity entity) {
        if (!WeatherOwnership.owns(level)) {
            return 0;
        }
        double max = WeatherConfig.sunWarmthMax();
        if (max <= 0) {
            return 0;
        }
        Vec3 eye = entity.getEyePosition();
        BlockPos feet = entity.blockPosition();
        if (ShipCover.inPlot(level, feet)) {
            Vec3 w = ShipCover.worldCentre(level, feet);
            if (w == null) {
                return 0;
            }
            eye = new Vec3(w.x, w.y + (eye.y - (feet.getY() + 0.5)), w.z);
            feet = BlockPos.containing(w);
        }
        return at(level, eye, feet, level.getTimeOfDay(1f), max, WeatherConfig.sunBlockReach());
    }

    /** The warmth at a world point (the person's eye), with their own blocks {@code feet} and the eye's skipped. */
    public static double at(ServerLevel level, Vec3 eye, BlockPos feet, double timeOfDay, double max, int reach) {
        double[] sun = SunLight.direction(timeOfDay);
        double height = SunLight.height(sun[1]);
        if (height < 0.02) {
            return 0;
        }
        int worst = Math.max(worldBlocks(level, eye, feet, sun, reach),
                ShipCover.rayObstruction(level, eye.x, eye.y, eye.z, sun[0], sun[1], sun[2], 0.3, reach,
                        SunWarmth::classify));
        double blocks = SunLight.blockShare(worst);
        if (blocks <= 0) {
            return 0;
        }
        StormShade.Index clouds = StormLight.index(level);
        double depth = clouds == null ? 0 : StormShade.opticalDepthAlong(clouds, eye.x, eye.y, eye.z, sun[0], sun[1], sun[2]);
        return SunLight.warmth(max, height, SunLight.through(blocks, SunLight.cloudShare(depth)));
    }

    /** The worst block class met along the ray in the world's own blocks. */
    static int worldBlocks(ServerLevel level, Vec3 eye, BlockPos feet, double[] sun, int reach) {
        BlockPos eyeBlock = BlockPos.containing(eye);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int worst = SunLight.PASS;
        long last = Long.MIN_VALUE;
        for (double t = 0.3; t <= reach; t += STEP) {
            pos.set(Math.floor(eye.x + sun[0] * t), Math.floor(eye.y + sun[1] * t), Math.floor(eye.z + sun[2] * t));
            long key = pos.asLong();
            if (key == last) {
                continue;
            }
            last = key;
            if (level.isOutsideBuildHeight(pos)) {
                break;
            }
            if (pos.equals(eyeBlock) || pos.equals(feet)) {
                continue;
            }
            if (!level.hasChunkAt(pos)) {
                break;
            }
            int c = classify(level.getBlockState(pos));
            if (c > worst) {
                worst = c;
                if (worst >= SunLight.STOPS) {
                    break;
                }
            }
        }
        return worst;
    }

    /** What a block does to the sun's ray (see {@link SunLight#PASS}). */
    public static int classify(BlockState state) {
        if (state.isAir()) {
            return SunLight.PASS;
        }
        int light = state.getLightBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (light >= 15) {
            return SunLight.STOPS;
        }
        if (light > 0 || !state.getFluidState().isEmpty()
                || !state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()) {
            return SunLight.PARTLY;
        }
        return SunLight.PASS;
    }

    private SunWarmth() {
    }
}
