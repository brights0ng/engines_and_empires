// Written by Gemini; god, sacrifice count, current demand, members, the god's hunger, the kept core chunk, the next traveller time and the last member-online time added by Claude.
package dev.brights0ng.empire.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.DemandLine;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.EmpireState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

public final class EmpireRecord {

    private final UUID id;
    /** Every member has equal control of the empire; the set may be empty (a frozen empire anyone can join). */
    private final Set<UUID> members = new LinkedHashSet<>();
    private final EmpireState state;
    @Nullable
    private BlockPos mainBonfire;
    private final long foundedGameTime;
    /** The god the empire was founded under, or null for an empire without one (founded before gods existed). */
    @Nullable
    private ResourceLocation god;
    /** How many demands the empire has paid in full. */
    private int sacrificeCount;
    /** The god's current demand, with what has been paid towards each line. */
    private final List<DemandLine> currentDemand = new ArrayList<>();
    /** Ticks since the god was last fed, counted only while a member is online ({@code god.GodHunger}). */
    private long hungerTicks;
    /**
     * The chunk of the last main bonfire (the founding chunk until there is one). While there is no main bonfire it stays
     * the core chunk, so the divine area stays on land whose 3x3 the empire could not unstake while it was the core.
     */
    private long coreChunk;
    /** The earliest game time the next traveller may arrive ({@code rules.ArrivalRule}); 0 lets the first come at once. */
    private long nextTravellerTime;
    /**
     * The last game time a member was online ({@code civilian.CivilianChunkLoading}); civilians stop loading chunks some
     * server time after it (Bright, D11). {@link #UNKNOWN} until first set (records saved before it existed).
     */
    private long lastMemberOnline = UNKNOWN;

    /** {@link #lastMemberOnline} not yet known. */
    public static final long UNKNOWN = -1L;

    public EmpireRecord(UUID id, Collection<UUID> members, EmpireState state, @Nullable BlockPos mainBonfire, long foundedGameTime) {
        this.id = id;
        this.members.addAll(members);
        this.state = state;
        this.mainBonfire = mainBonfire;
        this.foundedGameTime = foundedGameTime;
        this.coreChunk = mainBonfire != null ? chunkOf(mainBonfire) : state.foundingChunk();
    }

    public UUID id() {
        return id;
    }

    /** The members, in the order they joined. */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    /** Use {@link EmpireSavedData#addMember}, which keeps its player index in step. */
    boolean addMember(UUID player) {
        return members.add(player);
    }

    /** Use {@link EmpireSavedData#removeMember}, which keeps its player index in step. */
    boolean removeMember(UUID player) {
        return members.remove(player);
    }

    /**
     * Feeds the god: called at founding, joining, adoption, when a demand is paid in full and when 5 of its tier 1 meat
     * are given at once. Its hunger starts again from 0.
     */
    public void feedGod() {
        hungerTicks = 0;
    }

    public long hungerTicks() {
        return hungerTicks;
    }

    public void setHungerTicks(long hungerTicks) {
        this.hungerTicks = Math.max(0, hungerTicks);
    }

    public EmpireState state() {
        return state;
    }

    @Nullable
    public BlockPos mainBonfire() {
        return mainBonfire;
    }

    public void setMainBonfire(@Nullable BlockPos mainBonfire) {
        this.mainBonfire = mainBonfire;
        if (mainBonfire != null) {
            coreChunk = chunkOf(mainBonfire);
        }
    }

    /** The chunk of the main bonfire, or of the last one while there is none (see the field). */
    public long coreChunk() {
        return mainBonfire != null ? chunkOf(mainBonfire) : coreChunk;
    }

