// Written by Claude; extended with abilities, needs and inventory persistence by ChatGPT.
package dev.brights0ng.empire.data;

import java.util.List;
import java.util.Random;
import java.util.UUID;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.CivilianFood;

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
    public static final int VERSION = 2;

    private final UUID id;
    private final UUID empire;
    /** The entity that is this civilian's body now. */
    private UUID body;
    private String name;
    /** {@code CivilianAppearance.pack()}. */
    private int appearance;
    private int abilities;
    private CivilianFood.Saved food = new CivilianFood().save();
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
        this.abilities = defaultAbilities(id);
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

    public int abilities() {
        return abilities;
    }

    public void setAbilities(int abilities) {
        CivilianAbilities.unpack(abilities);
        this.abilities = abilities;
    }

    private static int defaultAbilities(UUID id) {
        return CivilianAbilities.roll(new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits())).pack();
    }

    public CivilianFood.Saved food() {
        return food;
    }

    public void setFood(CivilianFood.Saved food) {
        this.food = java.util.Objects.requireNonNull(food);
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
        tag.putInt("abilities", abilities);
        tag.put("food", CivilianFoodNbt.save(food));
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
        if (tag.contains("abilities", net.minecraft.nbt.Tag.TAG_INT)) {
            try {
                record.setAbilities(tag.getInt("abilities"));
            } catch (IllegalArgumentException ignored) {
                // Keep the same stable default as an older record if the saved bits are corrupt.
            }
        }
        record.setFood(CivilianFoodNbt.load(tag.getCompound("food")));
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), record.inventory, registries);
        if (tag.contains("health", net.minecraft.nbt.Tag.TAG_ANY_NUMERIC)) {
            record.setHealth(tag.getFloat("health"));
        }
        return record;
    }
}
