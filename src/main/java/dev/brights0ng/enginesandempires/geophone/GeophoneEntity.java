package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A geophone: a rod driven into the face of a block, with a sensor on the end that glows when a vibration reaches it.
 *
 * <p>It is an entity, not a block, so it can be staked exactly where the player clicked and at any angle: rod pointing
 * up out of the top of a block, down out of the underside, or sideways out of a wall. Its position is the point on the
 * face where it goes in, and that is also where it listens: the geophone hears the ground at its spike, not at its
 * tip. The way it points is the face it was staked into, see {@link GeophoneOrientation}.
 *
 * <p>When it is placed it slides into the block, over {@link GeophoneSlide#TICKS} ticks, until its crossguard meets the
 * face. The slide is only drawn, by the renderer: the entity is where it ends up from the start, and its hitbox never
 * changes. The renderer works out how far along it is from the game time it was placed, which is sent to players, so a
 * geophone that comes into view later is simply fully in. When the crossguard meets the face the server makes the sound of
 * it and sprays out crumbs of the block it went into.
 *
 * <p>Most of what it does is keep time. A vibration tells it when to glow, in game time, possibly several seconds
 * ahead, and which ore it is a pulse from; it lights the sensor when that moment comes and puts it out when the pulse
 * ends. A tier that hears every ore (see {@link GeophoneTier}) also remembers which ore is behind the glow, so it can be
 * shown in a different colour; which one is shown when several pulses overlap is decided by {@link GeophoneGlow}. Which
 * colour to actually draw for that ore is entirely the renderer's business: this class only ever deals in ore ids.
 * Pulses are kept in absolute game time and saved with the entity, so a geophone whose chunk unloads mid-pulse carries
 * on where it left off. It also tells the {@link GeophoneRegistry} when it appears and disappears, so vibrations know
 * where to look.
 *
 * <p>It is held by the block it is staked into. If that block is broken, or changes so that it no longer has a solid
 * face there, the geophone pops off as an item of its own tier. A player can knock it out by hitting it, or take it
 * back by right-clicking it: into the empty hand if there is one, otherwise into the inventory.
 */
public class GeophoneEntity extends Entity {

    /** Which face it is staked into, and so which way the rod points. */
    private static final EntityDataAccessor<Direction> FACING =
            SynchedEntityData.defineId(GeophoneEntity.class, EntityDataSerializers.DIRECTION);
    /** Whether the sensor is glowing now. Sent to players so they can draw it. */
    private static final EntityDataAccessor<Boolean> LIT =
            SynchedEntityData.defineId(GeophoneEntity.class, EntityDataSerializers.BOOLEAN);
    /** The id of the ore whose pulse is being shown, or empty if none is (nothing is lit, or the tier does not colour its glow). */
    private static final EntityDataAccessor<String> GLOW_ORE =
            SynchedEntityData.defineId(GeophoneEntity.class, EntityDataSerializers.STRING);
    /** The ordinal of its {@link GeophoneTier}. */
    private static final EntityDataAccessor<Integer> TIER =
            SynchedEntityData.defineId(GeophoneEntity.class, EntityDataSerializers.INT);
    /**
     * The game time it was placed, which is how the renderer knows how far along the slide is. Not saved: a geophone
     * loaded from disk has 0 here, which is long ago, so it is simply fully in.
     */
    private static final EntityDataAccessor<Long> PLACED_AT =
            SynchedEntityData.defineId(GeophoneEntity.class, EntityDataSerializers.LONG);

    /**
     * The most pulses it will hold at once. Generous on purpose: a brass geophone hears every ore, so a single wide shot
     * (the mechanical thumper reaches 512 blocks) can easily schedule several dozen pulses across a dense area, where the
     * same shot might give an andesite geophone only a handful. Pulses also clean themselves up the moment they expire
     * (see {@link #updateGlow}), so this is only ever a defence against a genuinely pathological shot, not a number that
     * ordinary play should come near.
     */
    private static final int MAX_PULSES = 256;

    /** How often, in ticks, it checks that the block behind it is still there. */
    private static final int SUPPORT_CHECK_INTERVAL = 10;

    /** How many crumbs fly out when the crossguard meets the face. */
    private static final int CRUMBS = 12;

    /** A pulse it has been told about: when it starts and ends, in game time, and which ore it is an echo of. */
    private record Pulse(long start, long end, String oreId) {
    }

    /** Pulses it has been told about that have not finished yet. */
    private final List<Pulse> pulses = new ArrayList<>();

    /** The block it is staked into, or null if not known (a geophone saved before this was kept). */
    private BlockPos support;

    /** Whether the moment the crossguard meets the face has been dealt with. Not saved: on loading it is long past. */
    private boolean settled;

    public GeophoneEntity(EntityType<? extends GeophoneEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Sets a new geophone up: staked into a face of the block at {@code support}, at the exact point {@code point} on
     * that face. Call before adding it to the world.
     */
    public void stake(Vec3 point, Direction face, BlockPos support, GeophoneTier tier) {
        this.entityData.set(FACING, face);
        this.entityData.set(TIER, tier.ordinal());
        this.entityData.set(PLACED_AT, level().getGameTime());
        this.support = support.immutable();
        setPos(point.x, point.y, point.z);
    }

    /** Which face of its block it is staked into. */
    public Direction facing() {
        return this.entityData.get(FACING);
    }

    /** Whether the sensor is glowing now. */
    public boolean isLit() {
        return this.entityData.get(LIT);
    }

    /** The id of the ore whose pulse is being shown, or empty if it is not lit, or its tier does not colour its glow. */
    public String glowOreId() {
        return this.entityData.get(GLOW_ORE);
    }

    /** Which ores this geophone hears. */
    public GeophoneTier tier() {
        GeophoneTier[] tiers = GeophoneTier.values();
        int ordinal = this.entityData.get(TIER);
        return tiers[Math.max(0, Math.min(ordinal, tiers.length - 1))];
    }

    /** How long ago it was placed, in ticks, for drawing the slide. Meant for the renderer. */
    public double placedAge(float partialTick) {
        return level().getGameTime() - this.entityData.get(PLACED_AT) + partialTick;
    }

    // ---- listening ----

    /**
     * Schedules a glow, in game time, for a pulse from this ore. If it is already holding {@link #MAX_PULSES}, this one
     * is dropped rather than making room by evicting an existing one: {@link SeismicWave#pulses} always delivers a given
     * geophone's pulses in ascending order of when they start, so anything already held is due sooner than this new one,
     * and so is more worth keeping. Losing the soonest pulses to make room for later ones (which is what evicting the
     * front of the list would do) is exactly the bug this guards against.
     */
    public void receive(long startTick, long endTick, String oreId) {
        if (level().isClientSide) {
            return;
        }
        if (pulses.size() >= MAX_PULSES) {
            return;
        }
        pulses.add(new Pulse(startTick, endTick, oreId));
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel server)) {
            return;
        }
        if (!settled) {
            long age = server.getGameTime() - this.entityData.get(PLACED_AT);
            if (GeophoneSlide.isDone(age)) {
                settled = true;
                if (age <= GeophoneSlide.TICKS + 5) {
                    settle(server);
                }
            }
        }
        if (tickCount % SUPPORT_CHECK_INTERVAL == 0 && !isStillSupported()) {
            pop(true);
            return;
        }
        updateGlow();
    }

    /**
     * Lights the sensor while any pulse is under way, in whichever ore's colour {@link GeophoneGlow} says to show. Does
     * nothing at all unless it is lit or has a pulse pending.
     */
    private void updateGlow() {
        boolean wasLit = isLit();
        if (pulses.isEmpty() && !wasLit) {
            return;
        }
        long now = level().getGameTime();
        List<GeophoneGlow.ActivePulse> active = new ArrayList<>();
        for (Iterator<Pulse> it = pulses.iterator(); it.hasNext(); ) {
            Pulse pulse = it.next();
            if (now >= pulse.end()) {
                it.remove();
            } else if (now >= pulse.start()) {
                active.add(new GeophoneGlow.ActivePulse(pulse.start(), pulse.oreId()));
            }
        }
        String previousOre = glowOreId();
        String nextOre = GeophoneGlow.choose(active, previousOre.isEmpty() ? null : previousOre);
        boolean nowLit = nextOre != null;
        if (nowLit != wasLit) {
            this.entityData.set(LIT, nowLit);
            if (nowLit) {
                level().playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.5F, 1.4F);
            }
        }
        String nextOreOrEmpty = nextOre == null ? "" : nextOre;
        if (!nextOreOrEmpty.equals(previousOre)) {
            this.entityData.set(GLOW_ORE, nextOreOrEmpty);
        }
    }

    /**
     * The moment the crossguard meets the face: the clunk of it, and a spray of crumbs of the block it went into, thrown
     * out of the face from around the rod. The crumbs are sent one at a time, each with its own direction, because a
     * burst sent as a single message would fly off in every direction, including into the block.
     */
    private void settle(ServerLevel server) {
        server.playSound(null, getX(), getY(), getZ(), SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.8F, 1.3F);

        BlockState block = support != null && server.hasChunkAt(support) ? server.getBlockState(support) : Blocks.STONE.defaultBlockState();
        if (block.isAir()) {
            return;
        }
        ParticleOptions crumbs = new BlockParticleOption(ParticleTypes.BLOCK, block);
        Direction face = facing();
        RandomSource random = server.getRandom();
        for (int i = 0; i < CRUMBS; i++) {
            // Along the face the crumbs start scattered around the rod, and fly out with some spread. Across it they start
            // just outside the face and fly directly outward. (A particle's velocity is only a bias: the game adds its own
            // randomness to it, so it has to be large to steer them.)
            double sideX = (random.nextDouble() - 0.5) * (face.getStepX() == 0 ? 1.0 : 0.0);
            double sideY = (random.nextDouble() - 0.5) * (face.getStepY() == 0 ? 1.0 : 0.0);
            double sideZ = (random.nextDouble() - 0.5) * (face.getStepZ() == 0 ? 1.0 : 0.0);
            server.sendParticles(crumbs,
                    getX() + face.getStepX() * 0.02 + sideX * 0.2,
                    getY() + face.getStepY() * 0.02 + sideY * 0.2,
                    getZ() + face.getStepZ() * 0.02 + sideZ * 0.2,
                    0,
                    face.getStepX() + sideX * 0.7,
                    face.getStepY() + sideY * 0.7,
                    face.getStepZ() + sideZ * 0.7,
                    1.0);
        }
    }

    // ---- being held by a block, being knocked out, being taken back ----

    private boolean isStillSupported() {
        if (support == null || !level().hasChunkAt(support)) {
            return true; // nothing to check against, or not loaded: assume it is fine
        }
        BlockState state = level().getBlockState(support);
        return state.isFaceSturdy(level(), support, facing());
    }

    /** Takes the geophone out of the world, optionally leaving it as an item where it was. */
    private void pop(boolean drop) {
        Vec3 out = new Vec3(facing().getStepX(), facing().getStepY(), facing().getStepZ()).scale(0.25);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.METAL_BREAK, SoundSource.BLOCKS, 0.8F, 1.2F);
        if (drop) {
            ItemEntity item = new ItemEntity(level(), getX() + out.x, getY() + out.y, getZ() + out.z, dropStack());
            item.setDefaultPickUpDelay();
            level().addFreshEntity(item);
        }
        discard();
    }

    /** The item it turns back into: its own tier's geophone item, never any other's. */
    private ItemStack dropStack() {
        return new ItemStack(SeismicContent.geophoneItemFor(tier()));
    }

    /** A player hitting it knocks it out of the wall and gets it back (unless in creative mode). Nothing else harms it. */
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
     * Right-clicking pulls the geophone out and gives it back to the player: into their hand if it is empty, otherwise into
     * the inventory, and onto the ground at their feet if that is full. (In creative mode it is just taken away, as when
     * it is hit, since nobody needs the item.)
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
            ItemStack geophone = dropStack();
            if (player.getItemInHand(hand).isEmpty()) {
                player.setItemInHand(hand, geophone);
            } else if (!player.getInventory().add(geophone)) {
                player.drop(geophone, false);
            }
        }
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.3F, 1.0F);
        discard();
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public ItemStack getPickResult() {
        return dropStack();
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

    // ---- shape ----

    /** The box it takes up: the rod, out of the face it is staked into. Rebuilt whenever it moves or turns. */
    @Override
    protected AABB makeBoundingBox() {
        Direction facing = this.entityData.get(FACING);
        double[] box = GeophoneOrientation.bounds(getX(), getY(), getZ(), facing.getStepX(), facing.getStepY(), facing.getStepZ());
        return new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (FACING.equals(key)) {
            setBoundingBox(makeBoundingBox());
        }
    }

    /** Geophones are small, but meant to be seen from a way off: you watch a pulse sweep across a field of them. */
    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96.0 * 96.0;
    }

    /**
     * Where the light it is drawn with is measured: just outside the face it is staked into. The geophone's own spot is
     * inside solid rock, which would draw it black.
     */
    @Override
    public Vec3 getLightProbePosition(float partialTicks) {
        Direction facing = facing();
        return new Vec3(getX() + facing.getStepX() * 0.3, getY() + facing.getStepY() * 0.3, getZ() + facing.getStepZ() * 0.3);
    }

    // ---- registry ----

    @Override
    public void onAddedToLevel() {
        super.onAddedToLevel();
        if (level() instanceof ServerLevel server) {
            GeophoneRegistry.add(server, this);
        }
    }

    @Override
    public void onRemovedFromLevel() {
        super.onRemovedFromLevel();
        if (level() instanceof ServerLevel server) {
            GeophoneRegistry.remove(server, this);
        }
    }

    // ---- saving ----

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(FACING, Direction.UP);
        builder.define(LIT, false);
        builder.define(GLOW_ORE, "");
        builder.define(TIER, GeophoneTier.ANDESITE.ordinal());
        builder.define(PLACED_AT, 0L);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putByte("Facing", (byte) facing().get3DDataValue());
        tag.putInt("Tier", this.entityData.get(TIER));
        if (support != null) {
            tag.putIntArray("Support", new int[]{support.getX(), support.getY(), support.getZ()});
        }
        ListTag scheduled = new ListTag();
        for (Pulse pulse : pulses) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("Start", pulse.start());
            entry.putLong("End", pulse.end());
            entry.putString("Ore", pulse.oreId());
            scheduled.add(entry);
        }
        tag.put("Pulses", scheduled);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(FACING, Direction.from3DDataValue(tag.getByte("Facing")));
        this.entityData.set(TIER, tag.getInt("Tier"));
        int[] at = tag.getIntArray("Support");
        support = at.length == 3 ? new BlockPos(at[0], at[1], at[2]) : null;
        pulses.clear();
        // A geophone saved before pulses carried their ore stored them as a plain {start, end} long array; that form has no
        // "Ore" tag, so it reads back in as the empty string, same as never having a colour to show.
        if (tag.contains("Pulses", Tag.TAG_LIST)) {
            ListTag scheduled = tag.getList("Pulses", Tag.TAG_COMPOUND);
            for (int i = 0; i < scheduled.size(); i++) {
                CompoundTag entry = scheduled.getCompound(i);
                pulses.add(new Pulse(entry.getLong("Start"), entry.getLong("End"), entry.getString("Ore")));
            }
        }
        setBoundingBox(makeBoundingBox());
    }
}