    private static long chunkOf(BlockPos pos) {
        return ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public long nextTravellerTime() {
        return nextTravellerTime;
    }

    public void setNextTravellerTime(long nextTravellerTime) {
        this.nextTravellerTime = nextTravellerTime;
    }

    public long lastMemberOnline() {
        return lastMemberOnline;
    }

    public void setLastMemberOnline(long gameTime) {
        this.lastMemberOnline = gameTime;
    }

    public long foundedGameTime() {
        return foundedGameTime;
    }

    @Nullable
    public ResourceLocation god() {
        return god;
    }

    public void setGod(@Nullable ResourceLocation god) {
        this.god = god;
    }

    public int sacrificeCount() {
        return sacrificeCount;
    }

    public void setSacrificeCount(int sacrificeCount) {
        this.sacrificeCount = Math.max(0, sacrificeCount);
    }

    /** The current demand's lines, in tier order. */
    public List<DemandLine> currentDemand() {
        return Collections.unmodifiableList(currentDemand);
    }

    public void setCurrentDemand(List<DemandLine> lines) {
        currentDemand.clear();
        currentDemand.addAll(lines);
    }

    /** Replaces one line of the current demand (a payment towards it). */
    public void setDemandLine(int index, DemandLine line) {
        currentDemand.set(index, line);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        ListTag membersTag = new ListTag();
        for (UUID member : members) {
            membersTag.add(NbtUtils.createUUID(member));
        }
        tag.put("members", membersTag);
        tag.putLong("founded", foundedGameTime);
        if (mainBonfire != null) {
            tag.putLong("main_bonfire", mainBonfire.asLong());
        }

        CompoundTag stateTag = new CompoundTag();
        stateTag.putInt("dominion", state.dominion());
        stateTag.putLong("founding_chunk", state.foundingChunk());

        ListTag stakedTag = new ListTag();
        for (long chunk : state.staked()) {
            stakedTag.add(LongTag.valueOf(chunk));
        }
        stateTag.put("staked", stakedTag);

        ListTag dormantTag = new ListTag();
        for (EmpireState.Dormant d : state.dormant()) {
            CompoundTag dTag = new CompoundTag();
            dTag.putLong("available_on", d.availableOnDay());
            dormantTag.add(dTag);
        }
        stateTag.put("dormant", dormantTag);

        tag.put("state", stateTag);

        if (god != null) {
            tag.putString("god", god.toString());
        }
        tag.putInt("sacrifice_count", sacrificeCount);
        ListTag demandTag = new ListTag();
        for (DemandLine line : currentDemand) {
            CompoundTag lineTag = new CompoundTag();
            lineTag.putString("kind", line.kind().name());
            ListTag idsTag = new ListTag();
            for (String lineId : line.ids()) {
                idsTag.add(StringTag.valueOf(lineId));
            }
            lineTag.put("ids", idsTag);
            lineTag.putInt("required", line.required());
            lineTag.putInt("paid", line.paid());
            demandTag.add(lineTag);
        }
        tag.put("demand", demandTag);
        tag.putLong("hunger_ticks", hungerTicks);
        tag.putLong("core_chunk", coreChunk());
        tag.putLong("next_traveller", nextTravellerTime);
        tag.putLong("last_member_online", lastMemberOnline);
        return tag;
    }

    public static EmpireRecord load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        List<UUID> members = new ArrayList<>();
        if (tag.contains("members", Tag.TAG_LIST)) {
            for (Tag memberTag : tag.getList("members", Tag.TAG_INT_ARRAY)) {
                members.add(NbtUtils.loadUUID(memberTag));
            }
        } else if (tag.hasUUID("founder")) {
            // Saved before members (version 1): the founder was the only one who could act for the empire.
            members.add(tag.getUUID("founder"));
        }
        long founded = tag.getLong("founded");
        BlockPos mainBonfire = tag.contains("main_bonfire") ? BlockPos.of(tag.getLong("main_bonfire")) : null;

        CompoundTag stateTag = tag.getCompound("state");
        int dominion = stateTag.getInt("dominion");
        long foundingChunk = stateTag.getLong("founding_chunk");

        Set<Long> staked = new HashSet<>();
        ListTag stakedTag = stateTag.getList("staked", Tag.TAG_LONG);
        for (int i = 0; i < stakedTag.size(); i++) {
            staked.add(((LongTag) stakedTag.get(i)).getAsLong());
        }

        List<EmpireState.Dormant> dormant = new ArrayList<>();
        ListTag dormantTag = stateTag.getList("dormant", Tag.TAG_COMPOUND);
        for (int i = 0; i < dormantTag.size(); i++) {
            CompoundTag dTag = dormantTag.getCompound(i);
            dormant.add(new EmpireState.Dormant(dTag.getLong("available_on")));
        }

        EmpireState state = new EmpireState(dominion, foundingChunk, staked, dormant);
        EmpireRecord record = new EmpireRecord(id, members, state, mainBonfire, founded);

        // Records saved before gods existed have none of these: they load godless, with nothing demanded.
        record.god = tag.contains("god", Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString("god")) : null;
        record.sacrificeCount = tag.getInt("sacrifice_count");
        record.hungerTicks = Math.max(0, tag.getLong("hunger_ticks"));
        record.nextTravellerTime = tag.getLong("next_traveller");
        record.lastMemberOnline = tag.contains("last_member_online", Tag.TAG_LONG) ? tag.getLong("last_member_online") : UNKNOWN;
        if (tag.contains("core_chunk", Tag.TAG_LONG)) {
            record.coreChunk = tag.getLong("core_chunk");
        }
        ListTag demandTag = tag.getList("demand", Tag.TAG_COMPOUND);
        for (int i = 0; i < demandTag.size(); i++) {
            CompoundTag lineTag = demandTag.getCompound(i);
            DemandLine.Kind kind;
            try {
                kind = DemandLine.Kind.valueOf(lineTag.getString("kind"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            List<String> ids = new ArrayList<>();
            ListTag idsTag = lineTag.getList("ids", Tag.TAG_STRING);
            for (int j = 0; j < idsTag.size(); j++) {
                ids.add(idsTag.getString(j));
            }
            if (!ids.isEmpty()) {
                record.currentDemand.add(new DemandLine(kind, ids, lineTag.getInt("required"), lineTag.getInt("paid")));
            }
        }
        return record;
    }
}
