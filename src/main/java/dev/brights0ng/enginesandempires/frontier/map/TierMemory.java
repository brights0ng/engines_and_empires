package dev.brights0ng.enginesandempires.frontier.map;

import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The last known ground tier of every chunk column (Bright: like a vanilla map, true near you and possibly out of date far
 * away). Only columns better than Frontier are kept, so it stays small. Saved with the Overworld
 * ({@code data/engines_and_empires_tier_map.dat}).
 *
 * <p>A loaded column's tier is worked out afresh whenever it is asked for, and remembered; a column is also remembered as its
 * chunk unloads.
 */
public final class TierMemory extends SavedData {

    static final String NAME = "engines_and_empires_tier_map";

    private final Long2ByteOpenHashMap tiers = new Long2ByteOpenHashMap();

    public static TierMemory of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(TierMemory::new, TierMemory::load, null), NAME);
    }

    /** The ground tier of column ({@code cx}, {@code cz}): worked out now if loaded, otherwise as last known. */
    public Tier groundTier(FrontierLevel frontier, int cx, int cz) {
        LevelChunk chunk = frontier.level().getChunkSource().getChunkNow(cx, cz);
        if (chunk != null) {
            Tier tier = frontier.groundTier(chunk, true);
            remember(cx, cz, tier);
            return tier;
        }
        return Tier.byId(tiers.get(ChunkPos.asLong(cx, cz)));
    }

    /** Remembers a column's ground tier. */
    public void remember(int cx, int cz, Tier tier) {
        long key = ChunkPos.asLong(cx, cz);
        byte old = tiers.get(key);
        if (tier == Tier.FRONTIER) {
            if (tiers.containsKey(key)) {
                tiers.remove(key);
                setDirty();
            }
        } else if (old != (byte) tier.ordinal() || !tiers.containsKey(key)) {
            tiers.put(key, (byte) tier.ordinal());
            setDirty();
        }
    }

    public int size() {
        return tiers.size();
    }

    static TierMemory load(CompoundTag tag, HolderLookup.Provider registries) {
        TierMemory memory = new TierMemory();
        long[] columns = tag.getLongArray("columns");
        byte[] values = tag.getByteArray("tiers");
        for (int i = 0; i < columns.length && i < values.length; i++) {
            memory.tiers.put(columns[i], values[i]);
        }
        return memory;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLongArray("columns", tiers.keySet().toLongArray());
        byte[] values = new byte[tiers.size()];
        int i = 0;
        for (long key : tiers.keySet()) {
            values[i++] = tiers.get(key);
        }
        tag.putByteArray("tiers", values);
        return tag;
    }
}
