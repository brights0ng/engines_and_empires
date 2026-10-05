package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A wind-up reader standing on the ground. While it stands there it listens: when a vibration is made and the geophones
 * around it hear a deposit, it takes a reading of where that deposit is, PROVIDED it is fully wound: an unwound reader hears
 * nothing. What it records, and when, is decided by {@link ReaderRecorder}. It keeps only the most recent reading, and
 * capturing one spends the whole charge, so a fresh wind is needed before it can catch another.
 *
 * <p>The reading, and whatever charge is left, go with the reader. When it is picked up, or knocked out, both are on the item
 * it becomes, and the item shows the reading as a compass. When the item is put down again the reader has both back, so a
 * reader wound up, placed, and taken back before it hears anything keeps its charge for the next placement.
 *
 * <p>Like a geophone it is an entity, held by the block it stands on, and it can be knocked out by a hit or taken back with a
 * right-click. A small lamp on top is lit while it holds a reading. A reading is not taken the instant a vibration is made: it
 * arrives when the last geophone it used has lit, so it is scheduled for then, in game time, and applied when the moment comes.
 * Scheduled readings are saved with the entity.
 */
public class WindupReaderEntity extends Entity {

    /** Whether it holds a reading. Sent to players so they can light the lamp. */
    private static final EntityDataAccessor<Boolean> HAS_READING =
            SynchedEntityData.defineId(WindupReaderEntity.class, EntityDataSerializers.BOOLEAN);

    /** More scheduled readings than this are not worth keeping: the oldest is dropped. */
    private static final int MAX_PENDING = 8;

    /** How often, in ticks, it checks that the block under it is still there. */
    private static final int SUPPORT_CHECK_INTERVAL = 10;

    /** A reading that has been worked out, and the game time it arrives. */
    private record Pending(long tick, ReaderReading reading) {
    }

    private final List<Pending> pending = new ArrayList<>();

    /** The reading it holds, or null. Only known on the server; players are just told whether there is one. */
    private ReaderReading reading;

    /** How much of a wind it has, from 0 (none) to 1 (enough to catch one reading). Only known on the server. */
    private float charge;

    /** The block it stands on, or null if not known. */
    private BlockPos support;

