package dev.brights0ng.enginesandempires.frontier.guard;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Keeps track of the guards in a level and which of them are free to move ({@link GuardFreedom}), counted per section, for
 * Civilized land: an area with 5 free guards in it (and Settled already) is Civilized.
 *
 * <p>Guards are picked up as they join the level and dropped as they leave. Every second their sections are brought up to
 * date; every couple of minutes each is checked again for being free, a few per tick so it never costs much at once.
 */
public final class GuardTracker {

    private static final int POSITION_INTERVAL = 20;
    private static final int CHECKS_PER_TICK = 8;

    private final FrontierLevel frontier;
    private final ServerLevel level;
    private final Int2ObjectOpenHashMap<Tracked> guards = new Int2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap freeBySection = new Long2IntOpenHashMap();

    private static final class Tracked {
        final Entity entity;
        long section = Long.MIN_VALUE;
        /** The section its free-guard count is in, or {@code Long.MIN_VALUE} if it is not counted anywhere. */
        long countedIn = Long.MIN_VALUE;
        boolean free;
        int reachable;
        long checkedAt = Long.MIN_VALUE;

        Tracked(Entity entity) {
            this.entity = entity;
        }
    }

    public GuardTracker(FrontierLevel frontier) {
        this.frontier = frontier;
        this.level = frontier.level();
    }

    public void onJoin(Entity entity) {
        if (GuardTypes.isCandidate(entity)) {
            guards.putIfAbsent(entity.getId(), new Tracked(entity));
        }
    }

    public void onLeave(Entity entity) {
        Tracked tracked = guards.remove(entity.getId());
        if (tracked != null) {
            place(tracked, Long.MIN_VALUE, false);
        }
    }

    /** Free guards standing in a section. */
    public int freeIn(long section) {
        return freeBySection.get(section);
    }

    public void tick() {
        long now = level.getGameTime();
        if (now % POSITION_INTERVAL != 0) {
            return;
        }
        long interval = FrontierConfig.guardCheckInterval();
        int checks = CHECKS_PER_TICK * POSITION_INTERVAL;
        List<Tracked> gone = new ArrayList<>();
        for (Tracked tracked : guards.values()) {
            if (tracked.entity.isRemoved()) {
                gone.add(tracked);
                continue;
            }
            long section = FrontierLevel.keyOf(tracked.entity.blockPosition());
            if (checks > 0 && now - tracked.checkedAt >= interval) {
                checks--;
                check(tracked, now);
            }
            place(tracked, section, tracked.free);
        }
        for (Tracked tracked : gone) {
            guards.remove(tracked.entity.getId());
            place(tracked, Long.MIN_VALUE, false);
        }
    }

    /** Checks a guard now (the debug command and tests). Returns how many spots it can reach, up to what counts as free. */
    public int checkNow(Entity entity) {
        onJoin(entity);
        Tracked tracked = guards.get(entity.getId());
        if (tracked == null) {
            return 0;
        }
        check(tracked, level.getGameTime());
        place(tracked, FrontierLevel.keyOf(entity.blockPosition()), tracked.free);
        return tracked.reachable;
    }

    /** Every tracked guard within {@code radius} blocks: the entity, whether it is free, and how far it could get. */
    public List<Report> near(BlockPos centre, double radius) {
        List<Report> found = new ArrayList<>();
        for (Tracked tracked : guards.values()) {
            if (tracked.entity.blockPosition().closerThan(centre, radius)) {
                found.add(new Report(tracked.entity, GuardTypes.isGuard(tracked.entity), tracked.free, tracked.reachable));
            }
        }
        return found;
    }

    public record Report(Entity entity, boolean guard, boolean free, int reachable) {
    }

    private void check(Tracked tracked, long now) {
        tracked.checkedAt = now;
        if (!GuardTypes.isGuard(tracked.entity)) {
            tracked.free = false;
            tracked.reachable = 0;
            return;
        }
        BlockPos at = tracked.entity.blockPosition();
        int needed = FrontierConfig.guardFreeArea();
        tracked.reachable = GuardFreedom.reachable(GuardTypes.ground(level), at.getX(), at.getY(), at.getZ(), needed,
                needed * 8);
        tracked.free = tracked.reachable >= needed;
    }

    /** Moves a guard's count to {@code section} (or none), and has the land around re-tiered if anything changed. */
    private void place(Tracked tracked, long section, boolean free) {
        long oldSection = tracked.countedIn;
        boolean nowCounted = free && section != Long.MIN_VALUE;
        if (oldSection == (nowCounted ? section : Long.MIN_VALUE)) {
            tracked.section = section;
            return;
        }
        if (oldSection != Long.MIN_VALUE) {
            int left = freeBySection.get(oldSection) - 1;
            if (left <= 0) {
                freeBySection.remove(oldSection);
            } else {
                freeBySection.put(oldSection, left);
            }
            frontier.markAreaDirty(oldSection);
        }
        if (nowCounted) {
            freeBySection.addTo(section, 1);
            frontier.markAreaDirty(section);
        }
        tracked.countedIn = nowCounted ? section : Long.MIN_VALUE;
        tracked.section = section;
    }
}
