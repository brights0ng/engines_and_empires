package dev.brights0ng.enginesandempires.frontier.tier;

import java.util.Arrays;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.common.util.INBTSerializable;

/**
 * What Frontier keeps about one chunk, saved with it as a data attachment: for each of its sections, the anchor blocks in
 * it and how inhabited it is, and when each of its torches was lit. The tiers themselves are not saved; they are worked out
 * again from this.
 *
 * <p>Sections are indexed from the bottom of the world (index 0 is the chunk's lowest section).
 */
public final class FrontierChunkData implements INBTSerializable<CompoundTag> {

    private int minSection;
    private int[] anchors;
    private int[] habitation;
    private long[] lastInhabited;
    private long[] residentSeen;
    /** Whether the chunk's blocks have been counted yet. Until they have, block changes are not counted either. */
    private boolean scanned;
    /** Each torch's position ({@code BlockPos.asLong}) and the game time it was lit. */
    private final Long2LongOpenHashMap torches = new Long2LongOpenHashMap();
    /** Whether the chunk's torches have been found yet (chunks counted before torches burnt out have not). */
    private boolean torchesScanned;

    public FrontierChunkData(int minSection, int sections) {
        this.minSection = minSection;
        allocate(sections);
    }

    private void allocate(int sections) {
        anchors = new int[sections];
        habitation = new int[sections];
        lastInhabited = new long[sections];
        residentSeen = new long[sections];
        Arrays.fill(lastInhabited, Habitation.NEVER);
        Arrays.fill(residentSeen, Habitation.NEVER);
    }

    public int minSection() {
        return minSection;
    }

    public int sections() {
        return anchors.length;
    }

    /** The index of the section at section-y {@code sectionY}, or -1 if the chunk has none there. */
    public int index(int sectionY) {
        int i = sectionY - minSection;
        return i >= 0 && i < anchors.length ? i : -1;
    }

    public boolean scanned() {
        return scanned;
    }

    public void setScanned(int[] counted) {
        System.arraycopy(counted, 0, anchors, 0, Math.min(counted.length, anchors.length));
        scanned = true;
    }

    public int anchors(int i) {
        return anchors[i];
    }

    public void addAnchors(int i, int delta) {
        anchors[i] = Math.max(0, anchors[i] + delta);
    }

    public int storedHabitation(int i) {
        return habitation[i];
    }

    public long lastInhabited(int i) {
        return lastInhabited[i];
    }

    public int habitation(int i, long now, TierParams params) {
        return Habitation.effective(habitation[i], lastInhabited[i], now, params);
    }

    /**
     * Someone is about at {@code now}: credits the time since the section was last credited, at most {@code upTo} ticks,
     * times {@code multiplier}. However many people are about, a section gains no more than the time that really passed.
     *
     * @return the value from before (for spotting big enough changes), or -1 if nothing was added
     */
    public int creditHabitation(int i, int upTo, int multiplier, long now, TierParams params) {
        long elapsed = Habitation.elapsedSince(lastInhabited[i], now, upTo);
        if (elapsed <= 0) {
            return -1;
        }
        return addHabitation(i, (int) elapsed * multiplier, now, params);
    }

    /** Adds inhabited time outright (the debug command and tests), and returns the value from before. */
    public int addHabitation(int i, int amount, long now, TierParams params) {
        int before = habitation(i, now, params);
        habitation[i] = Habitation.add(habitation[i], lastInhabited[i], now, amount, params);
        lastInhabited[i] = now;
        return before;
    }

    public long residentSeen(int i) {
        return residentSeen[i];
    }

    public void setResidentSeen(int i, long now) {
        residentSeen[i] = now;
    }

    /** Forgets all inhabited time and residents (for tests and the debug command). Anchors are real blocks, so they stay. */
    public void clearHabitation() {
        Arrays.fill(habitation, 0);
        Arrays.fill(lastInhabited, Habitation.NEVER);
        Arrays.fill(residentSeen, Habitation.NEVER);
    }

    // Torches ----------------------------------------------------------------------------------------------------------

    public boolean torchesScanned() {
        return torchesScanned;
    }

    public void setTorchesScanned(long[] found, long litAt) {
        torches.clear();
        for (long pos : found) {
            torches.put(pos, litAt);
        }
        torchesScanned = true;
    }

    /** Each torch and when it was lit. Changes to the entries' values write through. */
    public Long2LongMap torches() {
        return torches;
    }

    public void torchLit(long pos, long litAt) {
        torches.put(pos, litAt);
    }

    public boolean torchRemoved(long pos) {
        boolean had = torches.containsKey(pos);
        torches.remove(pos);
        return had;
    }

    public boolean hasTorches() {
        return !torches.isEmpty();
    }

    public boolean hasAnything() {
        for (int i = 0; i < anchors.length; i++) {
            if (anchors[i] > 0 || habitation[i] > 0 || residentSeen[i] != Habitation.NEVER) {
                return true;
            }
        }
        return false;
    }

    @Override
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("minSection", minSection);
        tag.putBoolean("scanned", scanned);
        tag.putIntArray("anchors", anchors);
        tag.putIntArray("habitation", habitation);
        tag.putLongArray("lastInhabited", lastInhabited);
        tag.putLongArray("residentSeen", residentSeen);
        tag.putBoolean("torchesScanned", torchesScanned);
        long[] positions = new long[torches.size()];
        long[] lit = new long[torches.size()];
        int n = 0;
        for (Long2LongMap.Entry entry : torches.long2LongEntrySet()) {
            positions[n] = entry.getLongKey();
            lit[n] = entry.getLongValue();
            n++;
        }
        tag.putLongArray("torchPos", positions);
        tag.putLongArray("torchLit", lit);
        return tag;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        int savedMin = tag.getInt("minSection");
        int[] savedAnchors = tag.getIntArray("anchors");
        int[] savedHabitation = tag.getIntArray("habitation");
        long[] savedLast = tag.getLongArray("lastInhabited");
        long[] savedResident = tag.getLongArray("residentSeen");
        // The world's height may have changed since this was saved: keep what still lines up, by world height.
        for (int saved = 0; saved < savedAnchors.length; saved++) {
            int i = index(savedMin + saved);
            if (i < 0) {
                continue;
            }
            anchors[i] = savedAnchors[saved];
            if (saved < savedHabitation.length) {
                habitation[i] = savedHabitation[saved];
            }
            if (saved < savedLast.length) {
                lastInhabited[i] = savedLast[saved];
            }
            if (saved < savedResident.length) {
                residentSeen[i] = savedResident[saved];
            }
        }
        scanned = tag.getBoolean("scanned") && savedMin == minSection && savedAnchors.length == anchors.length;
        long[] positions = tag.getLongArray("torchPos");
        long[] lit = tag.getLongArray("torchLit");
        torches.clear();
        for (int i = 0; i < positions.length && i < lit.length; i++) {
            torches.put(positions[i], lit[i]);
        }
        torchesScanned = tag.getBoolean("torchesScanned");
    }
}
