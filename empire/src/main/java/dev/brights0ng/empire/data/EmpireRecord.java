// Written by Gemini; god, sacrifice count and current demand added by Claude; equal members and the feeding hook by Claude.
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
    /** Every member, in the order they joined. All members have equal control; none is the founder. */
    private final Set<UUID> members;
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

    public EmpireRecord(UUID id, Collection<UUID> members, EmpireState state, @Nullable BlockPos mainBonfire, long foundedGameTime) {
        this.id = id;
        this.members = new LinkedHashSet<>(members);
        this.state = state;
        this.mainBonfire = mainBonfire;
        this.foundedGameTime = foundedGameTime;
    }

    public UUID id() {
        return id;
    }

    /** The members, in the order they joined. An empire whose last member left has none, and keeps its land. */
    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public boolean isMember(UUID player) {
        return members.contains(player);
    }

    /** Only {@link EmpireSavedData#addMember}, which keeps its member index in step. */
    boolean addMember(UUID player) {
        return members.add(player);
    }

    /** Only {@link EmpireSavedData#removeMember}, which keeps its member index in step. */
    boolean removeMember(UUID player) {
        return members.remove(player);
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

    /**
     * The god is fed: at founding, at each join, at adoption and when a demand is paid in full. Does nothing yet; the
     * god's satiety will hang off it.
     */
    public void feedGod() {
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
        return tag;
    }

    public static EmpireRecord load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        // Records saved before members existed name only their founder, who becomes the one member.
        List<UUID> members = new ArrayList<>();
        if (tag.contains("members", Tag.TAG_LIST)) {
            ListTag membersTag = tag.getList("members", Tag.TAG_INT_ARRAY);
            for (Tag memberTag : membersTag) {
                members.add(NbtUtils.loadUUID(memberTag));
            }
        } else if (tag.hasUUID("founder")) {
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
