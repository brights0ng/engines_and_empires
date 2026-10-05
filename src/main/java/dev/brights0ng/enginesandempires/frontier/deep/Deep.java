package dev.brights0ng.enginesandempires.frontier.deep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.incursion.IncursionData;
import dev.brights0ng.enginesandempires.frontier.incursion.IncursionOdds;
import dev.brights0ng.enginesandempires.geophone.DepositEchoes;
import dev.brights0ng.enginesandempires.geophone.DepositFinder;
import dev.brights0ng.enginesandempires.geophone.DepositScanner;
import dev.brights0ng.enginesandempires.geophone.SeismicWave;
import dev.brights0ng.enginesandempires.geophone.WaveModel;
import dev.brights0ng.enginesandempires.oregen.Realm;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.SculkSensorBlock;
import net.minecraft.world.level.block.entity.SculkShriekerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * What a thumper shot sets off, below the survey itself (phase 5 of Frontier):
 * <ul>
 *   <li><b>Sculk</b>: sensors, shriekers and Wardens within half the shot's range hear it, each when the wave reaches it
 *       (64 blocks a second, as the geophones hear it). Shriekers go through vanilla's warning level for the nearest player,
 *       so thumping near the Deep Dark long enough summons a Warden.</li>
 *   <li><b>Mobs from below</b>: a real shot has a chance (15% mechanical, 30% combustive) of bringing a pack of the
 *       {@code thumper} roster up through the ground 16–32 blocks away.</li>
 *   <li><b>Disturbance</b>: too many shots too close together call up an incursion ({@link Disturbance},
 *       {@link ThumperIncursions}).</li>
 *   <li><b>Stirred deposits</b>: the deposits a shot's echo comes back off are stirred for a while, and the {@code stirred}
 *       roster comes up around them while players are near ({@link StirredDeposits}).</li>
 * </ul>
 * Dry fires set off sculk, but nothing else. Hammer and plate blows set off nothing here.
 */
public final class Deep {

    private static final Map<ServerLevel, Deep> LEVELS = new WeakHashMap<>();

    /** How close a player must be to a stirred deposit for the Deep to come up around it. */
    public static final int STIRRED_RADIUS = 48;
    private static final int STIRRED_INTERVAL = 100;
    private static final double STIRRED_CHANCE = 0.3;
    private static final int STIRRED_CAP = 6;
    private static final int SCULK_FREQUENCY = 12;
    /** A chunk's shots only look for deposits to stir this often, so a thumper firing twice a second stays cheap. */
    private static final int STIR_SCAN_COOLDOWN = 200;

    private final ServerLevel level;
    private final Disturbance disturbance = new Disturbance();
    private final List<Pending> pending = new ArrayList<>();
    private final Map<Long, Long> lastStirScan = new HashMap<>();

    private enum Kind { SENSOR, SHRIEKER, WARDEN }

    private record Pending(long at, Kind kind, BlockPos pos, int entityId, UUID player, BlockPos source) {
    }

    private Deep(ServerLevel level) {
        this.level = level;
    }

