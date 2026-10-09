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
import dev.brights0ng.enginesandempires.weather.rain.Precip;
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
 * Streaks are spaced one per block <b>in the air</b>, within {@link #RADIUS} blocks (12 on fast graphics), and drawn
 * from {@link #DOWN} below to {@link #UP} above the pivot (the camera's height, eased a little and never more than
 * {@link #PIVOT_LAG} behind). Each is traced down along its lean to where it meets the ground ({@link StreakShape#land});
 * whether it rains, and how hard, comes from there, so it matches gameplay. Spacing them on the ground instead made
 * slopes along the wind look twice as wet as flat ground. Past {@link #NEAR} blocks half as many are drawn, fading out
 * toward the edge.
 *
 * <h2>Lean</h2>
 * The lean follows the wind averaged over 15 s, changing slowly, and a gust bends only rain that starts falling after
 * it ({@code RainSlant}, {@link StreakShape}). Streaks pivot at eye level, so a change swings their tops and bottoms
 * half as far each.
 *
 * <h2>Streaks stay put in the world (2026-10-08)</h2>
 * Every tick each shape is rebuilt and {@link StreakShape#pin pinned} at eye level to where last tick's shape was, so
 * the camera moving (jumping, an airship climbing, a long fall) never moves a leaning streak sideways: the streaks are
 * fixed slanted lines the camera moves through. Only a change of lean moves them, swinging about eye level. The shift
 * is interpolated between ticks with the pivot, so frames match. (Before, offsets were measured from an anchor height
 * eased after the camera over ~10 s, which hid jumps but slid the rain sideways after any fast climb or drop.)
 *
 * <h2>Rain that is already falling keeps falling</h2>
 * Each streak has a {@link RainColumn}: new rain arrives from the top at the fall speed, stopping rain drains out of
 * the bottom, strength eases. Streaks are re-sampled every {@link #RESAMPLE} ticks, staggered, and re-traced every
 * other tick.
 *
 * <h2>Splashes and sound</h2>
 * Only where rain (not snow) is reaching the ground, more for harder rain; snow is silent. Sleet and hail bounce, with
 * hits on whatever they land on, a hiss or roar, and drumming on the roof over a sheltered player
 * ({@link PrecipSounds}, 2026-10-08).
 *
 * <h2>Texture</h2>
 * Pinned to world height (2026-10-08): the drops' speed doesn't change when the camera moves up or down.
 *
 * <h2>No visible pattern (2026-10-09, Bright)</h2>
 * Each streak is set off the block grid by a small random amount and leans by its own slight random angle (about its
 * landing height, so it still lands where the weather says), so the streaks don't line up in rows, seen across or
 * along the wind. Each snow streak falls at its own speed around snow's real fall speed (1.2 m/s, from 0.75x to
 * 1.25x), from its own phase, with a gentle sideways sway, so the flakes don't line up in bands either.
 *
 * <h2>Rain colour (2026-10-09, Bright)</h2>
 * Rain uses its own texture (made in code, {@link #paleRain}): thin pale blue-grey drops, more see-through than
 * vanilla's bright blue.
 */
public final class LocalRainRenderer {

    private static final ResourceLocation RAIN = ResourceLocation.withDefaultNamespace("textures/environment/rain.png");
    private static final ResourceLocation SNOW = ResourceLocation.withDefaultNamespace("textures/environment/snow.png");
    private static final ResourceLocation PALE_RAIN = ResourceLocation.fromNamespaceAndPath(
            dev.brights0ng.enginesandempires.EnginesAndEmpiresMod.MODID, "dynamic/pale_rain");
    private static boolean paleRainReady;
    /** The most a streak is set off its block's centre, blocks. */
    static final double JITTER = 0.35;
    /** The most a streak leans by its own, blocks sideways per block down (about 2 degrees). */
    static final double TILT = 0.035;
    /** How see-through rain is, against vanilla's (1 = as opaque). */
    static final float RAIN_ALPHA = 0.7f;

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
    /** The pivot (the drawn band's middle) follows the camera's height over about this many ticks (a jump doesn't bob it). */
    static final double PIVOT_EASE_TICKS = 20;
    /**
     * The most the pivot may trail the camera (blocks), so a fast climb or fall still has rain well above and below
     * eye level. The streaks don't depend on the pivot, so this moves nothing sideways.
     */
    static final double PIVOT_LAG = 4;
    /** A camera this far from the pivot in one tick (a teleport) moves it at once, without interpolating. */
    static final double PIVOT_SNAP = 16;

    /** Streak shapes by kind: snow, rain in five strength bands, sleet, hail. */
    private static final int KINDS = 8;
    private static final int SLEET_KIND = 6;
    private static final int HAIL_KIND = 7;
    private static final StreakShape[] TICK_SHAPES = shapes();
    private static final StreakShape[] FRAME_SHAPES = shapes();
    /** Last tick's shift of each tick shape, for interpolating the frame shapes'. */
    private static final double[] PREV_SHIFT_X = new double[KINDS];
    private static final double[] PREV_SHIFT_Z = new double[KINDS];
    /** Where each tick shape put a streak at eye level, before rebuilding it. */
    private static final double[] EYE_X = new double[KINDS];
    private static final double[] EYE_Z = new double[KINDS];
    /** Whether the tick shapes hold last tick's streaks (to pin the new ones to); false starts them fresh. */
    private static boolean pinned;

    private static final Long2ObjectOpenHashMap<RainColumn> COLUMNS = new Long2ObjectOpenHashMap<>();
    private static final PrecipSounds SOUNDS = new PrecipSounds();
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

    private static int kind(Precip look, double strength) {
        return switch (look) {
            case SNOW -> 0;
            case SLEET -> SLEET_KIND;
            case HAIL -> HAIL_KIND;
            default -> 1 + Math.min(4, Math.max(0, (int) (strength * 5)));
        };
    }

    private static void buildShapes(StreakShape[] shapes, double pivotY) {
        double top = pivotY + UP;
        shapes[0].build(ClientWeather.slant(), Precip.SNOW, 0, pivotY, top);
        for (int i = 1; i <= 5; i++) {
            shapes[i].build(ClientWeather.slant(), Precip.RAIN, (i - 0.5) / 5, pivotY, top);
        }
        shapes[SLEET_KIND].build(ClientWeather.slant(), Precip.SLEET, 0.5, pivotY, top);
        shapes[HAIL_KIND].build(ClientWeather.slant(), Precip.HAIL, 0.8, pivotY, top);
    }

    /**
     * Rebuilds the tick shapes for the pivot now and pins each at eye height {@code eyeY} to where last tick's was, so
     * no streak moves sideways there ({@link StreakShape#pin}). {@code snapped}: the pivot jumped (no interpolating).
     */
    private static void rebuildTickShapes(double eyeY, boolean snapped) {
        for (int k = 0; k < KINDS; k++) {
            EYE_X[k] = TICK_SHAPES[k].x(eyeY);
            EYE_Z[k] = TICK_SHAPES[k].z(eyeY);
            PREV_SHIFT_X[k] = TICK_SHAPES[k].shiftX();
            PREV_SHIFT_Z[k] = TICK_SHAPES[k].shiftZ();
        }
        buildShapes(TICK_SHAPES, pivot);
        for (int k = 0; k < KINDS; k++) {
            StreakShape s = TICK_SHAPES[k];
            if (pinned) {
                s.pin(eyeY, EYE_X[k], EYE_Z[k]);
            } else {
                s.setShift(0, 0);
            }
            if (!pinned || snapped) {
                PREV_SHIFT_X[k] = s.shiftX();
                PREV_SHIFT_Z[k] = s.shiftZ();
            }
        }
        pinned = true;
    }

    /** How what falls looks in streak column (i, k) (mixed: rain or wet snow by column). */
    private static Precip look(LocalWeather.Here h, int i, int k, Precip otherwise) {
        return h.precip() == null ? otherwise : h.precip().look(i, k);
    }

    private static int radius() {
        return Minecraft.useFancyGraphics() ? RADIUS : RADIUS_FAST;
    }

    /** Samples, traces and steps the streaks around the camera, and makes splashes. Once per client tick. */
    public static void tick(ClientLevel level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || !LocalWeather.enabled(level)) {
            COLUMNS.clear();
            pinned = false;
            return;
        }
        ClientWeather.tick();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        prevPivot = pivot;
        boolean snapped = false;
        if (Double.isNaN(pivot) || Math.abs(cam.y - pivot) > PIVOT_SNAP) {
            pivot = cam.y;
            prevPivot = pivot;
            snapped = true;
        } else {
            pivot += (cam.y - pivot) * (1 - Math.exp(-1 / PIVOT_EASE_TICKS));
            pivot = Mth.clamp(pivot, cam.y - PIVOT_LAG, cam.y + PIVOT_LAG);
        }
        boolean clouds = !LocalWeather.clouds(level).isEmpty();
        if (!clouds && COLUMNS.isEmpty()) {
            // Nothing drawn: the next streaks can start fresh.
            pinned = false;
            return;
        }
        long tick = level.getGameTime();
        double top = pivot + UP;
        rebuildTickShapes(cam.y, snapped);
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
        // Streak (i, k) runs through (i + 0.5 + x(y), y, k + 0.5 + z(y)): centre the circle on the ones passing the camera.
        StreakShape rain = TICK_SHAPES[kind(Precip.RAIN, 0.6)];
        int cx = Mth.floor(cam.x - rain.x(cam.y));
        int cz = Mth.floor(cam.z - rain.z(cam.y));
        double top = pivot + UP;
        double floor = pivot - MAX_FALL;
        // Ships (phase 5d): rain stops on their decks and roofs. The ships over the drawn circle are found once a tick.
        int reach = r + 8;
        ships = dev.brights0ng.enginesandempires.weather.ships.ShipCover.area(level, cx - reach, cz - reach,
                cx + reach + 1, cz + reach + 1);
        dev.brights0ng.enginesandempires.weather.ships.ShipCover.Area over = ships;
        StreakShape.Ground ground = over.isEmpty()
                ? (x, z) -> level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)
                : (x, z) -> Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), over.top(x, z));
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
                    // Traced as rain first; other kinds lean differently, so traced again for them.
                    double y = TICK_SHAPES[kind(Precip.RAIN, 0.6)].land(px, pz, top, floor, ground, LAND);
                    LocalWeather.Here h = LocalWeather.at(level, LAND[0], y + 0.5, LAND[1]);
                    Precip look = look(h, i, k, Precip.RAIN);
                    if (kind(look, 0.6) != kind(Precip.RAIN, 0.6)) {
                        y = TICK_SHAPES[kind(look, 0.6)].land(px, pz, top, floor, ground, LAND);
                        h = LocalWeather.at(level, LAND[0], y + 0.5, LAND[1]);
                        look = look(h, i, k, look);
                    }
                    c = RainColumn.first(h.falling() ? h.strength() : 0, look, y, tick);
                    c.x = LAND[0];
                    c.z = LAND[1];
                    COLUMNS.put(key, c);
                } else {
                    if (((i + k + tick) & 1) == 0) {
                        c.ground = TICK_SHAPES[kind(c.look, c.strength)].land(px, pz, top, floor, ground, LAND);
                        c.x = LAND[0];
                        c.z = LAND[1];
                    }
                    if (tick - c.sampledTick >= RESAMPLE && Math.floorMod(i * 31 + k * 17 + tick, RESAMPLE) == 0) {
                        LocalWeather.Here h = LocalWeather.at(level, c.x, c.ground + 0.5, c.z);
                        c.target = h.falling() ? h.strength() : 0;
                        c.look = look(h, i, k, c.look);
                        c.sampledTick = tick;
                    }
                }
                c.seenTick = tick;
            }
        }
    }

    /**
     * Vanilla's rain splashes and sound, from the streaks where rain is reaching the ground; sleet and hail bounce off
     * it as small ice pellets (phase 5a), with a tick or clatter.
     */
    /** The ships over the drawn circle this tick (phase 5d); null before the first. */
    private static dev.brights0ng.enginesandempires.weather.ships.ShipCover.Area ships;

    /**
     * The top of what roofs the camera's column: the world's (heightmap) or a ship's over it, whichever is higher, as
     * the block above it.
     */
    private static BlockPos roofOver(ClientLevel level, Vec3 cam, BlockPos camPos) {
        BlockPos top = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, camPos);
        if (ships != null && !ships.isEmpty()) {
            double ship = ships.topAbove(cam.x, cam.z, cam.y + 1);
            if (ship > top.getY()) {
                return BlockPos.containing(cam.x, ship, cam.z);
            }
        }
        return top;
    }

    private static void splash(ClientLevel level, Minecraft mc, Vec3 cam, long tick) {
        ParticleStatus particles = mc.options.particles().get();
        RandomSource random = RandomSource.create(tick * 312987231L);
        BlockPos camPos = BlockPos.containing(cam);
        // Look up the streaks landing around the camera (keyed as in visit).
        StreakShape rain = TICK_SHAPES[kind(Precip.RAIN, 0.6)];
        int baseX = Mth.floor(cam.x - rain.x(cam.y - 1));
        int baseZ = Mth.floor(cam.z - rain.z(cam.y - 1));
        int attempts = particles == ParticleStatus.DECREASED ? 50 : 100;
        BlockPos sound = null;
        double soundStrength = 0;
        BlockPos iceAt = null;
        Precip iceKind = null;
        double iceStrength = 0;
        SOUNDS.begin();
        for (int j = 0; j < attempts; j++) {
            int i = baseX + random.nextInt(21) - 10;
            int k = baseZ + random.nextInt(21) - 10;
            RainColumn c = COLUMNS.get(key(i, k));
            if (c == null || !c.reaching() || c.look == Precip.SNOW
                    || random.nextFloat() > Math.min(1, c.strength * c.strength * 1.5)) {
                continue;
            }
            int groundY = (int) Math.round(c.ground);
            if (groundY > camPos.getY() + 10 || groundY < camPos.getY() - 10 || groundY <= level.getMinBuildHeight()) {
                continue;
            }
            BlockPos below = new BlockPos(Mth.floor(c.x), groundY - 1, Mth.floor(c.z));
            if (c.look == Precip.SLEET || c.look == Precip.HAIL) {
                bounce(level, mc, random, c, below, particles);
                if (iceKind != Precip.HAIL || c.look == Precip.HAIL) {
                    iceAt = below;
                    iceKind = c.look;
                    iceStrength = Math.max(iceStrength, c.strength);
                }
                continue;
            }
            sound = below;
            soundStrength = c.strength;
            BlockState rainOn = level.getBlockState(below);
            FluidState rainFluid = level.getFluidState(below);
            SOUNDS.rainAccent(level, random, c.look, c.strength, c.x, groundY, c.z, below, rainOn, rainFluid);
            if (particles == ParticleStatus.MINIMAL) {
                break;
            }
            double d0 = random.nextDouble();
            double d1 = random.nextDouble();
            double surface = Math.max(rainOn.getCollisionShape(level, below).max(Direction.Axis.Y, d0, d1),
                    rainFluid.getHeight(level, below));
            ParticleOptions type = !rainFluid.is(FluidTags.LAVA) && !rainOn.is(Blocks.MAGMA_BLOCK)
                    && !CampfireBlock.isLitCampfire(rainOn) ? ParticleTypes.RAIN : ParticleTypes.SMOKE;
            // On a ship's deck the world has nothing there (its blocks are in Sable's plot): splash where it landed.
            double splashY = surface <= 0 && rainFluid.isEmpty() ? c.ground : below.getY() + surface;
            level.addParticle(type, below.getX() + d0, splashY, below.getZ() + d1, 0, 0, 0);
        }
        if (iceAt != null) {
            SOUNDS.bed(level, random, iceKind, iceStrength, iceAt);
        }
        // Under a roof, what falls on it drums overhead.
        BlockPos roofTop = roofOver(level, cam, camPos);
        if (roofTop.getY() > camPos.getY() + 1) {
            RainColumn over = COLUMNS.get(key(Mth.floor(cam.x - rain.x(roofTop.getY())),
                    Mth.floor(cam.z - rain.z(roofTop.getY()))));
            if (over != null && over.reaching()) {
                SOUNDS.roof(level, random, over.look, over.strength, roofTop.below());
            }
        }
        if (sound != null && random.nextInt(3) < rainSoundTime++) {
            rainSoundTime = 0;
            float loud = (float) (0.3 + 0.7 * Math.min(1, soundStrength * 1.3));
            if (sound.getY() > camPos.getY() + 1
                    && roofTop.getY() > camPos.getY()) {
                level.playLocalSound(sound, SoundEvents.WEATHER_RAIN_ABOVE, SoundSource.WEATHER, 0.1f * loud, 0.5f, false);
            } else {
                level.playLocalSound(sound, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.2f * loud, 1.0f, false);
            }
        }
    }

    /** An ice pellet or hailstone bouncing off the ground, with its sound ({@link PrecipSounds}). */
    private static void bounce(ClientLevel level, Minecraft mc, RandomSource random, RainColumn c, BlockPos below,
                               ParticleStatus particles) {
        boolean hail = c.look == Precip.HAIL;
        double d0 = random.nextDouble();
        double d1 = random.nextDouble();
        BlockState state = level.getBlockState(below);
        FluidState fluid = level.getFluidState(below);
        double surface = Math.max(state.getCollisionShape(level, below).max(Direction.Axis.Y, d0, d1),
                fluid.getHeight(level, below));
        double x = below.getX() + d0, y = below.getY() + surface + 0.05, z = below.getZ() + d1;
        if (particles != ParticleStatus.MINIMAL && fluid.isEmpty()) {
            var p = mc.particleEngine.createParticle(ParticleTypes.ITEM_SNOWBALL, x, y, z, 0, 0, 0);
            if (p != null) {
                double up = hail ? 0.12 + 0.12 * random.nextDouble() : 0.05 + 0.05 * random.nextDouble();
                p.setParticleSpeed((random.nextDouble() - 0.5) * 0.08, up, (random.nextDouble() - 0.5) * 0.08);
                p.scale(hail ? 0.9f : 0.45f);
                p.setLifetime(hail ? 14 + random.nextInt(8) : 6 + random.nextInt(4));
            }
        }
        if (SOUNDS.wantsHit(c.look, c.strength, random)) {
            SOUNDS.hit(level, random, c.look, c.strength, x, y, z, below, state, fluid);
        }
    }

    public static void clear() {
        COLUMNS.clear();
        pivot = Double.NaN;
        prevPivot = Double.NaN;
        pinned = false;
    }

    /** Draws the rain and snow around the camera. Returns whether anything was drawn. */
    public static boolean render(ClientLevel level, LightTexture lightTexture, float partialTick, double camX,
                                 double camY, double camZ) {
        if (COLUMNS.isEmpty() || level == null || Double.isNaN(pivot) || !pinned) {
            return false;
        }
        double pivotY = Double.isNaN(prevPivot) ? pivot : prevPivot + (pivot - prevPivot) * partialTick;
        buildShapes(FRAME_SHAPES, pivotY);
        // The shift goes with the pivot: interpolated together, the streaks stay put between ticks too.
        for (int k = 0; k < KINDS; k++) {
            FRAME_SHAPES[k].setShift(Mth.lerp(partialTick, PREV_SHIFT_X[k], TICK_SHAPES[k].shiftX()),
                    Mth.lerp(partialTick, PREV_SHIFT_Z[k], TICK_SHAPES[k].shiftZ()));
        }
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
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BufferBuilder buffer = null;
        for (Long2ObjectMap.Entry<RainColumn> e : COLUMNS.long2ObjectEntrySet()) {
            RainColumn c = e.getValue();
            // Rain and freezing rain with the rain texture; snow, sleet and hail with the snow one.
            if (c.look.frozen != snowPass) {
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
            long seed = (long) x * x * 3121 + x * 45238971L ^ (long) z * z * 418711 + z * 13761L;
            double h = ((seed * 0x9E3779B97F4A7C15L) >>> 40) / (double) (1L << 24);
            // Its own small offset off the grid and its own slight lean (pivoting where it lands), so streaks don't
            // line up in rows.
            Jitter jit = Jitter.of(seed, c.ground);
            // The streak's spot, and how far it is from the camera at eye level.
            double px = x + 0.5;
            double pz = z + 0.5;
            StreakShape shape = FRAME_SHAPES[kind(c.look, c.strength)];
            double mx = px + shape.x(camY) + jit.x(camY) - camX;
            double mz = pz + shape.z(camY) + jit.z(camY) - camZ;
            double distance = Math.sqrt(mx * mx + mz * mz);
            if (distance > r) {
                continue;
            }
            double s = c.strength;
            double keep = 0.3 + 0.7 * Math.min(1, s * 1.5);
            if (distance > NEAR) {
                keep *= 0.5;
            }
            if (c.look == Precip.HAIL) {
                // Hailstones are fewer and further between than drops.
                keep *= 0.5;
            }
            if (h > keep) {
                continue;
            }
            Precip look = c.look;
            if (buffer == null) {
                RenderSystem.setShaderTexture(0, snowPass ? SNOW : paleRain());
                buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            }
            // Sleet a little narrower than snow (small pellets), hail as wide (bigger stones on a coarser texture).
            double width = look == Precip.SLEET ? 0.35 : 0.5;
            double scale = distance > 0.001 ? width / distance : 0;
            double quadX = -mz * scale;
            double quadZ = mx * scale;
            double edge = distance / r;
            float fade = (float) ((1 - edge * edge) * (snowPass ? 0.3 : 0.5) + 0.5) * (float) (1 - edge * edge * edge);
            float alpha = Mth.clamp(fade * (0.35f + 0.65f * (float) Math.min(1, s)), 0, 1) * (snowPass ? 0.86f : 1f);
            if (!snowPass) {
                alpha *= RAIN_ALPHA;
            }
            pos.set(Mth.floor(px + shape.x(lower) + jit.x(lower)), Mth.floor(lower),
                    Mth.floor(pz + shape.z(lower) + jit.z(lower)));
            int light = LevelRenderer.getLightColor(level, pos);
            // The scroll: how far the texture has fallen, in texture units. Worked out in double from the tick
            // count wrapped to a whole number of texture repeats (a float game time loses the partial tick), so it
            // moves smoothly and never jumps.
            double vOffset;
            float uOffset = 0;
            float vScale = 1;
            float uSpan = 1;
            float red = 1, green = 1, blue = 1;
            if (look == Precip.SLEET) {
                // Pellets falling fast: the snow texture scrolled at the fall speed.
                vOffset = -((gameTime % 64) + partialTick) / 64.0 * 8;
                uOffset = (float) (h * 7.3 % 1);
                red = 0.92f;
                green = 0.95f;
            } else if (look == Precip.HAIL) {
                // Fewer, bigger stones: half the texture stretched over the streak, scrolled fast.
                vOffset = -((gameTime % 32) + partialTick) / 32.0 * 8;
                uOffset = (float) (h * 7.3 % 1);
                vScale = 0.5f;
                uSpan = 0.5f;
            } else if (snowPass) {
                // Snow's real fall speed, varied per streak, from its own phase (2026-10-09: it crawled at about a
                // sixth of a block a second, and every streak's flakes lined up in bands). Texture units per tick:
                // blocks a second / 20 / 4 blocks per unit.
                double speed = dev.brights0ng.enginesandempires.weather.rain.RainModel.SNOW_FALL * (0.75 + 0.5 * jit.u());
                long phase = (gameTime + (seed & 0xFFFFL)) & 131071;
                vOffset = -(phase + partialTick) * speed / 80.0;
                // A gentle sideways sway, each streak its own.
                uOffset = (float) (h * 7.3 % 1 + 0.04 * Math.sin((gameTime + partialTick) * (0.02 + 0.03 * jit.v())
                        + h * 40));
            } else {
                // Each streak at its own speed (3-4 repeats a second and a half): wrapped only every ~2 hours.
                long offset = (gameTime + (long) x * x * 3121L + x * 45238971L + (long) z * z * 418711L
                        + z * 13761L) & 131071;
                vOffset = -(offset + partialTick) / 32.0 * (3 + h);
                if (look == Precip.FREEZING_RAIN) {
                    // A little glassier: a cold blue-white sheen.
                    red = 0.82f;
                    green = 0.92f;
                }
            }
            // The texture is pinned to the world: v = -y / 4 plus the scroll, so drops fall at their own speed
            // whatever the camera does. (Vanilla's v = (lower + upper - y) / 4 moves with the streak's ends, which
            // follow the camera's height: jumping threw the drops up and let them settle back.) Kept small: v
            // repeats every unit, so a whole number of units is taken off.
            double vBase = vOffset - Math.floor(vOffset) + Math.floor((lower + upper) * 0.125 * vScale) * 2;
            Tint tint = new Tint(red, green, blue, alpha, uSpan, vScale);
            if (shape.straight()) {
                segment(buffer, shape, jit, px, pz, lower, upper, camX, camY, camZ, quadX, quadZ, vBase, uOffset, tint,
                        light);
            } else {
                double from = lower;
                for (int j = 0; j < shape.nodes() && from < upper; j++) {
                    double y = shape.nodeY(j);
                    if (y <= from) {
                        continue;
                    }
                    double to = Math.min(y, upper);
                    segment(buffer, shape, jit, px, pz, from, to, camX, camY, camZ, quadX, quadZ, vBase, uOffset, tint,
                            light);
                    from = to;
                }
                if (from < upper) {
                    segment(buffer, shape, jit, px, pz, from, upper, camX, camY, camZ, quadX, quadZ, vBase, uOffset,
                            tint, light);
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

    /** A streak's colour and opacity, and how much of the texture it spans (u across, v per block down). */
    private record Tint(float r, float g, float b, float a, float uSpan, float vScale) {
    }

    /**
     * A streak's own offset off its block's centre ({@code jx, jz}) and lean ({@code tx, tz}, blocks sideways per
     * block, pivoting at {@code pivotY}, where it lands), and two more random numbers for snow, all from its seed.
     */
    record Jitter(double jx, double jz, double tx, double tz, double pivotY, double u, double v) {

        static Jitter of(long seed, double pivotY) {
            long m = seed * 0xD1B54A32D192ED03L + 0x9E3779B97F4A7C15L;
            double[] r = new double[6];
            for (int i = 0; i < r.length; i++) {
                m ^= m >>> 31;
                m *= 0x94D049BB133111EBL;
                m ^= m >>> 29;
                r[i] = (m >>> 11) / (double) (1L << 53);
            }
            double pivot = Double.isFinite(pivotY) ? pivotY : 0;
            return new Jitter((r[0] - 0.5) * 2 * JITTER, (r[1] - 0.5) * 2 * JITTER, (r[2] - 0.5) * 2 * TILT,
                    (r[3] - 0.5) * 2 * TILT, pivot, r[4], r[5]);
        }

        double x(double y) {
            return jx + tx * (y - pivotY);
        }

        double z(double y) {
            return jz + tz * (y - pivotY);
        }
    }

    /**
     * The rain texture (2026-10-09, Bright: vanilla's is an unrealistic bright blue): thin, pale blue-grey drops, more
     * see-through, brightest at their lower end, made once in code (64 x 256, the size of vanilla's).
     */
    private static ResourceLocation paleRain() {
        if (!paleRainReady) {
            com.mojang.blaze3d.platform.NativeImage img = new com.mojang.blaze3d.platform.NativeImage(64, 256, true);
            img.fillRect(0, 0, 64, 256, 0);
            RandomSource r = RandomSource.create(0x5EEDL);
            int red = 196, green = 207, blue = 222;
            for (int d = 0; d < 80; d++) {
                int x = r.nextInt(64);
                int y0 = r.nextInt(256);
                int len = 8 + r.nextInt(14);
                double peak = 0.45 + 0.25 * r.nextDouble();
                for (int i = 0; i < len; i++) {
                    double t = (i + 1) / (double) len;
                    int a = (int) Math.round(255 * peak * (0.25 + 0.75 * t * t));
                    img.setPixelRGBA(x, (y0 + i) & 255, (a << 24) | (blue << 16) | (green << 8) | red);
                }
            }
            Minecraft.getInstance().getTextureManager().register(PALE_RAIN,
                    new net.minecraft.client.renderer.texture.DynamicTexture(img));
            paleRainReady = true;
        }
        return PALE_RAIN;
    }

    /** One straight piece of a streak, from world height {@code y0} up to {@code y1}. */
    private static void segment(BufferBuilder buffer, StreakShape shape, Jitter jit, double px, double pz, double y0,
                                double y1, double camX, double camY, double camZ, double quadX, double quadZ,
                                double vBase, float uOffset, Tint tint, int light) {
        double bx = px + shape.x(y0) + jit.x(y0) - camX;
        double bz = pz + shape.z(y0) + jit.z(y0) - camZ;
        double tx = px + shape.x(y1) + jit.x(y1) - camX;
        double tz = pz + shape.z(y1) + jit.z(y1) - camZ;
        quad(buffer, bx, bz, y0 - camY, tx, tz, y1 - camY, quadX, quadZ, (float) (vBase - y1 * 0.25 * tint.vScale()),
                (float) (vBase - y0 * 0.25 * tint.vScale()), uOffset, tint, light);
    }

    private static void quad(BufferBuilder buffer, double bx, double bz, double by, double tx, double tz, double ty,
                             double quadX, double quadZ, float vTop, float vBottom, float uOffset, Tint t,
                             int light) {
        float u1 = t.uSpan() + uOffset;
        buffer.addVertex((float) (tx - quadX), (float) ty, (float) (tz - quadZ)).setUv(uOffset, vTop)
                .setColor(t.r(), t.g(), t.b(), t.a()).setLight(light);
        buffer.addVertex((float) (tx + quadX), (float) ty, (float) (tz + quadZ)).setUv(u1, vTop)
                .setColor(t.r(), t.g(), t.b(), t.a()).setLight(light);
        buffer.addVertex((float) (bx + quadX), (float) by, (float) (bz + quadZ)).setUv(u1, vBottom)
                .setColor(t.r(), t.g(), t.b(), t.a()).setLight(light);
        buffer.addVertex((float) (bx - quadX), (float) by, (float) (bz - quadZ)).setUv(uOffset, vBottom)
                .setColor(t.r(), t.g(), t.b(), t.a()).setLight(light);
    }

    private LocalRainRenderer() {
    }
}
