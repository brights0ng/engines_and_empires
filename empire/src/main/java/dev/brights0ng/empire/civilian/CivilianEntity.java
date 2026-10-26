// Written by Claude; extended with abilities, inspection, ticking needs, inventory and eating by ChatGPT.
package dev.brights0ng.empire.civilian;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.civilian.CivilianNetwork.CivilianSpeechPayload;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.CivilianFoodNbt;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.CivilianFood;
import dev.brights0ng.empire.rules.CivilianLines;
import dev.brights0ng.empire.rules.CivilianNames;
import dev.brights0ng.empire.rules.TravellerWants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A civilian of an empire: a slim-player-shaped townsperson with a composited skin ({@link CivilianAppearance}), a name
 * from {@link CivilianNames}, and a home it wanders around. Never despawns.
 *
 * <p>Speech runs on the server once a second and only near players: a line now and then ({@link CivilianLines}), a
 * greeting for a player who comes close, and short scripted conversations with a nearby civilian. Each line goes to the
 * tracking clients ({@link CivilianNetwork}), which draw it as a bubble.
 *
 * <p>Right-clicking starts a dialogue, except for sneak inspection, traveller offers and citizen food hand-over:
 * the civilian stops, faces the player and
 * greets them, and each later click gets a reply, until the player leaves, goes idle or is gone.
 *
 * <p>A civilian is {@link Status#FREE} (its home is where it spawned), a {@link Status#TRAVELLER} waiting at an
 * empire's bonfire to be given the food it wants, a {@link Status#CITIZEN} (the body of a {@link CivilianRecord}, which
 * is the master copy; see {@link CivilianRoster}), or {@link Status#LEAVING}: walking off to vanish.
 */
public class CivilianEntity extends PathfinderMob {

    /** How far from home a civilian wanders (provisional). */
    public static final int HOME_RADIUS = 16;
    /** A civilian speaks only with a (non-spectator) player this close (provisional). */
    public static final int SPEECH_RADIUS = 16;
    /** Ticks between a civilian's lines, rolled in [MIN, MAX] (provisional). */
    public static final int COOLDOWN_MIN = 1200;
    public static final int COOLDOWN_MAX = 3600;
    /** A player coming this close may be greeted, at most once per GREET_GAP ticks (provisional). */
    public static final int GREET_RADIUS = 6;
    public static final int GREET_GAP = 6000;
    /** Chance a due line starts a conversation instead, with a civilian this close (provisional). */
    public static final double TALK_CHANCE = 0.25;
    public static final int TALK_RADIUS = 4;
    /** Ticks between the lines of a conversation (provisional). */
    public static final int TALK_LINE_GAP = 100;
    /** A dialogue ends once its player has been further than DIALOGUE_RANGE for DIALOGUE_LEAVE ticks in a row, or has
     *  not clicked for DIALOGUE_IDLE ticks (provisional). */
    public static final int DIALOGUE_RANGE = 5;
    public static final int DIALOGUE_LEAVE = 200;
    public static final int DIALOGUE_IDLE = 600;
    /** How far from the bonfire a waiting traveller wanders (provisional). */
    public static final int TRAVELLER_RADIUS = 8;
    /** A leaving civilian walks to a point this far from home, away from it (provisional). */
    public static final int LEAVE_DISTANCE = 48;
    /** A leaving civilian vanishes after this many ticks even if it is not there yet (provisional). */
    public static final int LEAVE_TIMEOUT = 1200;
    /** PROVISIONAL: active ticks between writes of a citizen's needs to its master record. */
    public static final int RECORD_SYNC_INTERVAL = 100;
    /** PROVISIONAL: a hotbar of pockets until the carrying system replaces it (Bright, D9). */
    public static final int INVENTORY_SIZE = 9;
    /** PROVISIONAL: active ticks between attempts to eat without wasting nutrition. */
    public static final int EAT_CHECK_INTERVAL = 20;

    /** What a civilian is to the empires (saved). */
    public enum Status {
        FREE, TRAVELLER, CITIZEN, LEAVING
    }

    private static final double RAIN_CHANCE = 0.5;
    private static final double NIGHT_CHANCE = 0.4;
    private static final double GREET_CHANCE = 0.5;
    /** Partners further apart than this end their conversation. */
    private static final double TALK_BREAK_DISTANCE = 6.0;
    private static final int SPEECH_INTERVAL = 20;
    /** Clicks this soon after the civilian's last line get no new line. */
    private static final int CLICK_GAP = 10;
    /** A waiting traveller says what it wants the first time it comes this close to the bonfire. */
    private static final double ANNOUNCE_RADIUS = 6.0;
    /** A leaving civilian vanishes at once when no (non-spectator) player is this close. */
    private static final double LEAVE_WATCH_RADIUS = 24.0;
    private static final double LEAVE_ARRIVED = 2.0;
    private static final double LEAVE_SPEED = 0.6;

    private static final EntityDataAccessor<Integer> APPEARANCE =
            SynchedEntityData.defineId(CivilianEntity.class, EntityDataSerializers.INT);

    private static final String TAG_APPEARANCE = "Appearance";
    private static final String TAG_ABILITIES = "Abilities";
    private static final String TAG_FOOD = "Food";
    private static final String TAG_HOME = "Home";
    private static final String TAG_STATUS = "Status";
    private static final String TAG_CIVILIAN_ID = "CivilianId";
    private static final String TAG_TRAVELLER_EMPIRE = "TravellerEmpire";
    private static final String TAG_WANT = "Want";
    private static final String TAG_LEAVE_AT = "LeaveAt";

    /** A free civilian's own home; a citizen's or traveller's comes from {@link CivilianRoster#homeFor}. */
    @Nullable
    private BlockPos home;
    private Status status = Status.FREE;
    /** The record this civilian is the body of (citizens only). */
    @Nullable
    private UUID civilianId;
    /** Travellers only: the empire it waits at, which of {@code TravellerWants.WANTS} it wants, and when it gives up. */
    @Nullable
    private UUID travellerEmpire;
    private int want;
    private long leaveAt;
    /** Set when the look was chosen before spawning (debug commands), so finalizeSpawn keeps it. */
    private boolean appearancePreset;
    private CivilianAbilities abilities = new CivilianAbilities(3, 3, 3, 3, 3, 3);
    private boolean abilitiesPreset;
    private CivilianFood food = new CivilianFood();
    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE);
    /** Only meals moved out of our pockets are returned when use ends; future tools are left alone. */
    private boolean inventoryMeal;

    // Speech state; transient, rebuilt after a reload. Times are in this civilian's tickCount.
    private final RandomGenerator rng = () -> random.nextLong();
    private int nextLineTick = -1;
    private int lastGreetTick = Integer.MIN_VALUE / 2;
    private int lastLineTick = Integer.MIN_VALUE / 2;
    @Nullable
    private CivilianLines.Category lastCategory;
    private int lastIndex = -1;
    private final Set<UUID> playersInGreetRange = new HashSet<>();
    /** Who this civilian is in conversation with (both sides hold it). */
    @Nullable
    private CivilianEntity talkPartner;
    /** The conversation's script; only the civilian that started it holds this. */
    @Nullable
    private Conversation conversation;
    /** The player this civilian is in dialogue with. */
    @Nullable
    private UUID dialoguePlayer;
    private int lastClickTick;
    private int awayTicks;
    // Roster state; transient.
    /** The chunk this citizen last checked against its record. */
    private long trackedChunk = Long.MIN_VALUE;
    private boolean wantAnnounced;
    private int leaveTicks;
    @Nullable
    private Vec3 leaveTarget;

    private static final class Conversation {
        final CivilianEntity partner;
        final int exchange;
        int step;
        int nextTick;

        Conversation(CivilianEntity partner, int exchange) {
            this.partner = partner;
            this.exchange = exchange;
        }
    }

    public CivilianEntity(EntityType<? extends CivilianEntity> type, Level level) {
        super(type, level);
        setCanPickUpLoot(false);
        ((GroundPathNavigation) getNavigation()).setCanOpenDoors(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 30.0)
                .add(Attributes.MOVEMENT_SPEED, 0.5)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    // Temporary vanilla goals until the real civilian AI exists.
    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new CivilianFaceGoal(this));
        goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, 0.6));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5) {
            @Override
            public boolean canUse() {
                return status != Status.LEAVING && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, 0);
    }

    public CivilianAppearance appearance() {
        return CivilianAppearance.unpack(entityData.get(APPEARANCE));
    }

    /** Sets the look; a citizen's is written through to its record. */
    public void setAppearance(CivilianAppearance appearance) {
        entityData.set(APPEARANCE, appearance.pack());
        if (status == Status.CITIZEN && level() instanceof ServerLevel serverLevel) {
            EmpireSavedData data = EmpireSavedData.of(serverLevel);
            CivilianRecord record = ownRecord(data);
            if (record != null && record.appearance() != appearance.pack()) {
                record.setAppearance(appearance.pack());
                data.setDirty();
            }
        }
    }

    /** Fixes the look before {@link #finalizeSpawn} so it is not rolled. */
    void presetAppearance(CivilianAppearance appearance) {
        setAppearance(appearance);
        appearancePreset = true;
    }

    public CivilianAbilities abilities() {
        return abilities;
    }

    /** Sets scores and writes through to this citizen's master record. */
    public void setAbilities(CivilianAbilities abilities) {
        this.abilities = java.util.Objects.requireNonNull(abilities);
        if (status == Status.CITIZEN && level() instanceof ServerLevel serverLevel) {
            EmpireSavedData data = EmpireSavedData.of(serverLevel);
            CivilianRecord record = ownRecord(data);
            if (record != null && record.abilities() != abilities.pack()) {
                record.setAbilities(abilities.pack());
                data.setDirty();
            }
        }
    }

    void presetAbilities(CivilianAbilities abilities) {
        setAbilities(abilities);
        abilitiesPreset = true;
    }

    public CivilianFood food() {
        return food;
    }

    public SimpleContainer inventory() {
        return inventory;
    }

    /** Applies a master snapshot without writing it back during join. */
    void restoreNeeds(CivilianRecord record) {
        food = CivilianFood.restore(record.food());
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, record.inventory().get(slot).copy());
        }
        if (!Float.isNaN(record.health())) {
            setHealth(Math.clamp(record.health(), 1.0F, getMaxHealth()));
        }
    }

    /** Debug edit: preserve timers and intake history, and immediately write through for citizens. */
    public void setFood(int level, float saturation) {
        CivilianFood.Saved saved = food.save();
        int clamped = Math.clamp(level, 0, 20);
        food = CivilianFood.restore(new CivilianFood.Saved(clamped,
                Math.clamp(Float.isFinite(saturation) ? saturation : 0.0F, 0.0F, (float) clamped),
                saved.exhaustion(), saved.tickTimer(), saved.foodEatenToday(), saved.foodEatenYesterday(), saved.activeTicks()));
        syncNeedsToRecord();
    }

    /** Single write-back point for current and future body-owned needs, guarded against stale or dead bodies. */
    public void syncNeedsToRecord() {
        // Removed bodies can still have live needs to flush during unload/discard.
        if (status != Status.CITIZEN || dead || getHealth() <= 0.0F || getRemovalReason() == RemovalReason.KILLED
                || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        CivilianRecord record = ownRecord(data);
        CivilianFood.Saved saved = food.save();
        boolean inventoryChanged = false;
        if (record != null) {
            for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
                if (!ItemStack.matches(inventory.getItem(slot), record.inventory().get(slot))) {
                    inventoryChanged = true;
                    break;
                }
            }
        }
        if (record != null && (inventoryChanged || !saved.equals(record.food()) || Float.compare(getHealth(), record.health()) != 0)) {
            record.setFood(saved);
            record.setHealth(getHealth());
            record.setInventory(inventory.getItems());
            data.setDirty();
        }
    }

    @Override
    public void onRemovedFromLevel() {
        if (getRemovalReason() != RemovalReason.KILLED) {
            if (inventoryMeal) stopUsingItem();
            syncNeedsToRecord();
        }
        super.onRemovedFromLevel();
    }

    @Override
    protected void actuallyHurt(DamageSource source, float amount) {
        float before = getHealth();
        super.actuallyHurt(source, amount);
        // Player charges only damage reaching health, after armor, magic and absorption.
        if (!level().isClientSide && status != Status.LEAVING && getHealth() < before) {
            food.addExhaustion(source.getFoodExhaustion());
        }
    }

    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        if (!level().isClientSide && status != Status.LEAVING) {
            food.addExhaustion(0.05F);
        }
    }

    @Override
    public void travel(Vec3 movement) {
        Vec3 before = position();
        super.travel(movement);
        if (!level().isClientSide && status != Status.LEAVING && !isPassenger()) {
            double dx = getX() - before.x;
            double dy = getY() - before.y;
            double dz = getZ() - before.z;
            // ServerPlayer rounds to centimetres; wading excludes vertical movement.
            boolean submerged = isSwimming() || isEyeInFluid(FluidTags.WATER);
            if (submerged || isInWater()) {
                int centimetres = Math.round((float) Math.sqrt(dx * dx + dz * dz + (submerged ? dy * dy : 0.0)) * 100.0F);
                if (centimetres > 0) {
                    food.addExhaustion(0.01F * centimetres * 0.01F);
                }
            }
        }
    }

    @Nullable
    public BlockPos home() {
        return home;
    }

    public Status status() {
        return status;
    }

    @Nullable
    public UUID civilianId() {
        return civilianId;
    }

    @Nullable
    public UUID travellerEmpire() {
        return travellerEmpire;
    }

    /** A traveller's want, an index into {@code TravellerWants.WANTS}. */
    public int want() {
        return want;
    }

    public long leaveAt() {
        return leaveAt;
    }

    /** Sets the home and keeps the civilian within {@code radius} of it. */
    void setHome(BlockPos home, int radius) {
        this.home = home.immutable();
        restrictTo(this.home, radius);
    }

    /** Makes this civilian the body of record {@code id}, living at {@code home}. */
    void becomeCitizen(UUID id, BlockPos home) {
        status = Status.CITIZEN;
        civilianId = id;
        travellerEmpire = null;
        trackedChunk = Long.MIN_VALUE;
        setHome(home, HOME_RADIUS);
    }

    /** Makes this civilian a traveller come to {@code empire}'s bonfire at {@code home}. */
    void becomeTraveller(UUID empire, int want, long leaveAt, BlockPos home) {
        status = Status.TRAVELLER;
        civilianId = null;
        travellerEmpire = empire;
        this.want = want;
        this.leaveAt = leaveAt;
        wantAnnounced = false;
        setHome(home, TRAVELLER_RADIUS);
    }

    /** Walks off, away from home, to vanish; says nothing more. */
    public void startLeaving() {
        if (status == Status.LEAVING) {
            return;
        }
        if (inventoryMeal) stopUsingItem();
        status = Status.LEAVING;
        clearRestriction();
        endConversation();
        dialoguePlayer = null;
        getNavigation().stop();
        leaveTicks = 0;
        leaveTarget = null;
    }

    /** This citizen's record, if this civilian is still its body. */
    @Nullable
    CivilianRecord ownRecord(EmpireSavedData data) {
        CivilianRecord record = civilianId != null ? data.civilian(civilianId) : null;
        return record != null && record.body().equals(getUUID()) ? record : null;
    }

    @Override
    @Nullable
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType,
                                        @Nullable SpawnGroupData spawnGroupData) {
        SpawnGroupData data = super.finalizeSpawn(level, difficulty, spawnType, spawnGroupData);
        setPersistenceRequired();
        if (!appearancePreset) {
            setAppearance(CivilianAppearance.random(random::nextLong));
        }
        if (!abilitiesPreset) {
            setAbilities(CivilianAbilities.roll(random::nextLong));
        }
        setCustomName(Component.literal(CivilianNames.pick(random::nextLong)));
        setCustomNameVisible(true);

        status = Status.FREE;
        setHome(blockPosition(), HOME_RADIUS);
        return data;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        // Use progress is not saved by vanilla. Return the meal before equipment and record snapshots diverge.
        if (inventoryMeal && !level().isClientSide) stopUsingItem();
        super.addAdditionalSaveData(tag);
        syncNeedsToRecord();
        tag.put(TAG_FOOD, CivilianFoodNbt.save(food.save()));
        tag.put("Inventory", ContainerHelper.saveAllItems(new CompoundTag(), inventory.getItems(), level().registryAccess()));
        tag.putInt(TAG_APPEARANCE, entityData.get(APPEARANCE));
        tag.putInt(TAG_ABILITIES, abilities.pack());
        if (home != null) {
            tag.put(TAG_HOME, NbtUtils.writeBlockPos(home));
        }
        tag.putString(TAG_STATUS, status.name());
        if (civilianId != null) {
            tag.putUUID(TAG_CIVILIAN_ID, civilianId);
        }
        if (travellerEmpire != null) {
            tag.putUUID(TAG_TRAVELLER_EMPIRE, travellerEmpire);
            tag.putInt(TAG_WANT, want);
            tag.putLong(TAG_LEAVE_AT, leaveAt);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        food = CivilianFood.restore(CivilianFoodNbt.load(tag.getCompound(TAG_FOOD)));
        inventory.clearContent();
        ContainerHelper.loadAllItems(tag.getCompound("Inventory"), inventory.getItems(), level().registryAccess());
        CivilianAbilities loadedAbilities = null;
        if (tag.contains(TAG_ABILITIES, net.minecraft.nbt.Tag.TAG_INT)) {
            try {
                loadedAbilities = CivilianAbilities.unpack(tag.getInt(TAG_ABILITIES));
            } catch (IllegalArgumentException ignored) {
                // Missing or corrupt scores get a fresh roll; a citizen's master copy wins on join.
            }
        }
        abilities = loadedAbilities != null ? loadedAbilities : CivilianAbilities.roll(random::nextLong);
        if (tag.contains(TAG_APPEARANCE)) {
            try {
                setAppearance(CivilianAppearance.unpack(tag.getInt(TAG_APPEARANCE)));
            } catch (IllegalArgumentException ignored) {
                // A corrupt look keeps the default rather than failing the whole entity.
            }
        }
        home = NbtUtils.readBlockPos(tag, TAG_HOME).orElse(null);
        try {
            status = Status.valueOf(tag.getString(TAG_STATUS));
        } catch (IllegalArgumentException e) {
            status = Status.FREE;
        }
        civilianId = tag.hasUUID(TAG_CIVILIAN_ID) ? tag.getUUID(TAG_CIVILIAN_ID) : null;
        travellerEmpire = tag.hasUUID(TAG_TRAVELLER_EMPIRE) ? tag.getUUID(TAG_TRAVELLER_EMPIRE) : null;
        want = Math.floorMod(tag.getInt(TAG_WANT), TravellerWants.WANTS.size());
        leaveAt = tag.getLong(TAG_LEAVE_AT);
        // Vanilla does not save the restriction. A citizen's or traveller's home is set again as it joins the level.
        if (home != null && status != Status.LEAVING) {
            restrictTo(home, status == Status.TRAVELLER ? TRAVELLER_RADIUS : HOME_RADIUS);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (status == Status.CITIZEN) {
            trackChunk(serverLevel);
        }
        boolean speechTick = (tickCount + getId()) % SPEECH_INTERVAL == 0;
        if (speechTick && status != Status.LEAVING) {
            tickRoster(serverLevel);
        }
        if (status == Status.LEAVING) {
            tickLeaving();
            return;
        }
        if (isAlive() && !isRemoved()) {
            food.countActiveTick();
            food.addExhaustion((float) EmpireConfig.civilianBasalExhaustionPerTick());
            CivilianFood.TickResult result = food.tick(getHealth(), getMaxHealth(),
                    serverLevel.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION),
                    serverLevel.getDifficulty() == Difficulty.PEACEFUL);
            if (result.healAmount() > 0.0F) heal(result.healAmount());
            if (result.starveDamage() > 0.0F) hurt(damageSources().starve(), result.starveDamage());
            if (tickCount % EAT_CHECK_INTERVAL == 0) tryEatFromInventory();
            if (tickCount % RECORD_SYNC_INTERVAL == 0) syncNeedsToRecord();
        }
        tickDialogue();
        if (speechTick) {
            tickSpeech();
        }
    }

    private void tryEatFromInventory() {
        if (isUsingItem() || !getMainHandItem().isEmpty()) return;
        int missing = 20 - food.foodLevel();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            FoodProperties properties = stack.getFoodProperties(this);
            if (!stack.isEmpty() && properties != null && properties.nutrition() <= missing) {
                setItemInHand(InteractionHand.MAIN_HAND, inventory.removeItem(slot, 1));
                inventoryMeal = true;
                startUsingItem(InteractionHand.MAIN_HAND);
                // NeoForge can cancel the start-use event. The food still belongs in the pockets.
                if (!isUsingItem()) returnInventoryMeal();
                return;
            }
        }
    }

    @Override
    public ItemStack eat(Level level, ItemStack stack, FoodProperties properties) {
        if (!level.isClientSide) food.eat(properties.nutrition(), properties.saturation());
        ItemStack result = super.eat(level, stack, properties);
        // In 1.21.1 this conversion belongs to Player.eat, not LivingEntity or Item.finishUsingItem.
        if (!level.isClientSide && !hasInfiniteMaterials() && properties.usingConvertsTo().isPresent()) {
            ItemStack container = properties.usingConvertsTo().get().copy();
            if (result.isEmpty()) result = container;
            else storeOrDrop(container);
        }
        syncNeedsToRecord();
        return result;
    }

    @Override
    public void stopUsingItem() {
        super.stopUsingItem();
        // completeUsingItem installs finishUsingItem's returned stack BEFORE calling this hook.
        if (!level().isClientSide && inventoryMeal) returnInventoryMeal();
    }

    private void returnInventoryMeal() {
        inventoryMeal = false;
        ItemStack remainder = getMainHandItem();
        setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        storeOrDrop(remainder);
        syncNeedsToRecord();
    }

    private void storeOrDrop(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack overflow = inventory.addItem(stack);
        if (!overflow.isEmpty()) spawnAtLocation(overflow);
    }

    /** Keeps a citizen's record's last position current: written whenever the civilian is in a new chunk. */
    private void trackChunk(ServerLevel level) {
        long chunk = chunkPosition().toLong();
        if (chunk == trackedChunk) {
            return;
        }
        trackedChunk = chunk;
        EmpireSavedData data = EmpireSavedData.of(level);
        CivilianRecord record = ownRecord(data);
        if (record != null && record.lastChunk() != chunk) {
            record.setLast(blockPosition(), chunk);
            data.setDirty();
        }
    }

    private void tickRoster(ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        if (status == Status.CITIZEN) {
            CivilianRecord record = civilianId != null ? data.civilian(civilianId) : null;
            if (record == null) {
                // The record went while this body was loaded (an empire disbanded with no server to tell its bodies).
                startLeaving();
            } else if (!record.body().equals(getUUID())) {
                // A newer body has replaced this one.
                discard();
            }
        } else if (status == Status.TRAVELLER) {
            if (level.getGameTime() >= leaveAt) {
                if (playerWithin(SPEECH_RADIUS)) {
                    sayKey("civilian.empire.traveller.leaving");
                }
                CivilianRoster.clearTravellerSlot(data, this);
                startLeaving();
            } else if (!wantAnnounced && home != null
                    && distanceToSqr(Vec3.atBottomCenterOf(home)) <= ANNOUNCE_RADIUS * ANNOUNCE_RADIUS
                    && playerWithin(SPEECH_RADIUS)) {
                wantAnnounced = true;
                sayWant();
            }
        }
    }

    /** Walks to the leave point; vanishes once there, after LEAVE_TIMEOUT ticks, or as soon as no player is near. */
    private void tickLeaving() {
        leaveTicks++;
        if (leaveTarget == null) {
            Vec3 from = home != null ? Vec3.atBottomCenterOf(home) : position();
            Vec3 away = new Vec3(getX() - from.x, 0.0, getZ() - from.z);
            if (away.lengthSqr() < 0.25) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                away = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
            }
            away = away.normalize().scale(LEAVE_DISTANCE);
            leaveTarget = new Vec3(from.x + away.x, getY(), from.z + away.z);
        }
        double dx = leaveTarget.x - getX();
        double dz = leaveTarget.z - getZ();
        if (dx * dx + dz * dz <= LEAVE_ARRIVED * LEAVE_ARRIVED || leaveTicks >= LEAVE_TIMEOUT
                || !playerWithin(LEAVE_WATCH_RADIUS)) {
            discard();
            return;
        }
        // The path finder reaches only about FOLLOW_RANGE, so ask again each time a partial path runs out.
        if (getNavigation().isDone()) {
            getNavigation().moveTo(leaveTarget.x, leaveTarget.y, leaveTarget.z, LEAVE_SPEED);
        }
    }

    @Override
    public void die(DamageSource damageSource) {
        boolean wasDead = dead;
        super.die(damageSource);
        if (!wasDead && dead && level() instanceof ServerLevel serverLevel) {
            CivilianRoster.onDeath(this, serverLevel);
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        // Clear hands before Mob rolls equipment drops: carried items drop exactly once, without a chance roll.
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack carried = getItemInHand(hand);
            setItemInHand(hand, ItemStack.EMPTY);
            if (!carried.isEmpty()) spawnAtLocation(carried);
        }
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack carried = inventory.removeItemNoUpdate(slot);
            if (!carried.isEmpty()) spawnAtLocation(carried);
        }
        super.dropCustomDeathLoot(level, source, recentlyHit);
    }

    /** Who {@link CivilianFaceGoal} keeps this civilian facing, or null. */
    @Nullable
    Entity faceTarget() {
        if (dialoguePlayer != null) {
            return level().getPlayerByUUID(dialoguePlayer);
        }
        return talkPartner;
    }

    /** The player this civilian is in dialogue with, or null. */
    @Nullable
    public UUID dialoguePlayer() {
        return dialoguePlayer;
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        return talkTo(player);
    }

    /**
     * A right-click: inspects, hands food to a citizen, offers a traveller its want, or starts/continues a dialogue.
     */
    InteractionResult talkTo(Player player) {
        if (!level().isClientSide) {
            if (player.isSpectator()) {
                return InteractionResult.PASS;
            }
            if (player.isSecondaryUseActive() && status != Status.LEAVING) {
                player.sendSystemMessage(Component.translatable("message.empire.civilian.abilities", getName(),
                        abilities.endurance(), abilities.precision(), abilities.willpower(), abilities.wits(),
                        abilities.organization(), abilities.teamwork()));
                return InteractionResult.SUCCESS;
            }
            if (status == Status.LEAVING) {
                return InteractionResult.CONSUME;
            }
            ItemStack held = player.getMainHandItem();
            if (status == Status.CITIZEN && held.getFoodProperties(this) != null) {
                ItemStack remainder = inventory.addItem(held.copy());
                int moved = held.getCount() - remainder.getCount();
                if (!player.getAbilities().instabuild) held.shrink(moved);
                syncNeedsToRecord();
                sayKey(moved > 0 ? "civilian.empire.citizen.food_given" : "civilian.empire.citizen.pockets_full");
                return InteractionResult.SUCCESS;
            }
            // A traveller answers with its own line (its want, or what it makes of the offer) instead of a greeting or reply.
            boolean travellerSpoke = false;
            if (status == Status.TRAVELLER) {
                CivilianRoster.travellerClicked(this, player);
                travellerSpoke = true;
                if (status == Status.LEAVING) {
                    return InteractionResult.CONSUME;
                }
            }
            endConversation();
            boolean started = !player.getUUID().equals(dialoguePlayer);
            dialoguePlayer = player.getUUID();
            lastClickTick = tickCount;
            awayTicks = 0;
            if (!travellerSpoke && tickCount - lastLineTick >= CLICK_GAP) {
                say(started ? CivilianLines.Category.GREETING : CivilianLines.Category.REPLY);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
    }

    private void tickDialogue() {
        if (dialoguePlayer == null) {
            return;
        }
        // Null when the player has left or is in another dimension.
        Player player = level().getPlayerByUUID(dialoguePlayer);
        if (player == null || !player.isAlive() || tickCount - lastClickTick > DIALOGUE_IDLE) {
            dialoguePlayer = null;
            return;
        }
        if (distanceToSqr(player) > (double) DIALOGUE_RANGE * DIALOGUE_RANGE) {
            if (++awayTicks >= DIALOGUE_LEAVE) {
                dialoguePlayer = null;
            }
        } else {
            awayTicks = 0;
        }
    }

    private void tickSpeech() {
        if (nextLineTick < 0) {
            // The first wait is shorter and random, so a crowd does not speak in sync.
            nextLineTick = tickCount + random.nextInt(COOLDOWN_MIN + 1);
        }
        if (talkPartner != null) {
            tickConversation();
            return;
        }
        if (dialoguePlayer != null) {
            return;
        }
        if (!playerWithin(SPEECH_RADIUS)) {
            playersInGreetRange.clear();
            return;
        }
        if (tickGreeting()) {
            return;
        }
        if (tickCount < nextLineTick) {
            return;
        }
        if (random.nextDouble() < TALK_CHANCE && startConversation()) {
            return;
        }
        say(ambientCategory());
        resetLineCooldown();
    }

    private CivilianLines.Category ambientCategory() {
        if (level().isRainingAt(blockPosition().above()) && random.nextDouble() < RAIN_CHANCE) {
            return CivilianLines.Category.RAIN;
        }
        if (level().isNight() && random.nextDouble() < NIGHT_CHANCE) {
            return CivilianLines.Category.NIGHT;
        }
        return CivilianLines.Category.IDLE;
    }

    /** Greets (half the time) when a player newly comes within GREET_RADIUS and the last greeting was GREET_GAP ago. */
    private boolean tickGreeting() {
        Set<UUID> now = new HashSet<>();
        for (Player player : level().players()) {
            if (!player.isSpectator() && distanceToSqr(player) <= (double) GREET_RADIUS * GREET_RADIUS) {
                now.add(player.getUUID());
            }
        }
        boolean newcomer = !playersInGreetRange.containsAll(now);
        playersInGreetRange.clear();
        playersInGreetRange.addAll(now);
        if (newcomer && tickCount - lastGreetTick >= GREET_GAP && random.nextDouble() < GREET_CHANCE) {
            lastGreetTick = tickCount;
            say(CivilianLines.Category.GREETING);
            return true;
        }
        return false;
    }

    private boolean playerWithin(double radius) {
        double r2 = radius * radius;
        for (Player player : level().players()) {
            if (!player.isSpectator() && distanceToSqr(player) <= r2) {
                return true;
            }
        }
        return false;
    }

    private void resetLineCooldown() {
        nextLineTick = tickCount + COOLDOWN_MIN + random.nextInt(COOLDOWN_MAX - COOLDOWN_MIN + 1);
    }

    /** Whether this civilian can be drawn into a conversation. */
    private boolean available() {
        return isAlive() && talkPartner == null && dialoguePlayer == null;
    }

    private boolean startConversation() {
        CivilianEntity partner = null;
        double best = Double.MAX_VALUE;
        for (CivilianEntity other : level().getEntitiesOfClass(CivilianEntity.class, getBoundingBox().inflate(TALK_RADIUS))) {
            double d2 = distanceToSqr(other);
            if (other != this && other.available() && d2 <= (double) TALK_RADIUS * TALK_RADIUS && d2 < best
                    && hasLineOfSight(other)) {
                partner = other;
                best = d2;
            }
        }
        if (partner == null) {
            return false;
        }
        Conversation c = new Conversation(partner, random.nextInt(CivilianLines.TALK_LENGTHS.length));
        conversation = c;
        talkPartner = partner;
        partner.talkPartner = this;
        partner.conversation = null;
        c.nextTick = tickCount;
        tickConversation();
        return true;
    }

    private void tickConversation() {
        CivilianEntity partner = talkPartner;
        if (partner == null) {
            return;
        }
        if (conversation == null) {
            // The partner's side: the starter drives the script; drop the link if the starter is gone or let go.
            if (partner.isRemoved() || partner.talkPartner != this) {
                endConversation();
            }
            return;
        }
        if (partner.isRemoved() || !partner.isAlive() || partner.talkPartner != this
                || dialoguePlayer != null || partner.dialoguePlayer != null
                || distanceToSqr(partner) > TALK_BREAK_DISTANCE * TALK_BREAK_DISTANCE) {
            endConversation();
            return;
        }
        Conversation c = conversation;
        if (tickCount < c.nextTick) {
            return;
        }
        CivilianEntity speaker = c.step % 2 == 0 ? this : partner;
        speaker.sayKey(CivilianLines.talkKey(c.exchange, c.step));
        c.step++;
        if (c.step >= CivilianLines.TALK_LENGTHS[c.exchange]) {
            endConversation();
        } else {
            c.nextTick = tickCount + TALK_LINE_GAP;
        }
    }

    /** Ends this civilian's conversation, on both sides; both get a fresh cooldown. */
    void endConversation() {
        CivilianEntity other = talkPartner;
        if (other == null) {
            return;
        }
        talkPartner = null;
        conversation = null;
        resetLineCooldown();
        if (other.talkPartner == this) {
            other.talkPartner = null;
            other.conversation = null;
            other.resetLineCooldown();
        }
    }

    /** Says a line of {@code category}, never the one this civilian said last. */
    public void say(CivilianLines.Category category) {
        int last = category == lastCategory ? lastIndex : -1;
        int index = CivilianLines.pick(category, rng, last);
        lastCategory = category;
        lastIndex = index;
        sayKey(CivilianLines.key(category, index));
    }

    /** Sends a line (a lang key) to the players tracking this civilian. */
    public void sayKey(String key) {
        sayKey(key, new String[0]);
    }

    /** A traveller's line naming what it wants and how many. */
    void sayWant() {
        sayKey(TravellerWants.wantKey(want), String.valueOf(TravellerWants.WANTS.get(want).count()));
    }

    /** Sends a line (a lang key and its arguments, as text) to the players tracking this civilian. Not while leaving. */
    public void sayKey(String key, String... args) {
        if (level().isClientSide || status == Status.LEAVING) {
            return;
        }
        lastLineTick = tickCount;
        PacketDistributor.sendToPlayersTrackingEntity(this, new CivilianSpeechPayload(getId(), key, List.of(args)));
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource damageSource) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }
}
