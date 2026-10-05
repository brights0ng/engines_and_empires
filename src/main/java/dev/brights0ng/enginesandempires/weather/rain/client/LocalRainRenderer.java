package dev.brights0ng.enginesandempires.weather.rain.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.brights0ng.enginesandempires.weather.rain.ClientWeather;
import dev.brights0ng.enginesandempires.weather.rain.LocalWeather;
import dev.brights0ng.enginesandempires.weather.rain.RainColumn;
import dev.brights0ng.enginesandempires.weather.rain.StreakShape;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;

/**
 * Rain and snow drawn per streak, from the shared query ({@link LocalWeather}), in place of Project Atmosphere's
 * renderer and vanilla's splashes and rain sound (hooked in by {@code mixin/client/PaPrecipitationRendererMixin} and
 * {@code mixin/client/LevelRendererRainMixin}).
 *
 * <h2>Where streaks are (2026-10-04, third pass)</h2>
 * Streaks are spaced one per block <b>in the air</b>, where they cross the pivot height (the camera's height, eased so a
 * jump doesn't shift them), within {@link #RADIUS} blocks (12 on fast graphics), and drawn from {@link #DOWN} below to
 * {@link #UP} above it. Each is traced down along its lean to where it meets the ground ({@link StreakShape#land});
 * whether it rains, and how hard, comes from there, so it matches gameplay. Spacing them on the ground instead made
 * slopes along the wind look twice as wet as flat ground. Past {@link #NEAR} blocks half as many are drawn, fading out
 * toward the edge.
 *
 * <h2>Lean</h2>
 * The lean follows the wind averaged over 15 s, changing slowly, and a gust bends only rain that starts falling after
 * it ({@code RainSlant}, {@link StreakShape}). Streaks pivot at eye level, so a change swings their tops and bottoms
 * half as far each.
 *
 * <h2>Rain that is already falling keeps falling</h2>
 * Each streak has a {@link RainColumn}: new rain arrives from the top at the fall speed, stopping rain drains out of
 * the bottom, strength eases. Streaks are re-sampled every {@link #RESAMPLE} ticks, staggered, and re-traced every
 * other tick.
 *
 * <h2>Splashes and sound</h2>
 * Only where rain (not snow) is reaching the ground, more for harder rain; snow is silent.
 */
public final class LocalRainRenderer {

    private static final ResourceLocation RAIN = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");

    static final int RADIUS = 24;
    static final int RADIUS_FAST = 12;
    static final int UP = 24;
    static final int DOWN = 24;
    static final int NEAR = 12;
    static final int RESAMPLE = 4;
    /** The furthest below the pivot a landing spot is looked for (a streak is only drawn to {@link #DOWN} anyway). */
    static final int MAX_FALL = 64;
    /** Ticks a streak no longer near the camera is kept before being forgotten. */
    static final int FORGET = 40;
    /** The pivot follows the camera's height over about this many ticks (so jumping doesn't shift the streaks). */
    static final double PIVOT_EASE_TICKS = 20;
    /** A camera this far from the pivot (teleport, fast lift) moves it at once. */
    static final double PIVOT_SNAP = 16;

    /** Streak shapes by kind: snow, then rain in five strength bands. */
    private static final int KINDS = 6;
    private static final StreakShape[] TICK_SHAPES = shapes();
    private static final StreakShape[] FRAME_SHAPES = shapes();

    private static final Long2ObjectOpenHashMap<RainColumn> COLUMNS = new Long2ObjectOpenHashMap<>();
    private static int rainSoundTime;
    private static double pivot = Double.NaN;
    private static double prevPivot = Double.NaN;
    private static final double[] LAND = new double[2];

