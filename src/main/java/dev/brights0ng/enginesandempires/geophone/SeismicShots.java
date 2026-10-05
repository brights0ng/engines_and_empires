package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.deep.Deep;
import dev.brights0ng.enginesandempires.frontier.deep.ShotSource;
import dev.brights0ng.enginesandempires.oregen.Realm;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Makes a vibration in the ground and lights up the geophones that hear it.
 *
 * <p>The vibration is not simulated wave by wave. When it is made, everything about it is worked out at once: which
 * deposits it reaches, and for each geophone that can hear one, when its echo arrives. Each geophone is then
 * told when to glow, and lights up by itself at that moment, so the delay between geophones is real, and
 * you can watch a pulse sweep across a field of them.
 *
 * <p>Nothing is done if no geophone is anywhere near: with nobody listening there is nothing to work out.
 * Finding the deposits runs off the main thread, so a big vibration does not stall the game.
 */
public final class SeismicShots {

    /** How far a vibration carries when the ground itself is struck. */
    public static final int HAMMER_RANGE = 16;

    /** How far it carries when a strike plate is struck. */
    public static final int PLATE_RANGE = 128;

    /** How far it carries when the mechanical thumper slams down on a strike plate. */
    public static final int MECHANICAL_RANGE = 512;

    // The combustive thumper's ranges (1024 for diesel, 768 for gasoline and biodiesel) live in CombustiveFiring.

