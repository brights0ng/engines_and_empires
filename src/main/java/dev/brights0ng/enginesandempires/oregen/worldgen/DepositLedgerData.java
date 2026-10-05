package dev.brights0ng.enginesandempires.oregen.worldgen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.brights0ng.enginesandempires.oregen.DepositLedger;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Saves a dimension's {@link DepositLedger} with the world, so a deposit whose chunks are generated over
 * several play sessions is still recognised as complete when the last one is, and so a deposit that has been
 * mined out is still known to be after a restart.
 *
 * <p>This class only converts between the ledger and NBT. The ledger itself is thread-safe and is what
 * worldgen updates; this is asked to snapshot it when the game saves.
 *
 * <p>Ledgers saved before ore counts were kept have no per-chunk counts. They load fine: the counts are
 * treated as not yet known, and are filled in as chunks next save.
 */
final class DepositLedgerData extends SavedData {

    /** The name of the save file: {@code data/engines_and_empires_deposits.dat} in each dimension's folder. */
    static final String NAME = "engines_and_empires_deposits";

    private final DepositLedger ledger = new DepositLedger();

    static SavedData.Factory<DepositLedgerData> factory() {
        return new SavedData.Factory<>(DepositLedgerData::new, DepositLedgerData::load, null);
    }

    DepositLedger ledger() {
        return ledger;
    }

    static DepositLedgerData load(CompoundTag tag, HolderLookup.Provider registries) {
        DepositLedgerData data = new DepositLedgerData();
        List<DepositLedger.EntryView> views = new ArrayList<>();
        ListTag list = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            long[] chunks = entry.getLongArray("chunks");
            byte[] doneBytes = entry.getByteArray("done");
            boolean[] done = new boolean[doneBytes.length];
            for (int j = 0; j < done.length; j++) {
                done[j] = doneBytes[j] != 0;
            }
            int[] cells = entry.contains("cells") ? entry.getIntArray("cells") : new int[0];
            int[] remaining;
            if (entry.contains("remaining")) {
                remaining = entry.getIntArray("remaining");
            } else {
                remaining = new int[chunks.length];
                Arrays.fill(remaining, -1);
            }
            views.add(new DepositLedger.EntryView(
                    entry.getLong("seed"), entry.getString("ore"), entry.getInt("x"), entry.getInt("z"),
                    entry.getInt("y"), entry.getInt("attempt"), entry.getInt("expected"), entry.getFloat("solid"),
                    chunks, done, entry.getInt("placed"), stateOf(entry.getString("state")),
                    entry.getLong("order"), cells, remaining, entry.getBoolean("depleted")));
        }
        data.ledger.restore(views);
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (DepositLedger.EntryView view : ledger.snapshot()) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("seed", view.seed());
            entry.putString("ore", view.oreId());
            entry.putInt("x", view.x());
            entry.putInt("z", view.z());
            entry.putInt("y", view.centerY());
            entry.putInt("attempt", view.attempt());
            entry.putInt("expected", view.expectedOre());
            entry.putFloat("solid", view.solidFraction());
            entry.putLongArray("chunks", view.expectedChunks());
            byte[] done = new byte[view.done().length];
            for (int i = 0; i < done.length; i++) {
                done[i] = (byte) (view.done()[i] ? 1 : 0);
            }
            entry.putByteArray("done", done);
            entry.putInt("placed", view.placed());
            entry.putString("state", view.state().name());
            entry.putLong("order", view.order());
            entry.putIntArray("cells", view.cells());
            entry.putIntArray("remaining", view.remaining());
            entry.putBoolean("depleted", view.depleted());
            list.add(entry);
        }
        tag.put("entries", list);
        return tag;
    }

    /** An unrecognised saved state is treated as in progress, which is always safe. */
    private static DepositLedger.State stateOf(String name) {
        try {
            return DepositLedger.State.valueOf(name);
        } catch (IllegalArgumentException e) {
            return DepositLedger.State.IN_PROGRESS;
        }
    }
}
