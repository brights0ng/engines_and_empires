package dev.brights0ng.enginesandempires.frontier.deep;

import java.util.ArrayList;
import java.util.List;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The deposits a thumper shot has stirred: the ones its echo came back off. For a while afterwards (3 days by default) the
 * Deep comes up around them: mobs of the {@code stirred} roster spawn near a stirred deposit while a player is close.
 *
 * <p>Saved per dimension ({@code data/engines_and_empires_stirred.dat}), next to the deposit ledger but separate from it, so
 * the ledger's format is left alone. A deposit is remembered by its seed, with the middle of its ore blocks.
 */
public final class StirredDeposits extends SavedData {

    static final String NAME = "engines_and_empires_stirred";

    public record Stirred(long seed, String oreId, BlockPos centre, long until) {
    }

    private final Long2ObjectOpenHashMap<Stirred> deposits = new Long2ObjectOpenHashMap<>();

    public static StirredDeposits of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(StirredDeposits::new, StirredDeposits::load, null), NAME);
    }

    public void stir(long seed, String oreId, BlockPos centre, long until) {
        Stirred old = deposits.get(seed);
        long keepUntil = old == null ? until : Math.max(old.until(), until);
        deposits.put(seed, new Stirred(seed, oreId, centre, keepUntil));
        setDirty();
    }

    /** The deposits still stirred at {@code now} whose middle is within {@code radius} blocks of {@code pos}. */
    public List<Stirred> near(BlockPos pos, double radius, long now) {
        List<Stirred> found = new ArrayList<>();
        double squared = radius * radius;
        for (Stirred stirred : deposits.values()) {
            if (stirred.until() > now && stirred.centre().distSqr(pos) <= squared) {
                found.add(stirred);
            }
        }
        return found;
    }

    /** Forgets the deposits that have settled down again. */
    public void settle(long now) {
        if (deposits.values().removeIf(stirred -> stirred.until() <= now)) {
            setDirty();
        }
    }

    public int size() {
        return deposits.size();
    }

    static StirredDeposits load(CompoundTag tag, HolderLookup.Provider registries) {
        StirredDeposits data = new StirredDeposits();
        ListTag list = tag.getList("stirred", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long seed = entry.getLong("seed");
            data.deposits.put(seed, new Stirred(seed, entry.getString("ore"), BlockPos.of(entry.getLong("centre")),
                    entry.getLong("until")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Long2ObjectMap.Entry<Stirred> entry : deposits.long2ObjectEntrySet()) {
            Stirred stirred = entry.getValue();
            CompoundTag item = new CompoundTag();
            item.putLong("seed", stirred.seed());
            item.putString("ore", stirred.oreId());
            item.putLong("centre", stirred.centre().asLong());
            item.putLong("until", stirred.until());
            list.add(item);
        }
        tag.put("stirred", list);
        return tag;
    }
}