    private static StreakShape[] shapes() {
        StreakShape[] s = new StreakShape[KINDS];
        for (int i = 0; i < KINDS; i++) {
            s[i] = new StreakShape(DOWN, UP);
        }
        return s;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int kind(boolean snow, double strength) {
        return snow ? 0 : 1 + Math.min(4, Math.max(0, (int) (strength * 5)));
    }

    private static void buildShapes(StreakShape[] shapes, double pivotY) {
        double top = pivotY + UP;
        shapes[0].build(ClientWeather.slant(), true, 0, pivotY, top);
        for (int i = 1; i < KINDS; i++) {
            shapes[i].build(ClientWeather.slant(), false, (i - 0.5) / 5, pivotY, top);
        }
    }

    private static int radius() {
        return Minecraft.useFancyGraphics() ? RADIUS : RADIUS_FAST;
    }

    /** Samples, traces and steps the streaks around the camera, and makes splashes. Once per client tick. */
    public static void tick(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || !LocalWeather.enabled(level)) {
            COLUMNS.clear();
            return;
        }
        ClientWeather.tick();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        prevPivot = pivot;
        if (Double.isNaN(pivot) || Math.abs(cam.y - pivot) > PIVOT_SNAP) {
            pivot = cam.y;
            prevPivot = pivot;
        } else {
            pivot += (cam.y - pivot) * (1 - Math.exp(-1 / PIVOT_EASE_TICKS));
        }
        boolean clouds = !LocalWeather.clouds(level).isEmpty();
        if (!clouds && COLUMNS.isEmpty()) {
            return;
        }
        long tick = level.getGameTime();
        double top = pivot + UP;
        buildShapes(TICK_SHAPES, pivot);
        if (clouds) {
            visit(level, cam, tick);
        }
        for (ObjectIterator<Long2ObjectMap.Entry<RainColumn>> it = COLUMNS.long2ObjectEntrySet().fastIterator();
             it.hasNext(); ) {
            RainColumn c = it.next().getValue();
            c.step(top);
            if (tick - c.seenTick > FORGET && (!c.active || tick - c.seenTick > FORGET * 5)) {
                it.remove();
            }
        }
        splash(level, mc, cam, tick);
    }

