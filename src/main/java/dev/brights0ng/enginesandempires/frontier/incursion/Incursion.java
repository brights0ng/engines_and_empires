package dev.brights0ng.enginesandempires.frontier.incursion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import dev.brights0ng.enginesandempires.frontier.deep.DeepRoster;
import dev.brights0ng.enginesandempires.frontier.deep.DeepSpawns;
import dev.brights0ng.enginesandempires.frontier.upkeep.DryTorches;
import dev.brights0ng.enginesandempires.geophone.ChokedThumpers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One incursion under way (Frontier phase 6): waves of mobs from the Deep, coming up out of the ground and hunting.
 *
 * <ul>
 *   <li><b>Lesser</b> and <b>greater</b> incursions come for a settlement at night. They come up 24-40 blocks from a player
 *       inside, hunt players first, and seek out villagers, pillagers and colonists when no player is about. A lesser
 *       one has 1-2 waves, a greater one 5, each worse than the last. At dawn the waves stop and whatever is left digs back
 *       down or runs off: <em>Retreating</em>, neither a win nor a loss. Beating the last wave is a <em>Victory</em>: Hero of
 *       the Village for the players there.</li>
 *   <li><b>Thumper</b> incursions (always lesser) come for the thumpers that called them, whatever the time: 1-2 waves. A mob
 *       that stays by a thumper chokes it with sculk. If every thumper it came for is choked and the players have not won
 *       within two minutes, it is a <em>Defeat</em>. After ten minutes without a win it retreats.</li>
 * </ul>
 *
 * <p>Like a vanilla raid: a boss bar with the wave and what is left of it, a horn from the side each wave comes from, a
 * pause between waves, and the last stragglers glowing. Incursion mobs break no blocks, but snuff torches as they pass.
 *
 * <p>Saved with {@link IncursionData}. Its mobs carry {@link #TAG} and their incursion's id in {@link #ID_KEY}.
 */
public final class Incursion {

    public enum Kind { LESSER, GREATER, THUMPER }

    public enum State { STARTING, WAITING, FIGHTING, ENDED }

    public enum Result { NONE, VICTORY, DEFEAT, RETREAT }

    public static final String TAG = "engines_and_empires.incursion";
    public static final String ID_KEY = "engines_and_empires.incursion";
    public static final String RETREAT_TAG = "engines_and_empires.retreating";
    public static final String RETREAT_AT_KEY = "engines_and_empires.retreat_at";

    /** How far the boss bar and the reward reach around a thumper incursion. */
    public static final int THUMPER_REACH = 96;
    /** How far around the thumper that called it an incursion looks for other thumpers to go after. */
    public static final int THUMPER_SEARCH = 16;
    private static final int GLOW_AFTER = 600;
    private static final int GLOW_WHEN_LEFT = 3;
    /** How far from a player inside a settlement its waves come up. */
    private static final int SETTLEMENT_SPAWN_MIN = 24;
    private static final int SETTLEMENT_SPAWN_MAX = 40;
    /** How far from the nearest player a mob that cannot walk comes up. */
    private static final int STATIONARY_SPAWN_MIN = 8;
    private static final int STATIONARY_SPAWN_MAX = 16;
    private static final int ENDED_LINGER = 200;
    private static final int MAX_SPAWN_FAILURES = 20;

    private final int id;
    private final Kind kind;
    private final BlockPos centre;
    private final Set<Long> sections;
    private final Set<Long> columns;
    private final List<BlockPos> thumpers;
    private final int totalWaves;
    private final long startedAt;
    private final long dawnAt;

    private State state = State.STARTING;
    private Result result = Result.NONE;
    private int wave;
    private long nextWaveAt;
    private long waveStartedAt;
    private long endedAt;
    private long allChokedSince = -1;
    private int spawnFailures;
    private float waveHealth = 1.0F;
    private final Map<UUID, Long> mobs = new LinkedHashMap<>();
    private final Set<UUID> killedPlayers = new HashSet<>();
    private final Map<Long, Integer> choking = new HashMap<>();

    private ServerBossEvent bar;
    private Settlement settlement;

    Incursion(int id, Kind kind, BlockPos centre, Set<Long> sections, Set<Long> columns, List<BlockPos> thumpers,
              int totalWaves, long startedAt, long dawnAt, long firstWaveAt) {
        this.id = id;
        this.kind = kind;
        this.centre = centre.immutable();
        this.sections = new HashSet<>(sections);
        this.columns = new HashSet<>(columns);
        this.thumpers = new ArrayList<>(thumpers);
        this.totalWaves = totalWaves;
        this.startedAt = startedAt;
        this.dawnAt = dawnAt;
        this.nextWaveAt = firstWaveAt;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Asking

    public int id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public State state() {
        return state;
    }

    public Result result() {
        return result;
    }

    public int wave() {
        return wave;
    }

    public int totalWaves() {
        return totalWaves;
    }

    public BlockPos centre() {
        return centre;
    }

    public List<BlockPos> thumpers() {
        return List.copyOf(thumpers);
    }

    public int mobCount() {
        return mobs.size();
    }

    public boolean isOver() {
        return state == State.ENDED;
    }

    /** The settlement it came for (lesser and greater ones), as it was when it began. */
    public Settlement settlement() {
        if (settlement == null && kind != Kind.THUMPER) {
            settlement = new Settlement(sections, columns, kind == Kind.GREATER);
        }
        return settlement;
    }

    /** Whether it is after the same land as the chunk columns given. */
    public boolean overlaps(Set<Long> others) {
        for (long c : others) {
            if (columns.contains(c)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a player at {@code pos} is taking part: shown the bar, and rewarded on a win. */
    public boolean involves(BlockPos pos) {
        if (kind == Kind.THUMPER) {
            return pos.closerThan(centre, THUMPER_REACH);
        }
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (columns.contains(SettlementClusters.column(cx + dx, cz + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Where a mob of this incursion with nothing to fight heads. */
    public BlockPos destination(ServerLevel level, Mob mob) {
        if (kind == Kind.THUMPER) {
            BlockPos nearest = null;
            for (BlockPos thumper : thumpers) {
                if (ChokedThumpers.isThumper(level.getBlockState(thumper))
                        && (nearest == null || mob.distanceToSqr(Vec3.atCenterOf(thumper)) < mob.distanceToSqr(Vec3.atCenterOf(nearest)))) {
                    nearest = thumper;
                }
            }
            if (nearest != null) {
                return nearest;
            }
            Player player = level.getNearestPlayer(mob, THUMPER_REACH);
            return player != null && !player.isSpectator() && !player.isCreative() ? player.blockPosition() : centre;
        }
        return centre;
    }

    /** How close a mob must get to its destination before it stops marching. */
    public double arrivedWithin() {
        return kind == Kind.THUMPER ? 1.5 : 6.0;
    }

    /** Remembers a mob of this incursion (spawned, or loaded back in). */
    void enlist(Mob mob) {
        if (state != State.ENDED) {
            mobs.put(mob.getUUID(), mob.blockPosition().asLong());
        }
    }

    /** A player died to this incursion: whether it is the first time this incursion killed them. */
    public boolean firstKillOf(UUID player) {
        return killedPlayers.add(player);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Time passing

    /** Runs once a tick. Returns whether it is finished with (its bar gone) and can be forgotten. */
    boolean tick(ServerLevel level) {
        long now = level.getGameTime();
        if (state == State.ENDED) {
            if (now % 10 == 0) {
                updateBar(level);
            }
            if (now - endedAt >= ENDED_LINGER) {
                removeBar();
                return true;
            }
            return false;
        }
        if (checkEnd(level, now)) {
            return false;
        }
        switch (state) {
            case STARTING, WAITING -> {
                if (now >= nextWaveAt) {
                    spawnWave(level, now);
                }
            }
            case FIGHTING -> {
                prune(level);
                if (mobs.isEmpty()) {
                    if (wave >= totalWaves) {
                        end(level, Result.VICTORY);
                        return false;
                    }
                    state = State.WAITING;
                    nextWaveAt = now + FrontierConfig.incursions().waveDelay();
                } else {
                    behave(level, now);
                }
            }
            default -> {
            }
        }
        if (now % 10 == 0) {
            updateBar(level);
        }
        return false;
    }

    /** Dawn (settlement incursions), the time running out, or every thumper choked (thumper incursions). */
    private boolean checkEnd(ServerLevel level, long now) {
        FrontierConfig.IncursionParams params = FrontierConfig.incursions();
        if (kind != Kind.THUMPER) {
            if (dawnAt >= 0 && level.getDayTime() >= dawnAt) {
                end(level, Result.RETREAT);
                return true;
            }
            return false;
        }
        if (now - startedAt >= params.thumperTimeout()) {
            end(level, Result.RETREAT);
            return true;
        }
        boolean anyLeft = false;
        for (BlockPos thumper : thumpers) {
            if (!level.isLoaded(thumper) || ChokedThumpers.isThumper(level.getBlockState(thumper))) {
                anyLeft = true;
                break;
            }
        }
        if (anyLeft) {
            allChokedSince = -1;
        } else if (allChokedSince < 0) {
            allChokedSince = now;
        } else if (now - allChokedSince >= params.thumperDefeatDelay()) {
            end(level, Result.DEFEAT);
            return true;
        }
        return false;
    }

    /** Forgets mobs that have died or gone. One in a chunk that is not loaded is kept: it is still out there. */
    private void prune(ServerLevel level) {
        for (Iterator<Map.Entry<UUID, Long>> it = mobs.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> entry = it.next();
            Entity entity = level.getEntity(entry.getKey());
            if (entity != null) {
                if (!entity.isAlive()) {
                    it.remove();
                } else {
                    entry.setValue(entity.blockPosition().asLong());
                }
            } else if (level.isPositionEntityTicking(BlockPos.of(entry.getValue()))) {
                it.remove();
            }
        }
    }

    /** What its mobs do besides fighting: snuff torches, steer Wardens, choke thumpers, and glow when few are left. */
    private void behave(ServerLevel level, long now) {
        boolean everyTen = now % 10 == 0;
        boolean glow = mobs.size() <= GLOW_WHEN_LEFT && now - waveStartedAt >= GLOW_AFTER;
        List<Mob> loaded = new ArrayList<>();
        for (UUID uuid : mobs.keySet()) {
            if (level.getEntity(uuid) instanceof Mob mob && mob.isAlive()) {
                loaded.add(mob);
            }
        }
        for (Mob mob : loaded) {
            if (glow && !mob.hasGlowingTag()) {
                mob.setGlowingTag(true);
            }
            if (everyTen) {
                snuffTorches(level, mob.blockPosition());
            }
            if (now % 20 == 0 && mob.getType() == EntityType.WARDEN) {
                IncursionGoals.steerWarden(level, this, mob);
            }
            if (now % 20 == 0 && mob.getType().is(FrontierTags.STATIONARY)) {
                IncursionGoals.keepUp(mob);
            }
        }
        if (kind == Kind.THUMPER && now % 5 == 0) {
            chokeThumpers(level, loaded, 5);
        }
    }

    /** Every torch within 2 blocks of {@code at} goes out. */
    public static void snuffTorches(ServerLevel level, BlockPos at) {
        for (BlockPos pos : BlockPos.betweenClosed(at.offset(-2, -2, -2), at.offset(2, 2, 2))) {
            BlockState state = level.getBlockState(pos);
            if (state.is(FrontierTags.BURNS_OUT)) {
                level.setBlock(pos, DryTorches.dryFor(state), Block.UPDATE_ALL);
                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4F, 1.4F);
            }
        }
    }

    /** A thumper with one of this incursion's mobs by it for long enough is choked. */
    private void chokeThumpers(ServerLevel level, List<Mob> loaded, int ticks) {
        int needed = FrontierConfig.incursions().chokeTicks();
        for (BlockPos thumper : thumpers) {
            if (!level.isLoaded(thumper) || !ChokedThumpers.isThumper(level.getBlockState(thumper))) {
                choking.remove(thumper.asLong());
                continue;
            }
            AABB reach = new AABB(thumper).inflate(2.0, 1.0, 2.0).expandTowards(0, 2, 0);
            boolean near = false;
            for (Mob mob : loaded) {
                if (mob.getBoundingBox().intersects(reach)) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                choking.remove(thumper.asLong());
                continue;
            }
            int progress = choking.merge(thumper.asLong(), ticks, Integer::sum);
            level.sendParticles(ParticleTypes.SCULK_SOUL, thumper.getX() + 0.5, thumper.getY() + 1.2, thumper.getZ() + 0.5,
                    2, 0.4, 0.3, 0.4, 0.01);
            if (progress >= needed) {
                choking.remove(thumper.asLong());
                ChokedThumpers.choke(level, thumper);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Waves

    private void spawnWave(ServerLevel level, long now) {
        int next = wave + 1;
        Optional<DeepRoster> found = DeepRoster.get(level, kind == Kind.GREATER ? DeepRoster.greater(next) : DeepRoster.LESSER);
        BlockPos spot = found.isPresent() ? spawnSpot(level) : null;
        List<Mob> spawned = new ArrayList<>();
        if (spot != null) {
            DeepRoster list = found.get();
            for (int p = 0; p < list.packs(); p++) {
                list.pick(level.random).ifPresent(entry -> {
                    int count = entry.min() + level.random.nextInt(Math.max(1, entry.max() - entry.min() + 1));
                    spawned.addAll(DeepSpawns.pack(level, entry.type().orElseThrow(), count, entry.health(), spot, 0, 8,
                            true, level.random, this::prepare));
                });
            }
            for (DeepRoster.Fixed fixed : list.fixed()) {
                fixed.type().ifPresent(type -> {
                    int count = fixed.roll(level.random);
                    if (count <= 0) {
                        return;
                    }
                    if (type.is(FrontierTags.STATIONARY)) {
                        // It cannot walk to anyone: bring it up near a player instead, one at a time.
                        for (int i = 0; i < count; i++) {
                            BlockPos near = nearPlayer(level, spot);
                            spawned.addAll(DeepSpawns.pack(level, type, 1, fixed.health(), near, STATIONARY_SPAWN_MIN,
                                    STATIONARY_SPAWN_MAX, true, level.random, this::prepare));
                        }
                    } else {
                        spawned.addAll(DeepSpawns.pack(level, type, count, fixed.health(), spot, 0, 10, true,
                                level.random, this::prepare));
                    }
                });
            }
        }
        if (spawned.isEmpty()) {
            // Nowhere to come up (the edge not loaded, no room), or no roster: try again shortly, and give up in the end.
            nextWaveAt = now + 100;
            if (++spawnFailures >= MAX_SPAWN_FAILURES) {
                end(level, Result.RETREAT);
            }
            return;
        }
        wave = next;
        state = State.FIGHTING;
        waveStartedAt = now;
        float health = 0;
        for (Mob mob : spawned) {
            enlist(mob);
            health += mob.getMaxHealth();
        }
        waveHealth = Math.max(1.0F, health);
        horn(level, spot);
    }

    /** Tags a mob as this incursion's before it joins the level. */
    private void prepare(Mob mob) {
        mob.addTag(TAG);
        mob.getPersistentData().putInt(ID_KEY, id);
        mob.setPersistenceRequired();
    }

    /** The nearest player taking part to {@code from} (or {@code from} itself if none is about). */
    private BlockPos nearPlayer(ServerLevel level, BlockPos from) {
        ServerPlayer best = null;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && involves(player.blockPosition())
                    && (best == null || player.distanceToSqr(Vec3.atCenterOf(from)) < best.distanceToSqr(Vec3.atCenterOf(from)))) {
                best = player;
            }
        }
        return best == null ? from : best.blockPosition();
    }

    /**
     * Where a wave comes up. A settlement's: 24-40 blocks from one of the players inside, on the side away from the middle
     * (so it still comes in from the outskirts), on the settlement's own land if it can; with nobody inside, on its edge
     * farthest from any player. A thumper's: 24-40 blocks out from it.
     */
    private BlockPos spawnSpot(ServerLevel level) {
        if (kind == Kind.THUMPER) {
            int[] distance = FrontierConfig.thumperWaveDistance();
            return DeepSpawns.findSpot(level, EntityType.ZOMBIE, centre, distance[0], distance[1], true, level.random);
        }
        List<ServerPlayer> inside = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && settlement().contains(player.blockPosition())) {
                inside.add(player);
            }
        }
        if (!inside.isEmpty()) {
            ServerPlayer player = inside.get(level.random.nextInt(inside.size()));
            BlockPos near = outskirtsOf(level, player);
            if (near != null) {
                return near;
            }
        }
        BlockPos best = null;
        double bestDistance = -1;
        for (long column : SettlementClusters.edge(columns)) {
            int x = SettlementClusters.columnX(column) * 16 + 8;
            int z = SettlementClusters.columnZ(column) * 16 + 8;
            BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!level.isPositionEntityTicking(at)) {
                continue;
            }
            double nearest = Double.MAX_VALUE;
            for (ServerPlayer player : level.players()) {
                if (!player.isSpectator()) {
                    nearest = Math.min(nearest, player.distanceToSqr(Vec3.atCenterOf(at)));
                }
            }
            if (nearest > bestDistance) {
                bestDistance = nearest;
                best = at;
            }
        }
        if (best == null) {
            return null;
        }
        return DeepSpawns.findSpot(level, EntityType.ZOMBIE, best, 0, 8, true, level.random);
    }

    /** A spot 24-40 blocks from {@code player}, away from the settlement's middle, preferring its own land. */
    private BlockPos outskirtsOf(ServerLevel level, ServerPlayer player) {
        double away = Math.atan2(player.getZ() - centre.getZ(), player.getX() - centre.getX());
        if (player.blockPosition().closerThan(centre, 8)) {
            away = level.random.nextDouble() * Math.PI * 2; // in the middle: any side will do
        }
        BlockPos fallback = null;
        for (int attempt = 0; attempt < 16; attempt++) {
            double angle = away + (level.random.nextDouble() - 0.5) * (Math.PI * 2 / 3); // within 60 degrees either way
            double distance = SETTLEMENT_SPAWN_MIN + level.random.nextDouble() * (SETTLEMENT_SPAWN_MAX - SETTLEMENT_SPAWN_MIN);
            int x = (int) Math.floor(player.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getZ() + Math.sin(angle) * distance);
            BlockPos at = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
            if (!level.isPositionEntityTicking(at)) {
                continue;
            }
            BlockPos spot = DeepSpawns.findSpot(level, EntityType.ZOMBIE, at, 0, 3, true, level.random);
            if (spot == null) {
                continue;
            }
            if (settlement().inColumns(spot)) {
                return spot;
            }
            if (fallback == null) {
                fallback = spot;
            }
        }
        return fallback;
    }

    /** A low horn for everyone taking part, from the side the wave came up on, the way a raid's is. */
    private void horn(ServerLevel level, BlockPos spot) {
        for (ServerPlayer player : level.players()) {
            if (!involves(player.blockPosition()) && !player.blockPosition().closerThan(spot, 64)) {
                continue;
            }
            Vec3 from = player.position();
            Vec3 towards = Vec3.atCenterOf(spot).subtract(from);
            Vec3 at = towards.lengthSqr() < 1 ? from : from.add(towards.normalize().scale(13));
            player.connection.send(new ClientboundSoundPacket(SoundEvents.RAID_HORN, SoundSource.HOSTILE, at.x, at.y, at.z,
                    64.0F, 0.55F, level.random.nextLong()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ending

    /** Ends it: the reward for a victory, and whatever is left retreats. */
    public void end(ServerLevel level, Result how) {
        if (state == State.ENDED) {
            return;
        }
        state = State.ENDED;
        result = how;
        endedAt = level.getGameTime();
        if (how == Result.VICTORY) {
            FrontierConfig.IncursionParams params = FrontierConfig.incursions();
            int hero = kind == Kind.GREATER ? params.heroGreaterTicks() : params.heroLesserTicks();
            for (ServerPlayer player : level.players()) {
                if (!player.isSpectator() && involves(player.blockPosition())) {
                    if (hero > 0) {
                        player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, hero, 0, false, false, true));
                    }
                    player.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 0.8F, 1.0F);
                }
            }
        }
        for (UUID uuid : mobs.keySet()) {
            if (level.getEntity(uuid) instanceof Mob mob && mob.isAlive()) {
                IncursionGoals.retreat(level, mob);
            }
        }
        mobs.clear();
        updateBar(level);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // The boss bar

    private void updateBar(ServerLevel level) {
        if (bar == null) {
            bar = new ServerBossEvent(title(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_10);
        }
        bar.setName(title());
        bar.setProgress(progress(level));
        Set<ServerPlayer> should = new HashSet<>();
        for (ServerPlayer player : level.players()) {
            if (involves(player.blockPosition())) {
                should.add(player);
            }
        }
        for (ServerPlayer player : List.copyOf(bar.getPlayers())) {
            if (!should.contains(player)) {
                bar.removePlayer(player);
            }
        }
        for (ServerPlayer player : should) {
            bar.addPlayer(player);
        }
    }

    private Component title() {
        Component name = Component.translatable(kind == Kind.GREATER ? "event.engines_and_empires.incursion.greater"
                : "event.engines_and_empires.incursion");
        return switch (state) {
            case STARTING -> name;
            case WAITING, FIGHTING -> Component.translatable("event.engines_and_empires.incursion.wave", name,
                    state == State.WAITING ? wave + 1 : wave, totalWaves);
            case ENDED -> switch (result) {
                case VICTORY -> Component.translatable("event.engines_and_empires.incursion.victory", name);
                case DEFEAT -> Component.translatable("event.engines_and_empires.incursion.defeat", name);
                default -> Component.translatable("event.engines_and_empires.incursion.retreating", name);
            };
        };
    }

    /** Like a raid's: filling up while the next wave gathers, then what is left of the wave's health. */
    private float progress(ServerLevel level) {
        if (state == State.ENDED) {
            return result == Result.VICTORY ? 0.0F : 1.0F;
        }
        if (state != State.FIGHTING) {
            long delay = Math.max(1, FrontierConfig.incursions().waveDelay());
            float left = Math.max(0, nextWaveAt - level.getGameTime());
            return Math.max(0.0F, Math.min(1.0F, 1.0F - left / delay));
        }
        float health = 0;
        for (UUID uuid : mobs.keySet()) {
            if (level.getEntity(uuid) instanceof LivingEntity living) {
                health += living.getHealth();
            } else {
                health += waveHealth / Math.max(1, mobs.size()); // out of sight: assume its share
            }
        }
        return Math.max(0.0F, Math.min(1.0F, health / waveHealth));
    }

    void removeBar() {
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Saving

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("kind", kind.name());
        tag.putLong("centre", centre.asLong());
        tag.putLongArray("sections", sections.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("columns", columns.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("thumpers", thumpers.stream().mapToLong(BlockPos::asLong).toArray());
        tag.putInt("totalWaves", totalWaves);
        tag.putLong("startedAt", startedAt);
        tag.putLong("dawnAt", dawnAt);
        tag.putString("state", state.name());
        tag.putString("result", result.name());
        tag.putInt("wave", wave);
        tag.putLong("nextWaveAt", nextWaveAt);
        tag.putLong("waveStartedAt", waveStartedAt);
        tag.putLong("endedAt", endedAt);
        tag.putLong("allChokedSince", allChokedSince);
        tag.putFloat("waveHealth", waveHealth);
        ListTag mobList = new ListTag();
        for (Map.Entry<UUID, Long> entry : mobs.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("uuid", entry.getKey());
            m.putLong("pos", entry.getValue());
            mobList.add(m);
        }
        tag.put("mobs", mobList);
        ListTag killed = new ListTag();
        for (UUID uuid : killedPlayers) {
            CompoundTag k = new CompoundTag();
            k.putUUID("uuid", uuid);
            killed.add(k);
        }
        tag.put("killedPlayers", killed);
        return tag;
    }

    static Incursion load(CompoundTag tag) {
        Set<Long> sections = new HashSet<>();
        for (long s : tag.getLongArray("sections")) {
            sections.add(s);
        }
        Set<Long> columns = new HashSet<>();
        for (long c : tag.getLongArray("columns")) {
            columns.add(c);
        }
        List<BlockPos> thumpers = new ArrayList<>();
        for (long t : tag.getLongArray("thumpers")) {
            thumpers.add(BlockPos.of(t));
        }
        Incursion incursion = new Incursion(tag.getInt("id"), Kind.valueOf(tag.getString("kind")),
                BlockPos.of(tag.getLong("centre")), sections, columns, thumpers, tag.getInt("totalWaves"),
                tag.getLong("startedAt"), tag.getLong("dawnAt"), tag.getLong("nextWaveAt"));
        incursion.state = State.valueOf(tag.getString("state"));
        incursion.result = Result.valueOf(tag.getString("result"));
        incursion.wave = tag.getInt("wave");
        incursion.waveStartedAt = tag.getLong("waveStartedAt");
        incursion.endedAt = tag.getLong("endedAt");
        incursion.allChokedSince = tag.getLong("allChokedSince");
        incursion.waveHealth = Math.max(1.0F, tag.getFloat("waveHealth"));
        ListTag mobList = tag.getList("mobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < mobList.size(); i++) {
            CompoundTag m = mobList.getCompound(i);
            incursion.mobs.put(m.getUUID("uuid"), m.getLong("pos"));
        }
        ListTag killed = tag.getList("killedPlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < killed.size(); i++) {
            incursion.killedPlayers.add(killed.getCompound(i).getUUID("uuid"));
        }
        return incursion;
    }

    /** One line about it, for the debug command. */
    public String describe(ServerLevel level) {
        String where = kind == Kind.THUMPER ? thumpers.size() + " thumper(s) near " + centre.toShortString()
                : columns.size() + " chunk(s) around " + centre.toShortString();
        String when = state == State.ENDED ? result.name().toLowerCase()
                : state == State.FIGHTING ? "wave " + wave + "/" + totalWaves + ", " + mobs.size() + " left"
                : "wave " + (wave + 1) + "/" + totalWaves + " in " + Math.max(0, nextWaveAt - level.getGameTime()) / 20 + "s";
        String deadline = kind == Kind.THUMPER
                ? ", retreats in " + Math.max(0, startedAt + FrontierConfig.incursions().thumperTimeout() - level.getGameTime()) / 20 + "s"
                : dawnAt >= 0 ? ", dawn in " + Math.max(0, dawnAt - level.getDayTime()) / 20 + "s" : "";
        return "#" + id + " " + kind.name().toLowerCase() + ": " + where + ", " + when + deadline;
    }
}
