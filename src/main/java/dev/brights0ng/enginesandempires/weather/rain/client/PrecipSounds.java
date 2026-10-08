package dev.brights0ng.enginesandempires.weather.rain.client;

import dev.brights0ng.enginesandempires.weather.rain.Precip;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * The sounds of what falls landing (2026-10-08, Bright: "much more precipitation sound effects, especially for hail and
 * sleet"), from vanilla's own sounds pitched and layered, so no new audio ships:
 *
 * <ul>
 *   <li><b>Hail:</b> many hits a tick (more in harder hail), each on the surface it lands on: the block's own hit sound
 *       pitched up (a knock on wood, a clang on metal, a click on stone, a rustle in leaves, a muffled thump on snow),
 *       a plink on water, and an icy crack (calcite) layered on hard ground; a low roar under it all; and drumming on
 *       the roof over a sheltered player.</li>
 *   <li><b>Sleet:</b> a fine, fast ticking on the same surfaces, much quieter, and a hiss (the rain sound pitched
 *       high); a patter on the roof.</li>
 *   <li><b>Rain:</b> vanilla's rain sound as before, with now and then a plink on water or a ping on metal.</li>
 *   <li><b>Freezing rain:</b> the rain, and now and then a faint icy tick as it glazes.</li>
 * </ul>
 * Snow stays silent.
 */
final class PrecipSounds {

    /** Hits a tick at most: hail, sleet, rain accents. */
    static final int HAIL_HITS = 8;
    static final int SLEET_HITS = 6;
    static final int RAIN_ACCENTS = 2;

    private int hits;
    private int accents;
    private int bedTime;

    /** Starts a tick's budget. */
    void begin() {
        hits = 0;
        accents = 0;
    }

    /** Whether another hit of {@code kind} at {@code strength} fits this tick. */
    boolean wantsHit(Precip kind, double strength, RandomSource random) {
        int max = kind == Precip.HAIL ? HAIL_HITS : SLEET_HITS;
        int budget = (int) Math.ceil(max * (0.35 + 0.65 * Math.min(1, strength)));
        return hits < budget && random.nextFloat() < (kind == Precip.HAIL ? 0.85f : 0.6f);
    }

    /** A hailstone or ice pellet landing at (x, y, z) on {@code state} / {@code fluid} at {@code pos}. */
    void hit(ClientLevel level, RandomSource random, Precip kind, double strength, double x, double y, double z,
             BlockPos pos, BlockState state, FluidState fluid) {
        hits++;
        boolean hail = kind == Precip.HAIL;
        float loud = (float) (0.4 + 0.6 * Math.min(1, strength));
        if (!fluid.isEmpty()) {
            if (fluid.is(FluidTags.WATER)) {
                play(level, x, y, z, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, (hail ? 0.5f : 0.2f) * loud,
                        (hail ? 1.0f : 1.6f) + random.nextFloat() * 0.4f);
            }
            return;
        }
        SoundType type = state.getSoundType(level, pos, null);
        SoundEvent surface = type.getHitSound();
        if (hail) {
            play(level, x, y, z, surface, 0.45f * loud, 1.3f + random.nextFloat() * 0.5f);
            if (hard(type) && random.nextFloat() < 0.6f) {
                play(level, x, y, z, SoundEvents.CALCITE_HIT, 0.3f * loud, 1.5f + random.nextFloat() * 0.5f);
            }
        } else {
            play(level, x, y, z, surface, 0.14f * loud, 1.8f + random.nextFloat() * 0.2f);
            if (hard(type) && random.nextFloat() < 0.3f) {
                play(level, x, y, z, SoundEvents.CALCITE_STEP, 0.1f * loud, 1.9f + random.nextFloat() * 0.1f);
            }
        }
    }

    /** Now and then, rain on water or metal, or freezing rain glazing. */
    void rainAccent(ClientLevel level, RandomSource random, Precip kind, double strength, double x, double y,
                    double z, BlockPos pos, BlockState state, FluidState fluid) {
        if (accents >= RAIN_ACCENTS || random.nextFloat() > 0.12f) {
            return;
        }
        float loud = (float) (0.4 + 0.6 * Math.min(1, strength));
        if (fluid.is(FluidTags.WATER)) {
            accents++;
            play(level, x, y, z, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, 0.18f * loud,
                    1.4f + random.nextFloat() * 0.5f);
        } else if (state.getSoundType(level, pos, null) == SoundType.METAL
                || state.getSoundType(level, pos, null) == SoundType.COPPER
                || state.getSoundType(level, pos, null) == SoundType.CHAIN) {
            accents++;
            play(level, x, y, z, SoundEvents.METAL_HIT, 0.12f * loud, 1.7f + random.nextFloat() * 0.3f);
        } else if (kind == Precip.FREEZING_RAIN && hardOrGlass(state, level, pos)) {
            accents++;
            play(level, x, y, z, SoundEvents.CALCITE_STEP, 0.08f * loud, 1.9f + random.nextFloat() * 0.1f);
        }
    }

    /**
     * Over a sheltered player: what falls drums on the roof ({@code roof}, the block over their head). Hail a few times
     * a tick, sleet a patter.
     */
    void roof(ClientLevel level, RandomSource random, Precip kind, double strength, BlockPos roof) {
        if (kind != Precip.HAIL && kind != Precip.SLEET) {
            return;
        }
        boolean hail = kind == Precip.HAIL;
        int n = hail ? 1 + random.nextInt(2 + (int) (strength * 2)) : (random.nextFloat() < 0.6f ? 1 : 0);
        BlockState state = level.getBlockState(roof);
        SoundEvent sound = state.getSoundType(level, roof, null).getHitSound();
        float loud = (float) (0.4 + 0.6 * Math.min(1, strength));
        for (int i = 0; i < n; i++) {
            double x = roof.getX() + random.nextDouble() * 5 - 2;
            double z = roof.getZ() + random.nextDouble() * 5 - 2;
            play(level, x, roof.getY() + 1, z, sound, (hail ? 0.35f : 0.12f) * loud,
                    (hail ? 1.1f : 1.7f) + random.nextFloat() * 0.4f);
        }
    }

    /** The bed under it all, every few ticks: a low roar for hail, a hiss for sleet. */
    void bed(ClientLevel level, RandomSource random, Precip kind, double strength, BlockPos at) {
        if (kind != Precip.HAIL && kind != Precip.SLEET) {
            return;
        }
        if (random.nextInt(3) >= bedTime++) {
            return;
        }
        bedTime = 0;
        float loud = (float) (0.3 + 0.7 * Math.min(1, strength * 1.3));
        if (kind == Precip.HAIL) {
            level.playLocalSound(at, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.35f * loud,
                    0.55f + random.nextFloat() * 0.1f, false);
        } else {
            level.playLocalSound(at, SoundEvents.WEATHER_RAIN, SoundSource.WEATHER, 0.2f * loud,
                    1.5f + random.nextFloat() * 0.2f, false);
        }
    }

    private static boolean hard(SoundType t) {
        return t == SoundType.STONE || t == SoundType.DEEPSLATE || t == SoundType.POLISHED_DEEPSLATE
                || t == SoundType.DEEPSLATE_BRICKS || t == SoundType.DEEPSLATE_TILES || t == SoundType.TUFF
                || t == SoundType.CALCITE || t == SoundType.GLASS || t == SoundType.METAL || t == SoundType.COPPER
                || t == SoundType.NETHER_BRICKS || t == SoundType.MUD_BRICKS || t == SoundType.GRAVEL
                || t == SoundType.WOOD || t == SoundType.BAMBOO_WOOD || t == SoundType.CHERRY_WOOD;
    }

    private static boolean hardOrGlass(BlockState state, ClientLevel level, BlockPos pos) {
        return hard(state.getSoundType(level, pos, null));
    }

    private static void play(ClientLevel level, double x, double y, double z, SoundEvent sound, float volume,
                             float pitch) {
        level.playLocalSound(x, y, z, sound, SoundSource.WEATHER, volume, Math.min(2f, pitch), false);
    }
}
