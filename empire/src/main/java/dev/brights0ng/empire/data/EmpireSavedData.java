// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.EmpireState;
import dev.brights0ng.empire.rules.StakeResult;
import dev.brights0ng.empire.rules.UnstakeResult;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

public final class EmpireSavedData extends SavedData {

    public static final String FILE_NAME = "empire_empires";

    private final Map<UUID, EmpireRecord> empires = new HashMap<>();
    private final Map<UUID, UUID> founderToEmpire = new HashMap<>();
    private final Long2ObjectMap<UUID> chunkIndex = new Long2ObjectOpenHashMap<>();
    private final BonfireRegistry bonfires = new BonfireRegistry();

    public static EmpireSavedData of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(EmpireSavedData::new, EmpireSavedData::load, null),
                FILE_NAME);
    }

    public static EmpireSavedData of(ServerLevel level) {
        return of(level.getServer());
    }

    public void rebuildChunkIndex() {
        chunkIndex.clear();
        founderToEmpire.clear();
        for (EmpireRecord record : empires.values()) {
            founderToEmpire.put(record.founder(), record.id());
            for (long chunk : record.state().staked()) {
                chunkIndex.put(chunk, record.id());
            }
        }
    }

    public EmpireRecord createEmpire(UUID founder, long centerChunk, @Nullable BlockPos mainBonfire, long gameTime) {
        UUID empireId = UUID.randomUUID();
        EmpireState state = new EmpireState();
        state.found(centerChunk);
        EmpireRecord record = new EmpireRecord(empireId, founder, state, mainBonfire != null ? mainBonfire.immutable() : null, gameTime);

        empires.put(empireId, record);
        founderToEmpire.put(founder, empireId);
        for (long chunk : state.staked()) {
            chunkIndex.put(chunk, empireId);
        }
        if (mainBonfire != null) {
            bonfires.add(mainBonfire);
        }
        setDirty();
        return record;
    }

    public StakeResult stakeChunk(EmpireRecord record, long chunk, long day, boolean tierOk) {
        boolean ownedElsewhere = chunkIndex.containsKey(chunk) && !record.id().equals(chunkIndex.get(chunk));
        StakeResult result = record.state().stake(chunk, day, tierOk, ownedElsewhere);
        if (result == StakeResult.OK) {
            chunkIndex.put(chunk, record.id());
            setDirty();
        }
        return result;
    }

    public UnstakeResult unstakeChunk(EmpireRecord record, long chunk, long day) {
        long coreChunk = record.mainBonfire() != null
                ? ChunkKeys.pack(record.mainBonfire().getX() >> 4, record.mainBonfire().getZ() >> 4)
                : record.state().foundingChunk();
        UnstakeResult result = record.state().unstake(chunk, day, coreChunk);
        if (result == UnstakeResult.OK) {
            chunkIndex.remove(chunk);
            setDirty();
        }
        return result;
    }

    public void disbandEmpire(UUID empireId, @Nullable MinecraftServer server) {
        EmpireRecord record = empires.remove(empireId);
        if (record != null) {
            founderToEmpire.remove(record.founder());
            for (long chunk : record.state().staked()) {
                chunkIndex.remove(chunk);
            }
            setDirty();
            if (server != null) {
                ServerPlayer player = server.getPlayerList().getPlayer(record.founder());
                if (player != null) {
                    player.sendSystemMessage(Component.translatable("message.empire.disbanded"));
                }
            }
        }
    }

    public void onBonfirePlaced(BlockPos pos, @Nullable MinecraftServer server) {
        bonfires.add(pos);
        long chunk = ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
        UUID empireId = chunkIndex.get(chunk);
        if (empireId != null) {
            EmpireRecord record = empires.get(empireId);
            if (record != null && record.mainBonfire() == null) {
                record.setMainBonfire(pos.immutable());
                notifyMainBonfireChange(record, server, pos);
            }
        }
        setDirty();
    }

    public void onBonfireRemoved(BlockPos pos, @Nullable MinecraftServer server) {
        bonfires.remove(pos);
        for (EmpireRecord record : empires.values()) {
            if (pos.equals(record.mainBonfire())) {
                BlockPos bestCandidate = null;
                double bestDistSq = Double.MAX_VALUE;

                for (BlockPos candidate : bonfires.all()) {
                    long cChunk = ChunkKeys.pack(candidate.getX() >> 4, candidate.getZ() >> 4);
                    if (record.id().equals(chunkIndex.get(cChunk))) {
                        double distSq = candidate.distSqr(pos);
                        if (distSq < bestDistSq || (distSq == bestDistSq && (bestCandidate == null || candidate.asLong() < bestCandidate.asLong()))) {
                            bestCandidate = candidate;
                            bestDistSq = distSq;
                        }
                    }
                }

                record.setMainBonfire(bestCandidate);
                notifyMainBonfireChange(record, server, bestCandidate);
            }
        }
        setDirty();
    }

    private void notifyMainBonfireChange(EmpireRecord record, @Nullable MinecraftServer server, @Nullable BlockPos newMain) {
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(record.founder());
        if (player != null) {
            if (newMain != null) {
                player.sendSystemMessage(Component.translatable("message.empire.main_bonfire_changed", newMain.getX(), newMain.getY(), newMain.getZ()));
            } else {
                player.sendSystemMessage(Component.translatable("message.empire.main_bonfire_lost"));
            }
        }
    }

    @Nullable
    public EmpireRecord getEmpire(UUID empireId) {
        return empires.get(empireId);
    }

    @Nullable
    public EmpireRecord getEmpireByFounder(UUID founder) {
        UUID empireId = founderToEmpire.get(founder);
        return empireId != null ? empires.get(empireId) : null;
    }

    @Nullable
    public EmpireRecord getEmpireByChunk(long chunk) {
        UUID empireId = chunkIndex.get(chunk);
        return empireId != null ? empires.get(empireId) : null;
    }

    public boolean isChunkStaked(long chunk) {
        return chunkIndex.containsKey(chunk);
    }

    public Collection<EmpireRecord> allEmpires() {
        return Collections.unmodifiableCollection(empires.values());
    }

    public BonfireRegistry bonfires() {
        return bonfires;
    }

    public static EmpireSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        EmpireSavedData data = new EmpireSavedData();
        ListTag empiresTag = tag.getList("empires", Tag.TAG_COMPOUND);
        for (int i = 0; i < empiresTag.size(); i++) {
            CompoundTag recordTag = empiresTag.getCompound(i);
            EmpireRecord record = EmpireRecord.load(recordTag);
            data.empires.put(record.id(), record);
        }
        data.rebuildChunkIndex();
        if (tag.contains("bonfires", Tag.TAG_COMPOUND)) {
            data.bonfires.load(tag.getCompound("bonfires"));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);
        ListTag empiresTag = new ListTag();
        for (EmpireRecord record : empires.values()) {
            empiresTag.add(record.save());
        }
        tag.put("empires", empiresTag);
        CompoundTag bonfiresTag = new CompoundTag();
        bonfires.save(bonfiresTag);
        tag.put("bonfires", bonfiresTag);
        return tag;
    }
}