    /** Traces every streak near the camera to the ground, sampling new and stale ones. */
    private static void visit(ClientLevel level, Vec3 cam, long tick) {
        int r = radius();
        int cx = Mth.floor(cam.x);
        int cz = Mth.floor(cam.z);
        double top = pivot + UP;
        double floor = pivot - MAX_FALL;
        StreakShape.Ground ground = (x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                int i = cx + dx;
                int k = cz + dz;
                double px = i + 0.5;
                double pz = k + 0.5;
                long key = key(i, k);
                RainColumn c = COLUMNS.get(key);
                if (c == null) {
                    // Traced as rain first; snow leans further, so traced again if it's snow.
                    double y = TICK_SHAPES[kind(false, 0.6)].land(px, pz, top, floor, ground, LAND);
                    LocalWeather.Here h = LocalWeather.at(level, LAND[0], y + 0.5, LAND[1]);
                    if (h.snow()) {
                        y = TICK_SHAPES[0].land(px, pz, top, floor, ground, LAND);
                        h = LocalWeather.at(level, LAND[0], y + 0.5, LAND[1]);
                    }
                    c = RainColumn.first(h.falling() ? h.strength() : 0, h.snow(), y, tick);
                    c.x = LAND[0];
                    c.z = LAND[1];
                    COLUMNS.put(key, c);
                } else {
                    if (((i + k + tick) & 1) == 0) {
                        c.ground = TICK_SHAPES[kind(c.snow, c.strength)].land(px, pz, top, floor, ground, LAND);
                        c.x = LAND[0];
                        c.z = LAND[1];
                    }
                    if (tick - c.sampledTick >= RESAMPLE && Math.floorMod(i * 31 + k * 17 + tick, RESAMPLE) == 0) {
                        LocalWeather.Here h = LocalWeather.at(level, c.x, c.ground + 0.5, c.z);
                        c.target = h.falling() ? h.strength() : 0;
                        c.snow = h.snow();
                        c.sampledTick = tick;
                    }
                }
                c.seenTick = tick;
            }
        }
    }

    /** Vanilla's rain splashes and sound, from the streaks where rain is reaching the ground. */
    private static void splash(ClientLevel level, Minecraft mc, Vec3 cam, long tick) {
        ParticleStatus particles = mc.options.particles().get();
        RandomSource random = RandomSource.create(tick * 312987231L);
        BlockPos camPos = BlockPos.containing(cam);
        int attempts = particles == ParticleStatus.DECREASED ? 50 : 100;
        BlockPos sound = null;
        double soundStrength = 0;
        for (int j = 0; j < attempts; j++) {
            int i = camPos.getX() + random.nextInt(21) - 10;
            int k = camPos.getZ() + random.nextInt(21) - 10;
            RainColumn c = COLUMNS.get(key(i, k));
            if (c == null || !c.landing() || random.nextFloat() > Math.min(1, c.strength * c.strength * 1.5)) {
                continue;
            }
            int groundY = (int) Math.round(c.ground);
            if (groundY > camPos.getY() + 10 || groundY < camPos.getY() - 10 || groundY <= level.getMinBuildHeight()) {
                continue;
            }
            BlockPos below = new BlockPos(Mth.floor(c.x), groundY - 1, Mth.floor(c.z));
            sound = below;
            soundStrength = c.strength;
            if (particles == ParticleStatus.MINIMAL) {
                break;
            }
            double d0 = random.nextDouble();
            double d1 = random.nextDouble();
            BlockState state = level.getBlockState(below);
            FluidState fluid = level.getFluidState(below);
            double surface = Math.max(state.getCollisionShape(level, below).max(Direction.Axis.Y, d0, d1),
                    fluid.getHeight(level, below));
            ParticleOptions type = !fluid.is(FluidTags.LAVA) && !state.is(Blocks.MAGMA_BLOCK)
                    && !CampfireBlock.isLitCampfire(state) ? ParticleTypes.RAIN : ParticleTypes.SMOKE;
            level.addParticle(type, below.getX() + d0, below.getY() + surface, below.getZ() + d1, 0, 0, 0);
        }
        if (sound != null && random.nextInt(3) < rainSoundTime++) {
            rainSoundTime = 0;
            float loud = (float) (0.3 + 0.7 * Math.min(1, soundStrength * 1.3));
            if (sound.getY() > camPos.getY() + 1
                    && level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, camPos).getY() > camPos.getY()) {
                level.playLocalSound(sound, SoundEvents.WEATHER_RAIN_ABOVE, SoundSource.WEATHER, 0.1f * loud, 0.5f, false);
            } else {
                level.playLocalSound(sound, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.2f * loud, 1.0f, false);
            }
        }
    }

    public static void clear() {
        COLUMNS.clear();
        pivot = Double.NaN;
        prevPivot = Double.NaN;
    }

    /** Draws the rain and snow around the camera. Returns whether anything was drawn. */
    public static boolean render(ClientLevel level, LightTexture lightTexture, float partialTick, double camX,
                                 double camY, double camZ) {
        if (COLUMNS.isEmpty() || level == null || Double.isNaN(pivot)) {
            return false;
        }
        double pivotY = Double.isNaN(prevPivot) ? pivot : prevPivot + (pivot - prevPivot) * partialTick;
        buildShapes(FRAME_SHAPES, pivotY);
        lightTexture.turnOnLightLayer();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(Minecraft.useShaderTransparency());
        RenderSystem.setShader(GameRenderer::getParticleShader);
        boolean drew = false;
        try {
            drew |= pass(level, false, partialTick, pivotY, camX, camY, camZ);
            drew |= pass(level, true, partialTick, pivotY, camX, camY, camZ);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
            lightTexture.turnOffLightLayer();
        }
        return drew;
    }

    /** One layer: the rain streaks, or the snow ones. */
    private static boolean pass(ClientLevel level, boolean snowPass, float partialTick, double pivotY, double camX,
                                double camY, double camZ) {
        int r = radius();
        double top = pivotY + UP;
        double bottom = pivotY - DOWN;
        long gameTime = level.getGameTime();
        float ticks = gameTime + partialTick;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BufferBuilder buffer = null;
        for (Long2ObjectMap.Entry<RainColumn> e : COLUMNS.long2ObjectEntrySet()) {
            RainColumn c = e.getValue();
            if (c.snow != snowPass) {
                continue;
            }
            if (!c.active && (Double.isInfinite(c.prevTail) || c.prevTail <= c.prevHead)) {
                // Dry, or the last drops landed last tick.
                continue;
            }
            double lower = Math.max(Math.max(c.headAt(partialTick), c.ground), bottom);
            double upper = Math.min(c.tailAt(partialTick), top);
            if (upper - lower < 0.25) {
                continue;
            }
            int x = (int) (e.getLongKey() >> 32);
            int z = (int) e.getLongKey();
            // Where the streak crosses the pivot height.
            double px = x + 0.5;
            double pz = z + 0.5;
            double mx = px - camX;
            double mz = pz - camZ;
            double distance = Math.sqrt(mx * mx + mz * mz);
            if (distance > r) {
                continue;
            }
            double s = c.strength;
            long seed = (long) x * x * 3121 + x * 45238971L ^ (long) z * z * 418711 + z * 13761L;
            double h = ((seed * 0x9E3779B97F4A7C15L) >>> 40) / (double) (1L << 24);
            double keep = 0.3 + 0.7 * Math.min(1, s * 1.5);
            if (distance > NEAR) {
                keep *= 0.5;
            }
            if (h > keep) {
                continue;
            }
            StreakShape shape = FRAME_SHAPES[kind(snowPass, s)];
            if (buffer == null) {
                RenderSystem.setShaderTexture(0, snowPass ? SNOW : RAIN);
                buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            }
            double scale = distance > 0.001 ? 0.5 / distance : 0;
            double quadX = -mz * scale;
            double quadZ = mx * scale;
            double edge = distance / r;
            float fade = (float) ((1 - edge * edge) * (snowPass ? 0.3 : 0.5) + 0.5) * (float) (1 - edge * edge * edge);
            float alpha = Mth.clamp(fade * (0.35f + 0.65f * (float) Math.min(1, s)), 0, 1) * (snowPass ? 0.86f : 1f);
            pos.set(Mth.floor(px + shape.x(lower)), Mth.floor(lower), Mth.floor(pz + shape.z(lower)));
            int light = LevelRenderer.getLightColor(level, pos);
            float vOffset;
            float uOffset = 0;
            if (snowPass) {
                vOffset = -(ticks % 512 + partialTick) / 512f;
                uOffset = (float) (h * 7.3 % 1);
            } else {
                int offset = (int) (gameTime + (long) x * x * 3121L + x * 45238971L + (long) z * z * 418711L
                        + z * 13761L) & 31;
                vOffset = -(offset + partialTick) / 32f * (3f + (float) h);
            }
            // The texture runs down the streak as vanilla's does: v = (lower + upper - y) / 4, plus the scroll.
            double vBase = (lower + upper) * 0.25 + vOffset;
            if (shape.straight()) {
                segment(buffer, shape, px, pz, lower, upper, camX, camY, camZ, quadX, quadZ, vBase, uOffset, alpha,
                        light);
            } else {
                double from = lower;
                for (int j = 0; j < shape.nodes() && from < upper; j++) {
                    double y = shape.nodeY(j);
                    if (y <= from) {
                        continue;
                    }
                    double to = Math.min(y, upper);
                    segment(buffer, shape, px, pz, from, to, camX, camY, camZ, quadX, quadZ, vBase, uOffset, alpha,
                            light);
                    from = to;
                }
                if (from < upper) {
                    segment(buffer, shape, px, pz, from, upper, camX, camY, camZ, quadX, quadZ, vBase, uOffset, alpha,
                            light);
                }
            }
        }
        if (buffer == null) {
            return false;
        }
        MeshData mesh = buffer.build();
        if (mesh == null) {
            return false;
        }
        BufferUploader.drawWithShader(mesh);
        return true;
    }

    /** One straight piece of a streak, from world height {@code y0} up to {@code y1}. */
    private static void segment(BufferBuilder buffer, StreakShape shape, double px, double pz, double y0, double y1,
                                double camX, double camY, double camZ, double quadX, double quadZ, double vBase,
                                float uOffset, float alpha, int light) {
        double bx = px + shape.x(y0) - camX;
        double bz = pz + shape.z(y0) - camZ;
        double tx = px + shape.x(y1) - camX;
        double tz = pz + shape.z(y1) - camZ;
        quad(buffer, bx, bz, y0 - camY, tx, tz, y1 - camY, quadX, quadZ, (float) (vBase - y1 * 0.25),
                (float) (vBase - y0 * 0.25), uOffset, alpha, light);
    }

    private static void quad(BufferBuilder buffer, double bx, double bz, double by, double tx, double tz, double ty,
                             double quadX, double quadZ, float vTop, float vBottom, float uOffset, float alpha,
                             int light) {
        buffer.addVertex((float) (tx - quadX), (float) ty, (float) (tz - quadZ)).setUv(uOffset, vTop)
                .setColor(1f, 1f, 1f, alpha).setLight(light);
        buffer.addVertex((float) (tx + quadX), (float) ty, (float) (tz + quadZ)).setUv(1f + uOffset, vTop)
                .setColor(1f, 1f, 1f, alpha).setLight(light);
        buffer.addVertex((float) (bx + quadX), (float) by, (float) (bz + quadZ)).setUv(1f + uOffset, vBottom)
                .setColor(1f, 1f, 1f, alpha).setLight(light);
        buffer.addVertex((float) (bx - quadX), (float) by, (float) (bz - quadZ)).setUv(uOffset, vBottom)
                .setColor(1f, 1f, 1f, alpha).setLight(light);
    }

    private LocalRainRenderer() {
    }
}
