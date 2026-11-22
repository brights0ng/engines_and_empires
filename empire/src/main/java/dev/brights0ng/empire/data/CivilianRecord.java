// Written by Claude; extended with abilities, needs and inventory persistence by ChatGPT; extended with sleep, bed, fulfilment and traits persistence by Gemini.
package dev.brights0ng.empire.data;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.CivilianFood;
import dev.brights0ng.empire.rules.CivilianFulfilment;
import dev.brights0ng.empire.rules.CivilianPreferences;
import dev.brights0ng.empire.rules.CivilianSleep;
import dev.brights0ng.empire.rules.CivilianTraits;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;

/**
 * A citizen of an empire, as the empire remembers it: the master copy (Bright, 2026-10-24). The civilian entity is its
 * body; a lost body is replaced from this, and the record outlives it. Mutable, so later fields (skills) can be added;
 * versioned in its saved form.
 */
public final class CivilianRecord {

    /** The saved form's version; bump it when fields change meaning. */
    public static final int VERSION = 3;

    private final UUID id;
    private final UUID empire;
    /** The entity that is this civilian's body now. */
    private UUID body;
    private String name;
    /** {@code CivilianAppearance.pack()}. */
    private int appearance;
    private CivilianTraits traits;
    private CivilianFood.Saved food = new CivilianFood().save();
    private CivilianSleep.Saved sleep = new CivilianSleep().save();
    private CivilianFulfilment.Saved fulfilment;
    @Nullable
    private BlockPos bed;
    /** NaN in older records means to keep the body's health. */
    private float health = Float.NaN;
    private final NonNullList<ItemStack> inventory = NonNullList.withSize(CivilianEntity.INVENTORY_SIZE, ItemStack.EMPTY);
    private BlockPos lastPos;
    /** {@code ChunkKeys.pack} of the chunk the body was last seen in. */
    private long lastChunk;
    private final long joinedGameTime;

    public CivilianRecord(UUID id, UUID empire, UUID body, String name, int appearance, BlockPos lastPos, long lastChunk,
                          long joinedGameTime) {
        this.id = id;
        this.empire = empire;
        this.body = body;
        this.name = name;
        this.appearance = appearance;
        this.traits = CivilianTraits.defaultFor(id);
        this.fulfilment = CivilianFulfilment.Saved.empty(CivilianPreferences.defaultFor(id));
        this.lastPos = lastPos.immutable();
        this.lastChunk = lastChunk;
        this.joinedGameTime = joinedGameTime;
    }

    public UUID id() {
        return id;
    }

    public UUID empire() {
        return empire;
    }

    public UUID body() {
        return body;
    }

    public void setBody(UUID body) {
        this.body = body;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int appearance() {
        return appearance;
    }

    public void setAppearance(int appearance) {
        this.appearance = appearance;
    }

    public CivilianTraits traits() {
        return traits;
    }

    public void setTraits(CivilianTraits traits) {
        this.traits = Objects.requireNonNull(traits, "traits");
    }

    /** Derived player-facing 7-score abilities computed from {@link #traits()}. */
    public CivilianAbilities abilities() {
        return traits.abilities();
    }

    public CivilianFood.Saved food() {
        return food;
    }

    public void setFood(CivilianFood.Saved food) {
        this.food = Objects.requireNonNull(food);
    }

    public CivilianSleep.Saved sleep() {
        return sleep;
    }

    public void setSleep(CivilianSleep.Saved sleep) {
        this.sleep = Objects.requireNonNull(sleep);
    }

    public CivilianFulfilment.Saved fulfilment() {
        return fulfilment;
    }

    public void setFulfilment(CivilianFulfilment.Saved fulfilment) {
        this.fulfilment = Objects.requireNonNull(fulfilment);
    }

    @Nullable
    public BlockPos bed() {
        return bed;
    }

    public void setBed(@Nullable BlockPos bed) {
        this.bed = bed != null ? bed.immutable() : null;
    }

    public float health() {
        return health;
    }

    public void setHealth(float health) {
        this.health = Float.isFinite(health) ? health : Float.NaN;
    }

    public BlockPos lastPos() {
        return lastPos;
    }

    public long lastChunk() {
        return lastChunk;
    }

    public void setLast(BlockPos pos, long chunk) {
        this.lastPos = pos.immutable();
        this.lastChunk = chunk;
    }

    public long joinedGameTime() {
        return joinedGameTime;
    }

    public NonNullList<ItemStack> inventory() {
        return inventory;
    }

    /** Copies stacks so the master snapshot never shares mutable stacks with a body. */
    public void setInventory(List<ItemStack> items) {
        for (int slot = 0; slot < inventory.size(); slot++) {
            inventory.set(slot, slot < items.size() ? items.get(slot).copy() : ItemStack.EMPTY);
        }
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putUUID("id", id);
        tag.putUUID("empire", empire);
        tag.putUUID("body", body);
        tag.putString("name", name);
        tag.putInt("appearance", appearance);
        tag.put("traits", CivilianTraitsNbt.save(traits));
        tag.put("food", CivilianFoodNbt.save(food));
        tag.put("sleep", CivilianSleepNbt.save(sleep));
        tag.put("fulfilment", CivilianFulfilmentNbt.save(fulfilment));
        if (bed != null) {
            tag.putLong("bed", bed.asLong());
        }
        tag.putFloat("health", health);
        tag.put("inventory", ContainerHelper.saveAllItems(new CompoundTag(), inventory, registries));
        tag.putLong("last_pos", lastPos.asLong());
        tag.putLong("last_chunk", lastChunk);
        tag.putLong("joined", joinedGameTime);
        return tag;
    }

    public static CivilianRecord load(CompoundTag tag, HolderLookup.Provider registries) {
        CivilianRecord record = new CivilianRecord(tag.getUUID("id"), tag.getUUID("empire"), tag.getUUID("body"), tag.getString("name"),
                tag.getInt("appearance"), BlockPos.of(tag.getLong("last_pos")), tag.getLong("last_chunk"),
                tag.getLong("joined"));
        if (tag.contains("traits", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            record.setTraits(CivilianTraitsNbt.load(
                    tag.getCompound("traits"), () -> CivilianTraits.defaultFor(record.id())));
        }
        record.setFood(CivilianFoodNbt.load(tag.getCompound("food")));
        record.setSleep(CivilianSleepNbt.load(tag.getCompound("sleep")));
        record.setFulfilment(CivilianFulfilmentNbt.load(
                tag.getCompound("fulfilment"), () -> CivilianPreferences.defaultFor(record.id())));
        if (tag.contains("bed", net.minecraft.nbt.Tag.TAG_LONG)) {
            record.setBed(BlockPos.of(tag.getLong("bed")));
        }
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), record.inventory, registries);
        if (tag.contains("health", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)) {
            record.setHealth(tag.getFloat("health"));
        }
        return record;
    }
}