    public WindupReaderEntity(EntityType<? extends WindupReaderEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Sets a reader down: standing on the top of the block at {@code support}, at the exact point {@code point}, turned by
     * {@code yaw} degrees, holding {@code reading} if it has one, and carrying {@code charge} of a wind. Call before adding it
     * to the world.
     */
    public void stake(Vec3 point, BlockPos support, float yaw, ReaderReading reading, float charge) {
        this.support = support.immutable();
        this.reading = reading;
        this.charge = charge;
        this.entityData.set(HAS_READING, reading != null);
        setYRot(yaw);
        this.yRotO = yaw;
        setPos(point.x, point.y, point.z);
    }

    /** Whether it holds a reading. */
    public boolean hasReading() {
        return this.entityData.get(HAS_READING);
    }

    // ---- taking readings ----

    /** Schedules a reading to arrive at a game time. */
    public void receive(long tick, ReaderReading reading) {
        if (level().isClientSide) {
            return;
        }
        if (pending.size() >= MAX_PENDING) {
            pending.remove(0);
        }
        pending.add(new Pending(tick, reading));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        if (tickCount % SUPPORT_CHECK_INTERVAL == 0 && !isStillSupported()) {
            pop(true);
            return;
        }
        applyDueReadings(server);
    }

    /**
     * Takes the newest of the readings whose moment has come. The most recent reading is the one it keeps, but only if the
     * reader is fully wound: an unwound reader lets every due reading pass unheard, and its charge, whatever it is, is
     * untouched. Catching one spends the whole charge, so it must be wound afresh to catch the next.
     */
    private void applyDueReadings(ServerLevel server) {
        if (pending.isEmpty()) {
            return;
        }
        long now = server.getGameTime();
        Pending latest = null;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending candidate = it.next();
            if (candidate.tick() <= now) {
                it.remove();
                if (latest == null || candidate.tick() >= latest.tick()) {
                    latest = candidate;
                }
            }
        }
        if (latest == null) {
            return;
        }
        if (!ReaderWinding.isFullyWound(charge)) {
            // It heard something, but had no wind left to catch it with: a soft, empty click, not the chime of an actual catch.
            server.playSound(null, getX(), getY(), getZ(), SoundEvents.LEVER_CLICK, SoundSource.BLOCKS, 0.5F, 0.7F);
            return;
        }
        this.reading = latest.reading();
        this.charge = 0.0F;
        this.entityData.set(HAS_READING, true);
        server.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8F, 0.8F);
    }

    // ---- being held by the ground, knocked out, taken back ----

    private boolean isStillSupported() {
        if (support == null || !level().hasChunkAt(support)) {
            return true; // nothing to check against, or not loaded: assume it is fine
        }
        BlockState state = level().getBlockState(support);
        return state.isFaceSturdy(level(), support, Direction.UP);
    }

    /** The item this reader turns back into, carrying its reading and whatever charge it has. */
    private ItemStack stack() {
        ItemStack stack = new ItemStack(SeismicContent.WINDUP_READER.get());
        if (reading != null) {
            stack.set(SeismicContent.READER_READING.get(), reading);
        }
        stack.set(SeismicContent.READER_CHARGE.get(), charge);
        return stack;
    }

    /** Takes the reader out of the world, optionally leaving it as an item, with its reading, where it stood. */
    private void pop(boolean drop) {
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.METAL_BREAK, SoundSource.BLOCKS, 0.8F, 1.0F);
        if (drop) {
            ItemEntity item = new ItemEntity(level(), getX(), getY() + 0.25, getZ(), stack());
            item.setDefaultPickUpDelay();
            level().addFreshEntity(item);
        }
        discard();
    }

    /** A player hitting it knocks it over and gets it back (unless in creative mode). Nothing else harms it. */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (level().isClientSide || isRemoved()) {
            return false;
        }
        if (source.getEntity() instanceof Player player) {
            pop(!player.getAbilities().instabuild);
            return true;
        }
        return false;
    }

    /**
     * Right-clicking picks it up and gives it to the player, reading and all: into their hand if it is empty, otherwise into
     * the inventory, and onto the ground at their feet if that is full. (In creative mode it is just taken away, as when it
     * is hit.)
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (isRemoved()) {
            return InteractionResult.PASS;
        }
        if (!player.getAbilities().instabuild) {
            ItemStack reader = stack();
            if (player.getItemInHand(hand).isEmpty()) {
                player.setItemInHand(hand, reader);
            } else if (!player.getInventory().add(reader)) {
                player.drop(reader, false);
            }
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3F, 1.0F);
        discard();
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public ItemStack getPickResult() {
        return stack();
    }

    @Override
    public boolean isPickable() {
        return !isRemoved();
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    // ---- registry ----

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel server) {
            ReaderRegistry.add(server, this);
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (level() instanceof ServerLevel server) {
            ReaderRegistry.remove(server, this);
        }
    }

    // ---- saving ----

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(HAS_READING, false);
    }

    private static CompoundTag write(ReaderReading reading) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Dimension", reading.dimension());
        tag.putInt("X", reading.x());
        tag.putInt("Y", reading.y());
        tag.putInt("Z", reading.z());
        tag.putBoolean("Height", reading.hasHeight());
        tag.putLong("Deposit", reading.deposit());
        tag.putLong("TakenAt", reading.takenAt());
        tag.putInt("Confidence", reading.confidence().ordinal());
        tag.putString("Ore", reading.ore());
        return tag;
    }

    private static ReaderReading read(CompoundTag tag) {
        // A reader saved before deposits, times or confidence were kept has none of those tags, which read as 0: not known.
        ReaderAccuracy.Confidence[] confidences = ReaderAccuracy.Confidence.values();
        int ordinal = Math.max(0, Math.min(tag.getInt("Confidence"), confidences.length - 1));
        return new ReaderReading(tag.getString("Dimension"), tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z"),
                tag.getBoolean("Height"), tag.getLong("Deposit"), tag.getLong("TakenAt"), confidences[ordinal], tag.getString("Ore"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        if (support != null) {
            tag.putIntArray("Support", new int[]{support.getX(), support.getY(), support.getZ()});
        }
        if (reading != null) {
            tag.put("Reading", write(reading));
        }
        tag.putFloat("Charge", charge);
        ListTag scheduled = new ListTag();
        for (Pending p : pending) {
            CompoundTag entry = write(p.reading());
            entry.putLong("Tick", p.tick());
            scheduled.add(entry);
        }
        tag.put("Pending", scheduled);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        int[] at = tag.getIntArray("Support");
        support = at.length == 3 ? new BlockPos(at[0], at[1], at[2]) : null;
        reading = tag.contains("Reading", Tag.TAG_COMPOUND) ? read(tag.getCompound("Reading")) : null;
        charge = tag.getFloat("Charge");
        this.entityData.set(HAS_READING, reading != null);
        pending.clear();
        ListTag scheduled = tag.getList("Pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < scheduled.size(); i++) {
            CompoundTag entry = scheduled.getCompound(i);
            pending.add(new Pending(entry.getLong("Tick"), read(entry)));
        }
    }
}