    /** A hammer blow on a block: the sound and dust of the blow, and a vibration from the middle of the block. */
    public static void strike(ServerLevel level, BlockPos pos, BlockState struck, int range) {
        Vec3 centre = pos.getCenter();
        boolean heavy = range >= PLATE_RANGE;
        level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, heavy ? 0.9F : 0.6F, heavy ? 0.9F : 0.6F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, struck),
                centre.x, pos.getY() + 1.0, centre.z, heavy ? 14 : 8, 0.3, 0.05, 0.3, 0.05);
        fire(level, centre, range);
    }

    /**
     * The mechanical thumper slamming down onto the strike plate beneath it: a heavier sound than a hand blow, and a wide
     * spray of dust thrown out sideways along the ground rather than up, matching a slab impact rather than a swing.
     */
    public static void thump(ServerLevel level, BlockPos pos, BlockState plateState) {
        thump(level, pos, plateState, MECHANICAL_RANGE, ShotSource.MECHANICAL);
    }

    /**
     * A thumper's head landing on the strike plate beneath it, carrying as far as {@code range}. Besides the survey, the
     * shot sets off what lives below ({@link Deep}): sculk, mobs coming up, disturbance and, through its echoes, stirred
     * deposits. That happens whether or not any geophone is listening, so it is started here, before {@link #fire} (which
     * does nothing with nobody listening).
     */
    public static void thump(ServerLevel level, BlockPos pos, BlockState plateState, int range, ShotSource source) {
        Vec3 centre = pos.getCenter();
        level.playSound(null, pos, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1.2F, 0.6F);
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, plateState),
                centre.x, pos.getY() + 1.05, centre.z, 24, 0.5, 0.02, 0.5, 0.12);
        Deep.of(level).onThump(pos, source, range);
        fire(level, centre, range, source);
    }

    /**
     * Makes a vibration at a point that carries as far as {@code range}, in the ground of this dimension.
     *
     * @return how many loaded geophones are close enough to possibly hear it (0 means nothing was done)
     */
    public static int fire(ServerLevel level, Vec3 source, int range) {
        return fire(level, source, range, null);
    }

    /**
     * As {@link #fire(ServerLevel, Vec3, int)}, for a thumper shot of kind {@code shot} (null for a hammer or plate blow): a
     * real thumper shot also stirs the deposits its echoes come back off.
     */
    public static int fire(ServerLevel level, Vec3 source, int range, ShotSource shot) {
        if (Realm.ofDimension(level.dimension().location().toString()) == null) {
            return 0;
        }
        // A geophone hears a deposit within range of the source only if it is itself within range of that deposit, so
        // it can be at most twice the range from the source.
        List<GeophoneRegistry.Entry> geophones = GeophoneRegistry.within(level, source, 2.0 * range);
        if (geophones.isEmpty()) {
            return 0;
        }

        long start = level.getGameTime();
        DepositFinder.Query query = DepositFinder.Query.around(Mth.floor(source.x), Mth.floor(source.y), Mth.floor(source.z),
                range, DepositFinder.Metric.SPHERE);
        Set<String> ores = GeophoneTier.oresFor(geophones.stream().map(GeophoneRegistry.Entry::tier).toList());
        if (ores != null) {
            query = query.onlyOres(ores);
        }

        DepositScanner.scanAsync(level, query)
                .thenAcceptAsync(scan -> deliver(level, source, range, start, geophones, scan, shot), level.getServer())
                .exceptionally(error -> {
                    EnginesAndEmpiresMod.LOGGER.error("A seismic vibration failed", error);
                    return null;
                });
        return geophones.size();
    }

    /** Works out the pulses and hands each to its geophone. Runs on the main thread. */
    private static void deliver(ServerLevel level, Vec3 source, int range, long start,
                                List<GeophoneRegistry.Entry> geophones, DepositScanner.Scan scan, ShotSource shot) {
        List<SeismicWave.Echoer> echoers = new ArrayList<>();
        for (DepositFinder.Sighting sighting : scan.result().sightings()) {
            echoers.add(DepositEchoes.of(sighting.resolved()));
        }
        // The deposits it bounced off are stirred (the ore you just found is what wakes up).
        Deep.of(level).onEchoes(shot, echoers);
        List<SeismicWave.Receiver> receivers = new ArrayList<>();
        for (GeophoneRegistry.Entry geophone : geophones) {
            Vec3 spike = geophone.position();
            receivers.add(new SeismicWave.Receiver(geophone.entityId(), spike.x, spike.y, spike.z, geophone.tier()));
        }

        List<SeismicWave.Pulse> pulses = SeismicWave.pulses(source.x, source.y, source.z, range, echoers, receivers);
        for (SeismicWave.Pulse pulse : pulses) {
            // Looked up by id, which never loads anything: a geophone that was knocked out or unloaded while the search
            // ran is simply not found.
            Entity entity = level.getEntity((int) pulse.receiver());
            if (entity instanceof GeophoneEntity geophone) {
                geophone.receive(start + pulse.startTicks(), start + pulse.endTicks(), pulse.oreId());
            }
        }

        // Wind-up readers listen through the geophones around them, and record where the nearest deposit they heard is. A
        // reader hears through geophones up to LISTEN_RADIUS from it, which are themselves at most twice the range from the
        // source, so that bounds which readers can matter.
        String dimension = level.dimension().location().toString();
        // How far off any reading from this vibration may be: set by what made it (see ReaderAccuracy).
        double maxError = ReaderAccuracy.maxErrorFor(range);
        for (ReaderRegistry.Entry reader : ReaderRegistry.within(level, source, 2.0 * range + ReaderRecorder.LISTEN_RADIUS)) {
            Vec3 standing = reader.position();
            ReaderRecorder.Recording recording = ReaderRecorder.choose(
                    new ReaderRecorder.Reader(reader.entityId(), standing.x, standing.y, standing.z), echoers, receivers, pulses,
                    maxError);
            if (recording != null && level.getEntity(reader.entityId()) instanceof WindupReaderEntity entity) {
                entity.receive(start + recording.readyTicks(), reading(dimension, start, recording));
            }
        }

        // Smart loggers listen the same way, but further out the faster they turn, and keep every deposit they hear.
        for (LoggerRegistry.Entry logger : LoggerRegistry.within(level, source, 2.0 * range + LoggerListening.MAX_RADIUS)) {
            if (!level.isLoaded(logger.pos()) || !(level.getBlockEntity(logger.pos()) instanceof SmartLoggerBlockEntity entity)) {
                continue;
            }
            double radius = entity.listenRadius();
            if (radius <= 0) {
                continue;
            }
            Vec3 middle = logger.position();
            for (ReaderRecorder.Recording recording : ReaderRecorder.chooseAll(
                    new ReaderRecorder.Reader(logger.pos().asLong(), middle.x, middle.y, middle.z), radius, echoers, receivers, pulses,
                    maxError)) {
                entity.receive(start + recording.readyTicks(), reading(dimension, start, recording));
            }
        }
    }

    /** What a recording becomes once it is heard: a reading, taken when the last geophone it needed lit. */
    private static ReaderReading reading(String dimension, long start, ReaderRecorder.Recording recording) {
        return new ReaderReading(dimension, recording.x(), recording.y(), recording.z(), recording.hasHeight(), recording.deposit(),
                start + recording.readyTicks(), recording.confidence(), recording.ore());
    }

    private SeismicShots() {
    }
}
