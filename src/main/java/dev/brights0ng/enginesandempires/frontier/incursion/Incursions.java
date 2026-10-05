package dev.brights0ng.enginesandempires.frontier.incursion;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import dev.brights0ng.enginesandempires.geophone.ChokedThumpers;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;

/**
 * The incursions of one level: rolling for them at nightfall, starting them, and running them. See {@link Incursion}.
 */
public final class Incursions {

    private static final Map<ServerLevel, Incursions> LEVELS = new WeakHashMap<>();
    /** Nightfall and dawn, in the time of day. */
    public static final long NIGHTFALL = 13000;
    public static final long DAWN = 23000;
    /** A thumper incursion is not called up within this far of one already under way. */
    private static final int THUMPER_APART = 64;
    /** How many waves a greater incursion has (Bright, after playtesting: 10 was more than a night allows). */
    public static final int GREATER_WAVES = 5;

    private final ServerLevel level;

    private Incursions(ServerLevel level) {
        this.level = level;
    }

    public static Incursions of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, Incursions::new);
    }

    static void forget(ServerLevel level) {
        Incursions incursions = LEVELS.remove(level);
        if (incursions != null) {
            for (Incursion incursion : incursions.data().incursions()) {
                incursion.removeBar();
            }
        }
    }

    public IncursionData data() {
        return IncursionData.of(level);
    }

    /** Every incursion not yet forgotten (including ones just ended, whose bar still shows the result). */
    public List<Incursion> all() {
        return List.copyOf(data().incursions());
    }

    /** The incursions still under way. */
    public List<Incursion> active() {
        List<Incursion> active = new ArrayList<>();
        for (Incursion incursion : data().incursions()) {
            if (!incursion.isOver()) {
                active.add(incursion);
            }
        }
        return active;
    }

    public Incursion byId(int id) {
        for (Incursion incursion : data().incursions()) {
            if (incursion.id() == id) {
                return incursion;
            }
        }
        return null;
    }

    /** The incursion a mob belongs to, if it belongs to one still known. */
    public Incursion forMob(Mob mob) {
        if (!mob.getTags().contains(Incursion.TAG)) {
            return null;
        }
        return byId(mob.getPersistentData().getInt(Incursion.ID_KEY));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Time passing

    void tick() {
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier != null) {
            long dayTime = level.getDayTime();
            long timeOfDay = dayTime % 24000;
            long night = dayTime / 24000;
            if (timeOfDay >= NIGHTFALL && timeOfDay < DAWN && data().lastRolledNight() != night) {
                data().setLastRolledNight(night);
                rollTonight(frontier, false);
            }
        }
        List<Incursion> incursions = data().incursions();
        if (incursions.isEmpty()) {
            return;
        }
        for (Iterator<Incursion> it = incursions.iterator(); it.hasNext(); ) {
            if (it.next().tick(level)) {
                it.remove();
            }
        }
        data().markChanged();
    }

    /** A mob of an incursion has joined the level (spawned, or loaded back in). */
    void onMobJoin(Mob mob) {
        Incursion incursion = forMob(mob);
        if (incursion == null || incursion.isOver()) {
            IncursionGoals.retreat(level, mob);
        } else if (!IncursionGoals.isRetreating(mob)) {
            incursion.enlist(mob);
        }
        IncursionGoals.equip(mob);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Rolling

    /** How one settlement's roll went, for the debug command. */
    public record Roll(Settlement settlement, Settlement.Score score, double chance, IncursionOdds.Kind kind, String note) {
    }

    /**
     * Rolls tonight's incursions: for every settlement with a player inside, not immune, and without one already, a roll
     * against its chance. With {@code force}, every such settlement gets one (lesser for Settled land, and for Civilized land
     * greater at its usual share).
     */
    public List<Roll> rollTonight(FrontierLevel frontier, boolean force) {
        FrontierConfig.IncursionParams params = FrontierConfig.incursions();
        long now = level.getGameTime();
        List<Roll> rolls = new ArrayList<>();
        for (Settlement settlement : Settlement.all(frontier)) {
            if (settlement.players(level).isEmpty()) {
                rolls.add(new Roll(settlement, null, 0, IncursionOdds.Kind.NONE, "no player inside"));
                continue;
            }
            long immune = data().immuneUntil(settlement.columns(), now);
            if (immune > now) {
                rolls.add(new Roll(settlement, null, 0, IncursionOdds.Kind.NONE,
                        "immune for " + (immune - now) / 24000.0 + " days"));
                continue;
            }
            if (underAttack(settlement.columns())) {
                rolls.add(new Roll(settlement, null, 0, IncursionOdds.Kind.NONE, "already has one"));
                continue;
            }
            Settlement.Score score = settlement.score(level);
            double chance = settlement.chance(score.total());
            double roll = level.random.nextDouble();
            IncursionOdds.Kind kind = IncursionOdds.roll(force ? 1.0 : chance, settlement.civilized(), params.greaterShare(), roll);
            if (kind != IncursionOdds.Kind.NONE) {
                startSettlement(settlement, kind == IncursionOdds.Kind.GREATER);
            }
            rolls.add(new Roll(settlement, score, chance, kind, ""));
        }
        return rolls;
    }

    private boolean underAttack(Set<Long> columns) {
        for (Incursion incursion : active()) {
            if (incursion.kind() != Incursion.Kind.THUMPER && incursion.overlaps(columns)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Starting

    /** Starts a lesser or greater incursion against a settlement, and makes it immune for the next ten days. */
    public Incursion startSettlement(Settlement settlement, boolean greater) {
        FrontierConfig.IncursionParams params = FrontierConfig.incursions();
        long now = level.getGameTime();
        int waves = greater ? GREATER_WAVES : 1 + level.random.nextInt(2);
        Incursion incursion = new Incursion(data().nextId(), greater ? Incursion.Kind.GREATER : Incursion.Kind.LESSER,
                settlement.centre(level), settlement.sections(), settlement.columns(), List.of(), waves, now,
                nextDawn(level.getDayTime()), now + params.waveDelay());
        data().incursions().add(incursion);
        data().makeImmune(settlement.columns(), now + params.immunityTicks());
        for (ServerPlayer player : level.players()) {
            if (incursion.involves(player.blockPosition())) {
                player.playNotifySound(SoundEvents.WARDEN_EMERGE, SoundSource.HOSTILE, 1.0F, 0.5F);
            }
        }
        EnginesAndEmpiresMod.LOGGER.info("A {} incursion begins against the settlement around {} ({} waves)",
                greater ? "greater" : "lesser", incursion.centre(), waves);
        return incursion;
    }

    /**
     * Starts a (lesser) incursion against the thumpers around {@code pos}. Nothing happens if one is already under way
     * nearby. Returns it, or null.
     */
    public Incursion startThumper(BlockPos pos) {
        for (Incursion incursion : active()) {
            if (incursion.kind() == Incursion.Kind.THUMPER && incursion.centre().closerThan(pos, THUMPER_APART)) {
                return null;
            }
        }
        List<BlockPos> thumpers = new ArrayList<>();
        int r = Incursion.THUMPER_SEARCH;
        for (BlockPos at : BlockPos.betweenClosed(pos.offset(-r, -4, -r), pos.offset(r, 4, r))) {
            var state = level.getBlockState(at);
            if (ChokedThumpers.isThumper(state)) {
                BlockPos base = ChokedThumpers.basePos(at, state).immutable();
                if (!thumpers.contains(base)) {
                    thumpers.add(base);
                }
            }
        }
        long now = level.getGameTime();
        Incursion incursion = new Incursion(data().nextId(), Incursion.Kind.THUMPER, pos, Set.of(), Set.of(), thumpers,
                1 + level.random.nextInt(2), now, -1, now + FrontierConfig.incursions().waveDelay() / 3);
        data().incursions().add(incursion);
        EnginesAndEmpiresMod.LOGGER.info("Thumping called up an incursion at {} against {} thumper(s)", pos, thumpers.size());
        return incursion;
    }

    /** Ends every incursion under way: whatever is left retreats. */
    public int stopAll() {
        int n = 0;
        for (Incursion incursion : active()) {
            incursion.end(level, Incursion.Result.RETREAT);
            n++;
        }
        return n;
    }

    /** The next dawn after {@code dayTime}, in day time. */
    public static long nextDawn(long dayTime) {
        long dawn = dayTime - dayTime % 24000 + DAWN;
        return dawn <= dayTime ? dawn + 24000 : dawn;
    }
}
