// Written by Gemini; members, the main bonfire's 3x3 rule, the public core chunk, the divine index, main bonfire assignment, protection pushes and the civilian roster by Claude.
package dev.brights0ng.empire.data;

import java.util.Collection;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianRoster;
import dev.brights0ng.empire.map.ClaimMapNetwork;
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
    /** Every empire's citizens, by civilian id: the master copies (their entities are only bodies). */
    private final Map<UUID, CivilianRecord> civilians = new HashMap<>();
    /** Empire to its civilians' ids. Not saved: rebuilt from the records. */
    private final Map<UUID, Set<UUID>> civiliansByEmpire = new HashMap<>();
    /** The traveller waiting at each empire's bonfire, if any. */
    private final Map<UUID, TravellerSlot> travellers = new HashMap<>();

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

    /**
     * Rebuilds the divine index; called on load and whenever an empire's main bonfire can change. Only an empire with a
     * main bonfire has protected (divine) land: the 3x3 around the main bonfire's chunk (Bright, 2026-10-24).
     */
    public void rebuildDivineIndex() {
        divineIndex.clear();
        for (EmpireRecord record : empires.values()) {
            if (record.mainBonfire() == null) {
                continue;
            }
            for (long chunk : DivineArea.chunks(coreChunk(record))) {
                // Only the empire's own land: the divine area never reaches into another empire's or unclaimed land.
                if (record.state().staked().contains(chunk)) {
                    divineIndex.putIfAbsent(chunk, record.id());
                }
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
        return stakeChunk(record, chunk, day, tierOk, null);
    }

    /**
     * Stakes {@code chunk} for the empire. When it has no main bonfire, a bonfire in its land whose 3x3 the new chunk
     * completes becomes the main one (the members online are told, given a {@code server}).
     */
    public StakeResult stakeChunk(EmpireRecord record, long chunk, long day, boolean tierOk, @Nullable MinecraftServer server) {
        boolean ownedElsewhere = chunkIndex.containsKey(chunk) && !record.id().equals(chunkIndex.get(chunk));
        StakeResult result = record.state().stake(chunk, day, tierOk, ownedElsewhere);
        if (result == StakeResult.OK) {
            chunkIndex.put(chunk, record.id());
            tryAssignMain(record, server);
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
     * The empire's core chunk: its main bonfire's chunk, else the last main bonfire's (the founding chunk before any).
     * The 3x3 centred on it is the divine area, which cannot be unstaked.
     */
    public static long coreChunk(EmpireRecord record) {
        return record.coreChunk();
    }

    public UnstakeResult unstakeChunk(EmpireRecord record, long chunk, long day) {
        // Without a main bonfire nothing is divine: only the core chunk is kept, so the empire always holds land.
        UnstakeResult result = record.state().unstake(chunk, day, coreChunk(record), record.mainBonfire() != null);
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
            List<CivilianRecord> removed = new ArrayList<>();
            for (UUID civilianId : civiliansByEmpire.getOrDefault(empireId, Set.of())) {
                removed.add(civilians.remove(civilianId));
            }
            civiliansByEmpire.remove(empireId);
            TravellerSlot traveller = travellers.remove(empireId);
            rebuildDivineIndex();
            setDirty();
            messageMembers(record, server, Component.translatable("message.empire.disbanded"));
            CivilianRoster.onDisband(server, removed, traveller);
            ClaimMapNetwork.pushNear(server, coreChunk(record));
        }
    }

    public void onBonfirePlaced(BlockPos pos, @Nullable MinecraftServer server) {
        bonfires.add(pos);
        EmpireRecord record = getEmpireByChunk(ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (record != null) {
            tryAssignMain(record, server);
        }
        setDirty();
    }

    /**
     * When the empire has no main bonfire, makes one of its bonfires the main one: of those in its land whose 3x3 it has
     * fully staked, the one closest to the core chunk's centre (ties: the lowest {@link BlockPos#asLong}). Returns whether
     * it assigned one. Called when a bonfire is placed, a chunk is staked, and a member uses a bonfire.
     */
    public boolean tryAssignMain(EmpireRecord record, @Nullable MinecraftServer server) {
        if (record.mainBonfire() != null) {
            return false;
        }
        long core = record.coreChunk();
        double cx = (ChunkKeys.getX(core) << 4) + 8.0;
        double cz = (ChunkKeys.getZ(core) << 4) + 8.0;
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (BlockPos candidate : bonfires.all()) {
            long chunk = ChunkKeys.pack(candidate.getX() >> 4, candidate.getZ() >> 4);
            if (!record.id().equals(chunkIndex.get(chunk)) || !eligibleMain(record, candidate)) {
                continue;
            }
            double dx = candidate.getX() + 0.5 - cx;
            double dz = candidate.getZ() + 0.5 - cz;
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq || (distSq == bestDistSq && candidate.asLong() < best.asLong())) {
                best = candidate;
                bestDistSq = distSq;
            }
        }
        if (best == null) {
            return false;
        }
        record.setMainBonfire(best.immutable());
        onMainBonfireChanged(record, server);
        rebuildDivineIndex();
        notifyMainBonfireChange(record, server, best);
        ClaimMapNetwork.pushNear(server, ChunkKeys.pack(best.getX() >> 4, best.getZ() >> 4));
        setDirty();
        return true;
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
                onMainBonfireChanged(record, server);
                rebuildDivineIndex();
                notifyMainBonfireChange(record, server, bestCandidate);
                ClaimMapNetwork.pushNear(server, ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
            }
        }
        setDirty();
    }

    /** Every change of an empire's main bonfire comes here: its citizens and traveller follow the home. */
    private void onMainBonfireChanged(EmpireRecord record, @Nullable MinecraftServer server) {
        CivilianRoster.refreshHomes(server, record);
    }

    /** A bonfire can be the main one only if the empire has staked all 9 chunks of the 3x3 centred on its chunk. */
    public static boolean eligibleMain(EmpireRecord record, BlockPos pos) {
        return ClaimGraph.surroundingStaked(record.state().staked(), ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
    }

    /** How many of the 9 chunks of the 3x3 centred on {@code pos}'s chunk the empire has staked. */
    public static int stakedAround(EmpireRecord record, BlockPos pos) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int count = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (record.state().staked().contains(ChunkKeys.pack(cx + dx, cz + dz))) {
                    count++;
                }
            }
        }
        return count;
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

    /** Adds (or replaces) a citizen's record. */
    public void addCivilian(CivilianRecord record) {
        removeCivilian(record.id());
        civilians.put(record.id(), record);
        civiliansByEmpire.computeIfAbsent(record.empire(), k -> new HashSet<>()).add(record.id());
        setDirty();
    }

    /** Deletes a citizen's record (death, or its empire's end); returns it, or null if there was none. */
    @Nullable
    public CivilianRecord removeCivilian(UUID civilianId) {
        CivilianRecord record = civilians.remove(civilianId);
        if (record != null) {
            Set<UUID> ids = civiliansByEmpire.get(record.empire());
            if (ids != null) {
                ids.remove(civilianId);
                if (ids.isEmpty()) {
                    civiliansByEmpire.remove(record.empire());
                }
            }
            setDirty();
        }
        return record;
    }

    @Nullable
    public CivilianRecord civilian(UUID civilianId) {
        return civilians.get(civilianId);
    }

    /** The empire's citizens' records. */
    public List<CivilianRecord> civiliansOf(UUID empireId) {
        List<CivilianRecord> records = new ArrayList<>();
        for (UUID id : civiliansByEmpire.getOrDefault(empireId, Set.of())) {
            records.add(civilians.get(id));
        }
        return Collections.unmodifiableList(records);
    }

    public Collection<CivilianRecord> allCivilians() {
        return Collections.unmodifiableCollection(civilians.values());
    }

    public int citizenCount(UUID empireId) {
        return civiliansByEmpire.getOrDefault(empireId, Set.of()).size();
    }

    /** The traveller waiting at the empire's bonfire, or null. */
    @Nullable
    public TravellerSlot traveller(UUID empireId) {
        return travellers.get(empireId);
    }

    /** Sets or (with null) clears the empire's waiting traveller. */
    public void setTraveller(UUID empireId, @Nullable TravellerSlot slot) {
        if (slot != null) {
            travellers.put(empireId, slot);
        } else {
            travellers.remove(empireId);
        }
        setDirty();
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
        // Version 2 files have neither list: they load with no civilians and no travellers.
        ListTag civiliansTag = tag.getList("civilians", Tag.TAG_COMPOUND);
        for (int i = 0; i < civiliansTag.size(); i++) {
            CivilianRecord civilian = CivilianRecord.load(civiliansTag.getCompound(i));
            data.civilians.put(civilian.id(), civilian);
            data.civiliansByEmpire.computeIfAbsent(civilian.empire(), k -> new HashSet<>()).add(civilian.id());
        }
        ListTag travellersTag = tag.getList("travellers", Tag.TAG_COMPOUND);
        for (int i = 0; i < travellersTag.size(); i++) {
            CompoundTag slotTag = travellersTag.getCompound(i);
            data.travellers.put(slotTag.getUUID("empire"),
                    new TravellerSlot(slotTag.getUUID("body"), slotTag.getInt("want"), slotTag.getLong("leave_at")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 3);
        ListTag empiresTag = new ListTag();
        for (EmpireRecord record : empires.values()) {
            empiresTag.add(record.save());
        }
        tag.put("empires", empiresTag);
        CompoundTag bonfiresTag = new CompoundTag();
        bonfires.save(bonfiresTag);
        tag.put("bonfires", bonfiresTag);
        ListTag civiliansTag = new ListTag();
        for (CivilianRecord civilian : civilians.values()) {
            civiliansTag.add(civilian.save());
        }
        tag.put("civilians", civiliansTag);
        ListTag travellersTag = new ListTag();
        for (Map.Entry<UUID, TravellerSlot> entry : travellers.entrySet()) {
            CompoundTag slotTag = new CompoundTag();
            slotTag.putUUID("empire", entry.getKey());
            slotTag.putUUID("body", entry.getValue().body());
            slotTag.putInt("want", entry.getValue().want());
            slotTag.putLong("leave_at", entry.getValue().leaveAt());
            travellersTag.add(slotTag);
        }
        tag.put("travellers", travellersTag);
        return tag;
    }
}
