// Written by Gemini; founding meat count and the divine area added by Claude.
package dev.brights0ng.empire.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class EmpireState {

    public static final int FOUNDING_DOMINION = 10;
    public static final int DOMINION_PER_SACRIFICE = 5;
    /** How many of one god's founding meat found an empire under it, or give a godless empire that god. */
    public static final int FOUNDING_MEAT = 5;
    public static final int FOUNDING_RADIUS = 1;

    public record Dormant(long availableOnDay) {}

    private int dominion;
    private final Set<Long> staked;
    private final List<Dormant> dormant;
    private long foundingChunk;

    public EmpireState() {
        this.dominion = 0;
        this.staked = new HashSet<>();
        this.dormant = new ArrayList<>();
        this.foundingChunk = 0L;
    }

    public EmpireState(int dominion, long foundingChunk, Set<Long> staked, List<Dormant> dormant) {
        this.dominion = dominion;
        this.foundingChunk = foundingChunk;
        this.staked = new HashSet<>(staked);
        this.dormant = new ArrayList<>(dormant);
    }

    public int dominion() {
        return dominion;
    }

    public void setDominion(int dominion) {
        this.dominion = Math.max(0, dominion);
    }

    public void addDominion(int amount) {
        this.dominion += amount;
    }

    public long foundingChunk() {
        return foundingChunk;
    }

    public Set<Long> staked() {
        return Collections.unmodifiableSet(staked);
    }

    public int stakedCount() {
        return staked.size();
    }

    public List<Dormant> dormant() {
        return Collections.unmodifiableList(dormant);
    }

    public void releaseDueDormant(long day) {
        dormant.removeIf(d -> d.availableOnDay() <= day);
    }

    public int available(long day) {
        releaseDueDormant(day);
        return Math.max(0, dominion - staked.size() - dormant.size());
    }

    public void found(long centerChunk) {
        this.dominion = FOUNDING_DOMINION;
        this.foundingChunk = centerChunk;
        this.staked.clear();
        this.dormant.clear();

        int cx = ChunkKeys.getX(centerChunk);
        int cz = ChunkKeys.getZ(centerChunk);
        for (int dx = -FOUNDING_RADIUS; dx <= FOUNDING_RADIUS; dx++) {
            for (int dz = -FOUNDING_RADIUS; dz <= FOUNDING_RADIUS; dz++) {
                this.staked.add(ChunkKeys.pack(cx + dx, cz + dz));
            }
        }
    }

    public StakeResult stake(long chunk, long day, boolean tierOk, boolean ownedElsewhere) {
        if (staked.contains(chunk)) {
            return StakeResult.ALREADY_OURS;
        }
        if (ownedElsewhere) {
            return StakeResult.OWNED_BY_OTHER;
        }
        if (!ClaimGraph.isAdjacent(staked, chunk)) {
            return StakeResult.NOT_ADJACENT;
        }
        if (!tierOk) {
            return StakeResult.NOT_SETTLED;
        }
        if (available(day) <= 0) {
            return StakeResult.NO_DOMINION;
        }

        staked.add(chunk);
        return StakeResult.OK;
    }

    public UnstakeResult unstake(long chunk, long day, long coreChunk) {
        if (!staked.contains(chunk)) {
            return UnstakeResult.NOT_OURS;
        }
        if (chunk == coreChunk) {
            return UnstakeResult.CORE_CHUNK;
        }
        if (Math.abs(ChunkKeys.getX(chunk) - ChunkKeys.getX(coreChunk)) <= 1
                && Math.abs(ChunkKeys.getZ(chunk) - ChunkKeys.getZ(coreChunk)) <= 1) {
            return UnstakeResult.DIVINE_CHUNK;
        }
        if (ClaimGraph.isBridge(staked, chunk)) {
            return UnstakeResult.BRIDGE_CHUNK;
        }

        staked.remove(chunk);
        dormant.add(new Dormant(day + 1L));
        return UnstakeResult.OK;
    }

    public void forciblyDestake(long chunk) {
        if (staked.remove(chunk)) {
            dominion = Math.max(0, dominion - 1);
        }
    }
}
