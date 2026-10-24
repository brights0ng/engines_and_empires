// Written by Gemini; members, the main bonfire's 3x3 rule, the public core chunk and the divine index by Claude.
package dev.brights0ng.empire.data;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.ClaimGraph;
import dev.brights0ng.empire.rules.DivineArea;
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
    private final Map<UUID, UUID> memberToEmpire = new HashMap<>();
    private final Long2ObjectMap<UUID> chunkIndex = new Long2ObjectOpenHashMap<>();
    /** Each empire's divine area (the 3x3 around its core chunk), chunk to empire. Not saved: rebuilt from the records. */
    private final Long2ObjectMap<UUID> divineIndex = new Long2ObjectOpenHashMap<>();
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
        memberToEmpire.clear();
        for (EmpireRecord record : empires.values()) {
            for (UUID member : record.members()) {
                memberToEmpire.put(member, record.id());
            }
            for (long chunk : record.state().staked()) {
                chunkIndex.put(chunk, record.id());
            }
        }
        rebuildDivineIndex();
    }

    /** Rebuilds the divine index; called on load and whenever an empire's core chunk can change. */
    public void rebuildDivineIndex() {
        divineIndex.clear();
        for (EmpireRecord record : empires.values()) {
            for (long chunk : DivineArea.chunks(coreChunk(record))) {
                divineIndex.putIfAbsent(chunk, record.id());
            }
        }
    }

    public EmpireRecord createEmpire(UUID founder, long centerChunk, @Nullable BlockPos mainBonfire, long gameTime) {
        UUID empireId = UUID.randomUUID();
        EmpireState state = new EmpireState();
        state.found(centerChunk);
        EmpireRecord record = new EmpireRecord(empireId, List.of(founder), state, mainBonfire != null ? mainBonfire.immutable() : null, gameTime);

        empires.put(empireId, record);
        memberToEmpire.put(founder, empireId);
        for (long chunk : state.staked()) {
            chunkIndex.put(chunk, empireId);
        }
        if (mainBonfire != null) {
            bonfires.add(mainBonfire);
        }
        rebuildDivineIndex();
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

    /**
     * Adds {@code player} to the empire. Returns false, changing nothing, if they already belong to an empire (this one
     * or another).
     */
    public boolean addMember(EmpireRecord record, UUID player) {
        if (memberToEmpire.containsKey(player)) {
            return false;
        }
        record.addMember(player);
        memberToEmpire.put(player, record.id());
        setDirty();
        return true;
    }

    /**
     * Removes {@code player} from the empire. The last member leaving keeps the empire (frozen, with its land, dominion,
     * god, demand and bonfires) for anyone to join. Returns whether they were a member.
     */
    public boolean removeMember(EmpireRecord record, UUID player) {
        if (!record.removeMember(player)) {
            return false;
        }
        memberToEmpire.remove(player, record.id());
        setDirty();
        return true;
    }

    /**
     * The empire's core chunk: its main bonfire's chunk, else its founding chunk. The 3x3 centred on it is the divine
     * area, which cannot be unstaked.
     */
    public static long coreChunk(EmpireRecord record) {
        BlockPos main = record.mainBonfire();
        return main != null ? ChunkKeys.pack(main.getX() >> 4, main.getZ() >> 4) : record.state().foundingChunk();
    }

    public UnstakeResult unstakeChunk(EmpireRecord record, long chunk, long day) {
        UnstakeResult result = record.state().unstake(chunk, day, coreChunk(record));
        if (result == UnstakeResult.OK) {
            chunkIndex.remove(chunk);
            setDirty();
        }
        return result;
    }

    public void disbandEmpire(UUID empireId, @Nullable MinecraftServer server) {
        EmpireRecord record = empires.remove(empireId);
        if (record != null) {
            for (UUID member : record.members()) {
                memberToEmpire.remove(member, empireId);
            }
            for (long chunk : record.state().staked()) {
                chunkIndex.remove(chunk);
            }
            rebuildDivineIndex();
            setDirty();
            messageMembers(record, server, Component.translatable("message.empire.disbanded"));
        }
    }

    public void onBonfirePlaced(BlockPos pos, @Nullable MinecraftServer server) {
        bonfires.add(pos);
        long chunk = ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
        UUID empireId = chunkIndex.get(chunk);
        if (empireId != null) {
            EmpireRecord record = empires.get(empireId);
            if (record != null && record.mainBonfire() == null && eligibleMain(record, pos)) {
                record.setMainBonfire(pos.immutable());
                rebuildDivineIndex();
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
                    if (record.id().equals(chunkIndex.get(cChunk)) && eligibleMain(record, candidate)) {
                        double distSq = candidate.distSqr(pos);
                        if (distSq < bestDistSq || (distSq == bestDistSq && (bestCandidate == null || candidate.asLong() < bestCandidate.asLong()))) {
                            bestCandidate = candidate;
                            bestDistSq = distSq;
                        }
                    }
                }

                record.setMainBonfire(bestCandidate);
                rebuildDivineIndex();
                notifyMainBonfireChange(record, server, bestCandidate);
            }
        }
        setDirty();
    }

    /** A bonfire can be the main one only if the empire has staked all 9 chunks of the 3x3 centred on its chunk. */
    private static boolean eligibleMain(EmpireRecord record, BlockPos pos) {
        return ClaimGraph.surroundingStaked(record.state().staked(), ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
    }

    private void notifyMainBonfireChange(EmpireRecord record, @Nullable MinecraftServer server, @Nullable BlockPos newMain) {
        messageMembers(record, server, newMain != null
                ? Component.translatable("message.empire.main_bonfire_changed", newMain.getX(), newMain.getY(), newMain.getZ())
                : Component.translatable("message.empire.main_bonfire_lost"));
    }

    /** Sends a chat message to every member who is online. */
    public static void messageMembers(EmpireRecord record, @Nullable MinecraftServer server, Component message) {
        if (server == null) {
            return;
        }
        for (UUID member : record.members()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                player.sendSystemMessage(message);
            }
        }
    }

    @Nullable
    public EmpireRecord getEmpire(UUID empireId) {
        return empires.get(empireId);
    }

    /** The empire {@code player} belongs to, or null. */
    @Nullable
    public EmpireRecord getEmpireOf(UUID player) {
        UUID empireId = memberToEmpire.get(player);
        return empireId != null ? empires.get(empireId) : null;
    }

    @Nullable
    public EmpireRecord getEmpireByChunk(long chunk) {
        UUID empireId = chunkIndex.get(chunk);
        return empireId != null ? empires.get(empireId) : null;
    }

    /** The empire whose divine area holds {@code chunk} (an Overworld chunk), or null. */
    @Nullable
    public EmpireRecord getDivineOwner(long chunk) {
        UUID empireId = divineIndex.get(chunk);
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
        tag.putInt("version", 2);
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
