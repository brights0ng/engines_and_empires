// Written by Claude; extended with abilities by ChatGPT.
package dev.brights0ng.empire.data;

import java.util.UUID;
import java.util.Random;

import dev.brights0ng.empire.rules.CivilianAbilities;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("version", VERSION);
        tag.putUUID("id", id);
        tag.putUUID("empire", empire);
        tag.putUUID("body", body);
        tag.putString("name", name);
        tag.putInt("appearance", appearance);
        tag.putInt("abilities", abilities);
        tag.putLong("last_pos", lastPos.asLong());
        tag.putLong("last_chunk", lastChunk);
        tag.putLong("joined", joinedGameTime);
        return tag;
    }

    public static CivilianRecord load(CompoundTag tag) {
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
        return record;
    }
}
