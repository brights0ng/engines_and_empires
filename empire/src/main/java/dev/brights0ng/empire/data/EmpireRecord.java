// Written by Gemini.
package dev.brights0ng.empire.data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.rules.EmpireState;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;

public final class EmpireRecord {

    private final UUID id;
    private final UUID founder;
    private final EmpireState state;
    @Nullable
    private BlockPos mainBonfire;
    private final long foundedGameTime;

    public EmpireRecord(UUID id, UUID founder, EmpireState state, @Nullable BlockPos mainBonfire, long foundedGameTime) {
        this.id = id;
        this.founder = founder;
        this.state = state;
        this.mainBonfire = mainBonfire;
        this.foundedGameTime = foundedGameTime;
    }

    public UUID id() {
        return id;
    }

    public UUID founder() {
        return founder;
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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("founder", founder);
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
        return tag;
    }

    public static EmpireRecord load(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        UUID founder = tag.getUUID("founder");
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
        return new EmpireRecord(id, founder, state, mainBonfire, founded);
    }
}
