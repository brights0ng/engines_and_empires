package dev.brights0ng.enginesandempires.frontier.incursion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.LongPredicate;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * What incursions remember, saved per dimension ({@code data/engines_and_empires_incursions.dat}):
 * <ul>
 *   <li>the thumper shots of the last day and the loud machines that ran in it (for settlements' scores);</li>
 *   <li>which chunk columns are immune to nightly incursions, and until when (10 days after one);</li>
 *   <li>the last night incursions were rolled for, so a night is only rolled once;</li>
 *   <li>the incursions under way, so they carry on after a restart.</li>
 * </ul>
 */
public final class IncursionData extends SavedData {

    static final String NAME = "engines_and_empires_incursions";
    /** How long shots and machine runs count toward a score: a day. */
    public static final long MEMORY = 24000;

    public record Shot(long pos, long time, double weight) {
    }

    public record Run(long lastRun, double weight) {
    }

    private final List<Shot> shots = new ArrayList<>();
    private final Long2ObjectOpenHashMap<Run> runs = new Long2ObjectOpenHashMap<>();
    private final Long2LongOpenHashMap immuneUntil = new Long2LongOpenHashMap();
    private final List<Incursion> incursions = new ArrayList<>();
    private long lastRolledNight = Long.MIN_VALUE;
    private int nextId = 1;

    public static IncursionData of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(IncursionData::new, IncursionData::load, null), NAME);
    }

    // ---- scores ----

    public void recordShot(long pos, double weight, long now) {
        prune(now);
        shots.add(new Shot(pos, now, weight));
        setDirty();
    }

    public void machineRan(long pos, double weight, long now) {
        Run old = runs.get(pos);
        if (old == null || now - old.lastRun() >= 1200 || old.weight() != weight) {
            setDirty(); // saving every run would be pointless churn: a minute's accuracy is plenty
        }
        runs.put(pos, new Run(now, weight));
    }

    /** The weight of the shots in the last day whose position {@code inside} accepts. */
    public double shotScore(LongPredicate inside, long now) {
        prune(now);
        double total = 0;
        for (Shot shot : shots) {
            if (inside.test(shot.pos())) {
                total += shot.weight();
            }
        }
        return total;
    }

    /** The weight of the loud machines that ran in the last day, whose position {@code inside} accepts. */
    public double machineScore(LongPredicate inside, long now) {
        prune(now);
        double total = 0;
        for (Long2ObjectMap.Entry<Run> entry : runs.long2ObjectEntrySet()) {
            if (inside.test(entry.getLongKey())) {
                total += entry.getValue().weight();
            }
        }
        return total;
    }

    private void prune(long now) {
        if (shots.removeIf(s -> now - s.time() >= MEMORY) | runs.values().removeIf(r -> now - r.lastRun() >= MEMORY)) {
            setDirty();
        }
    }

    // ---- immunity ----

    public void makeImmune(Collection<Long> columns, long until) {
        for (long column : columns) {
            immuneUntil.put(column, Math.max(immuneUntil.get(column), until));
        }
        setDirty();
    }

    /** Whether any of {@code columns} is immune at {@code now}; returns the latest time it is immune until, or 0. */
    public long immuneUntil(Collection<Long> columns, long now) {
        immuneUntil.values().removeIf(until -> until <= now);
        long latest = 0;
        for (long column : columns) {
            latest = Math.max(latest, immuneUntil.get(column));
        }
        return latest;
    }

    public void clearImmunity() {
        immuneUntil.clear();
        setDirty();
    }

    // ---- nights ----

    public long lastRolledNight() {
        return lastRolledNight;
    }

    public void setLastRolledNight(long night) {
        lastRolledNight = night;
        setDirty();
    }

    // ---- incursions under way ----

    public List<Incursion> incursions() {
        return incursions;
    }

    public int nextId() {
        setDirty();
        return nextId++;
    }

    // ---- saving ----

    static IncursionData load(CompoundTag tag, HolderLookup.Provider registries) {
        IncursionData data = new IncursionData();
        ListTag shotList = tag.getList("shots", Tag.TAG_COMPOUND);
        for (int i = 0; i < shotList.size(); i++) {
            CompoundTag s = shotList.getCompound(i);
            data.shots.add(new Shot(s.getLong("pos"), s.getLong("time"), s.getDouble("weight")));
        }
        ListTag runList = tag.getList("runs", Tag.TAG_COMPOUND);
        for (int i = 0; i < runList.size(); i++) {
            CompoundTag r = runList.getCompound(i);
            data.runs.put(r.getLong("pos"), new Run(r.getLong("last"), r.getDouble("weight")));
        }
        long[] columns = tag.getLongArray("immuneColumns");
        long[] until = tag.getLongArray("immuneUntil");
        for (int i = 0; i < columns.length && i < until.length; i++) {
            data.immuneUntil.put(columns[i], until[i]);
        }
        data.lastRolledNight = tag.contains("lastRolledNight") ? tag.getLong("lastRolledNight") : Long.MIN_VALUE;
        data.nextId = Math.max(1, tag.getInt("nextId"));
        ListTag incursionList = tag.getList("incursions", Tag.TAG_COMPOUND);
        for (int i = 0; i < incursionList.size(); i++) {
            data.incursions.add(Incursion.load(incursionList.getCompound(i)));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag shotList = new ListTag();
        for (Shot shot : shots) {
            CompoundTag s = new CompoundTag();
            s.putLong("pos", shot.pos());
            s.putLong("time", shot.time());
            s.putDouble("weight", shot.weight());
            shotList.add(s);
        }
        tag.put("shots", shotList);
        ListTag runList = new ListTag();
        for (Long2ObjectMap.Entry<Run> entry : runs.long2ObjectEntrySet()) {
            CompoundTag r = new CompoundTag();
            r.putLong("pos", entry.getLongKey());
            r.putLong("last", entry.getValue().lastRun());
            r.putDouble("weight", entry.getValue().weight());
            runList.add(r);
        }
        tag.put("runs", runList);
        tag.putLongArray("immuneColumns", immuneUntil.keySet().toLongArray());
        tag.putLongArray("immuneUntil", immuneUntil.values().toLongArray());
        tag.putLong("lastRolledNight", lastRolledNight);
        tag.putInt("nextId", nextId);
        ListTag incursionList = new ListTag();
        for (Incursion incursion : incursions) {
            incursionList.add(incursion.save());
        }
        tag.put("incursions", incursionList);
        return tag;
    }

    /** Incursions change every tick while they run; make sure the latest state is what gets saved. */
    public void markChanged() {
        setDirty();
    }
}