    public static Deep of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, Deep::new);
    }

    static void forget(ServerLevel level) {
        LEVELS.remove(level);
    }

    public Disturbance disturbance() {
        return disturbance;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A shot

    /** A thumper's head has landed at {@code pos}, making a vibration that carries {@code range} blocks. */
    public void onThump(BlockPos pos, ShotSource source, int range) {
        FrontierConfig.DeepParams params = FrontierConfig.deep();
        long now = level.getGameTime();
        wakeSculk(pos, range * params.sculkRangeFraction(), now);
        // Every shot, dry or not, draws attention to the settlement it is fired in (see IncursionOdds).
        IncursionData.of(level).recordShot(pos.asLong(), IncursionOdds.shotWeight(range), now);
        if (!source.isRealShot()) {
            return;
        }
        double chance = source == ShotSource.COMBUSTIVE ? params.combustiveMobChance() : params.mechanicalMobChance();
        if (level.random.nextDouble() < chance) {
            int[] distance = FrontierConfig.shotMobDistance();
            DeepSpawns.bringUp(level, DeepRoster.THUMPER, pos, distance[0], distance[1], true, level.random);
        }
        stirAround(pos, source, range, now);
        Disturbance.Outcome outcome = disturbance.record(pos.getX() >> 4, pos.getZ() >> 4, now, source,
                params.disturbanceShots(), params.disturbanceWindow(), params.disturbanceCooldown(),
                params.mechanicalIncursionChance(), params.combustiveIncursionChance(), level.random.nextDouble());
        if (outcome == Disturbance.Outcome.INCURSION) {
            ThumperIncursions.call(level, pos);
        }
    }

    /** A shot's echoes came back off these deposits: stir them (real shots only). */
    public void onEchoes(ShotSource source, List<SeismicWave.Echoer> echoers) {
        if (source == null || !source.isRealShot() || echoers.isEmpty()) {
            return;
        }
        long until = level.getGameTime() + FrontierConfig.deep().stirTicks();
        StirredDeposits stirred = StirredDeposits.of(level);
        for (SeismicWave.Echoer echoer : echoers) {
            BlockPos centre = centre(echoer.cells());
            if (centre != null) {
                stirred.stir(echoer.id(), echoer.oreId(), centre, until);
            }
        }
    }

    /**
     * Stirs every deposit within the shot's range, whether or not any geophone is listening: the search runs in the
     * background (as the survey's does), and only once every {@link #STIR_SCAN_COOLDOWN} ticks per chunk.
     */
    private void stirAround(BlockPos pos, ShotSource source, int range, long now) {
        if (Realm.ofDimension(level.dimension().location().toString()) == null) {
            return;
        }
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        Long last = lastStirScan.get(chunk);
        if (last != null && now - last < STIR_SCAN_COOLDOWN) {
            return;
        }
        if (lastStirScan.size() > 256) {
            lastStirScan.values().removeIf(time -> now - time >= STIR_SCAN_COOLDOWN);
        }
        lastStirScan.put(chunk, now);
        DepositFinder.Query query = DepositFinder.Query.around(pos.getX(), pos.getY(), pos.getZ(), range,
                DepositFinder.Metric.SPHERE);
        DepositScanner.scanAsync(level, query)
                .thenAcceptAsync(scan -> {
                    List<SeismicWave.Echoer> echoers = new ArrayList<>();
                    for (DepositFinder.Sighting sighting : scan.result().sightings()) {
                        echoers.add(DepositEchoes.of(sighting.resolved()));
                    }
                    onEchoes(source, echoers);
                }, level.getServer())
                .exceptionally(error -> {
                    EnginesAndEmpiresMod.LOGGER.error("Looking for deposits to stir failed", error);
                    return null;
                });
    }

    /** Schedules every sculk listener and Warden within {@code radius} of {@code source} to hear the shot when it arrives. */
    private void wakeSculk(BlockPos source, double radius, long now) {
        if (radius <= 0) {
            return;
        }
        Player nearest = level.getNearestPlayer(source.getX() + 0.5, source.getY(), source.getZ() + 0.5, radius, false);
        UUID player = nearest == null ? null : nearest.getUUID();
        for (BlockPos listener : SculkListeners.of(level).within(source, radius)) {
            BlockState state = level.getBlockState(listener);
            Kind kind = state.getBlock() instanceof SculkSensorBlock ? Kind.SENSOR : Kind.SHRIEKER;
            pending.add(new Pending(now + delay(source, listener), kind, listener, -1, player, source));
        }
        AABB box = new AABB(source).inflate(radius);
        for (Warden warden : level.getEntitiesOfClass(Warden.class, box,
                w -> w.blockPosition().distSqr(source) <= radius * radius)) {
            pending.add(new Pending(now + delay(source, warden.blockPosition()), Kind.WARDEN, warden.blockPosition(),
                    warden.getId(), player, source));
        }
    }

    /** Ticks for the wave to get from {@code a} to {@code b}. */
    public static long delay(BlockPos a, BlockPos b) {
        return (long) Math.ceil(Math.sqrt(a.distSqr(b)) / WaveModel.BLOCKS_PER_TICK);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Time passing

    void tick() {
        long now = level.getGameTime();
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending p = it.next();
            if (p.at() <= now) {
                it.remove();
                hear(p);
            }
        }
        if (now % STIRRED_INTERVAL == 0) {
            stirUp(now);
        }
    }

    private void hear(Pending p) {
        if (!level.isLoaded(p.pos()) && p.kind() != Kind.WARDEN) {
            return;
        }
        switch (p.kind()) {
            case SENSOR -> {
                BlockState state = level.getBlockState(p.pos());
                if (state.getBlock() instanceof SculkSensorBlock sensor && SculkSensorBlock.canActivate(state)) {
                    sensor.activate(null, level, p.pos(), state, 15, SCULK_FREQUENCY);
                }
            }
            case SHRIEKER -> {
                if (level.getBlockEntity(p.pos()) instanceof SculkShriekerBlockEntity shrieker) {
                    ServerPlayer player = p.player() == null ? null : level.getServer().getPlayerList().getPlayer(p.player());
                    shrieker.tryShriek(level, player != null && player.level() == level ? player : null);
                }
            }
            case WARDEN -> {
                Entity entity = level.getEntity(p.entityId());
                if (entity instanceof Warden warden && warden.isAlive()) {
                    WardenAi.setDisturbanceLocation(warden, p.source());
                }
            }
        }
    }

    /** Brings the Deep up around stirred deposits that players are near. */
    private void stirUp(long now) {
        StirredDeposits stirred = StirredDeposits.of(level);
        stirred.settle(now);
        if (stirred.size() == 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            List<StirredDeposits.Stirred> near = stirred.near(player.blockPosition(), STIRRED_RADIUS, now);
            if (near.isEmpty() || level.random.nextDouble() >= STIRRED_CHANCE) {
                continue;
            }
            int around = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(STIRRED_RADIUS),
                    mob -> mob.getTags().contains(DeepSpawns.TAG)).size();
            if (around >= STIRRED_CAP) {
                continue;
            }
            StirredDeposits.Stirred deposit = near.get(level.random.nextInt(near.size()));
            DeepSpawns.bringUp(level, DeepRoster.STIRRED, deposit.centre(), 6, 20, false, level.random);
        }
    }

    /** The middle of a deposit's ore blocks. */
    static BlockPos centre(WaveModel.OreCells cells) {
        int n = cells.size();
        if (n == 0) {
            return null;
        }
        double x = 0;
        double y = 0;
        double z = 0;
        for (int i = 0; i < n; i++) {
            x += cells.x()[i];
            y += cells.y()[i];
            z += cells.z()[i];
        }
        return BlockPos.containing(x / n, y / n, z / n);
    }

    /** Hears every pending sculk trigger now, however far (tests). */
    public int hearAllNow() {
        int n = pending.size();
        List<Pending> all = new ArrayList<>(pending);
        pending.clear();
        all.forEach(this::hear);
        return n;
    }

    public int pendingCount() {
        return pending.size();
    }
}
