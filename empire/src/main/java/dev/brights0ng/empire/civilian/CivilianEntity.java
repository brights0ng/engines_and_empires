// Written by Claude; extended with abilities, inspection, ticking needs, inventory, eating and client eating sync by ChatGPT; extended with sleep persistence, behavior, fulfilment, traits, live derived mechanics and occupational skills by Gemini.
package dev.brights0ng.empire.civilian;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.civilian.CivilianNetwork.CivilianSpeechPayload;
import dev.brights0ng.empire.civilian.ai.CivilianAiController;
import dev.brights0ng.empire.civilian.ai.CivilianAiGoal;
import dev.brights0ng.empire.civilian.ai.EquipAction;
import dev.brights0ng.empire.civilian.ai.FarmCrops;
import dev.brights0ng.empire.data.CivilianFulfilmentNbt;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.CivilianFoodNbt;
import dev.brights0ng.empire.data.CivilianScheduleNbt;
import dev.brights0ng.empire.data.CivilianSkillsNbt;
import dev.brights0ng.empire.data.CivilianSleepNbt;
import dev.brights0ng.empire.data.CivilianTraitsNbt;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.data.WorkAssignment;
import dev.brights0ng.empire.ledger.LedgerSelection;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianAbilities;
import dev.brights0ng.empire.rules.CivilianDerivedStats;
import dev.brights0ng.empire.rules.CivilianFood;
import dev.brights0ng.empire.rules.CivilianFulfilment;
import dev.brights0ng.empire.rules.CivilianLines;
import dev.brights0ng.empire.rules.CivilianNames;
import dev.brights0ng.empire.rules.CivilianPreferences;
import dev.brights0ng.empire.rules.CivilianQuirk;
import dev.brights0ng.empire.rules.CivilianSchedule;
import dev.brights0ng.empire.rules.CivilianSkill;
import dev.brights0ng.empire.rules.CivilianSkills;
import dev.brights0ng.empire.rules.CivilianSleep;
import dev.brights0ng.empire.rules.CivilianTraits;
import dev.brights0ng.empire.rules.CivilianTraits.BaseTrait;
import dev.brights0ng.empire.rules.CrowdMemory;
import dev.brights0ng.empire.rules.Occupation;
import dev.brights0ng.empire.rules.RegionRules;
import dev.brights0ng.empire.rules.RouteMemory;
import dev.brights0ng.empire.rules.TravellerWants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
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
import net.minecraft.world.level.pathfinder.PathType;
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
    /** Ticks after being woken by damage or a click before lying down again (confirmed). */
    public static final int WAKE_COOLDOWN = 200;

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
    private static final EntityDataAccessor<Boolean> ROUGH_SLEEPING =
            SynchedEntityData.defineId(CivilianEntity.class, EntityDataSerializers.BOOLEAN);

    private static final ResourceLocation HUNGER_WEAKNESS_MODIFIER_ID = Empire.id("hunger_weakness");

    private static final String TAG_APPEARANCE = "Appearance";
    private static final String TAG_TRAITS = "Traits";
    private static final String TAG_SKILLS = "Skills";
    private static final String TAG_FOOD = "Food";
    private static final String TAG_SLEEP = "Sleep";
    private static final String TAG_FULFILMENT = "Fulfilment";
    private static final String TAG_SCHEDULE = "Schedule";
    private static final String TAG_BED = "Bed";
    private static final String TAG_ROUGH_SLEEPING = "RoughSleeping";
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
    private CivilianTraits traits = CivilianTraits.NEUTRAL;
    private CivilianAbilities abilities = CivilianTraits.NEUTRAL.abilities();
    private List<CivilianQuirk> quirks = List.of();
    private CivilianDerivedStats derivedStats = CivilianDerivedStats.NEUTRAL;
    private boolean traitsPreset;
    private CivilianSkills skills = new CivilianSkills();
    private boolean skillsPreset;
    private double appliedHungerWeakness = 0.0;
    private CivilianFood food = new CivilianFood();
    private CivilianSleep sleep = new CivilianSleep();
    private CivilianFulfilment fulfilment = new CivilianFulfilment(CivilianPreferences.defaultFor(getUUID()));
    private boolean preferencesPreset;
    private CivilianSchedule schedule = new CivilianSchedule();
    private RouteMemory routeMemory = new RouteMemory();
    private CrowdMemory crowdMemory = new CrowdMemory();
    @Nullable
    private WorkAssignment workAssignment;
    @Nullable
    private BlockPos claimedBed;
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
    // Sleep runtime state; transient.
    private boolean sleepClockReady;
    private int wakeCooldownUntil = Integer.MIN_VALUE / 2;
    private long unreachableNight = CivilianSleep.NO_NIGHT;
    @Nullable
    private BlockPos unreachableBed;
    @Nullable
    private UUID unreachableRegionId;
    private long unreachableRegionRevision;
    @Nullable
    private CivilianAiController aiController;

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
        // Hard-forbid fire, campfires, magma, and bonfire outer cells (default DAMAGE_FIRE malus is +16.0F, soft).
        setPathfindingMalus(PathType.DAMAGE_FIRE, -1.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, CivilianDerivedStats.BASE_MAX_HEALTH)
                .add(Attributes.MOVEMENT_SPEED, CivilianDerivedStats.BASE_MOVEMENT_SPEED)
                .add(Attributes.FOLLOW_RANGE, CivilianDerivedStats.BASE_FOLLOW_RANGE)
                .add(Attributes.JUMP_STRENGTH, CivilianDerivedStats.BASE_JUMP_STRENGTH)
                .add(Attributes.SAFE_FALL_DISTANCE, CivilianDerivedStats.BASE_SAFE_FALL_DISTANCE)
                .add(Attributes.FALL_DAMAGE_MULTIPLIER, 1.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, CivilianDerivedStats.BASE_KNOCKBACK_RESISTANCE)
                .add(Attributes.ATTACK_DAMAGE, CivilianDerivedStats.BASE_ATTACK_DAMAGE)
                .add(Attributes.ATTACK_KNOCKBACK, CivilianDerivedStats.BASE_ATTACK_KNOCKBACK)
                .add(Attributes.MOVEMENT_EFFICIENCY, CivilianDerivedStats.BASE_MOVEMENT_EFFICIENCY)
                .add(Attributes.WATER_MOVEMENT_EFFICIENCY, CivilianDerivedStats.BASE_WATER_MOVEMENT_EFFICIENCY)
                .add(Attributes.OXYGEN_BONUS, CivilianDerivedStats.BASE_OXYGEN_BONUS);
    }

    @Override
    protected void registerGoals() {
        aiController = new CivilianAiController(this);
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new CivilianFaceGoal(this));
        goalSelector.addGoal(2, new OpenDoorGoal(this, true));
        goalSelector.addGoal(3, new CivilianAiGoal(this, aiController));
        goalSelector.addGoal(4, new MoveTowardsRestrictionGoal(this, 0.6) {
            @Override
            public boolean canUse() {
                return status != Status.CITIZEN && status != Status.LEAVING && super.canUse();
            }
        });
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5) {
            @Override
            public boolean canUse() {
                return status != Status.CITIZEN && status != Status.LEAVING && super.canUse();
            }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(APPEARANCE, 0);
        builder.define(ROUGH_SLEEPING, false);
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

    public CivilianTraits traits() {
        return traits;
    }

    public CivilianAbilities abilities() {
        return abilities;
    }

    public List<CivilianQuirk> quirks() {
        return quirks;
    }

    public CivilianDerivedStats derivedStats() {
        return derivedStats;
    }

    /** Sets underlying 11 traits, recalculates derived scores/quirks/attributes, and writes through to the record. */
    public void setTraits(CivilianTraits traits) {
        applyTraits(traits, false, true);
    }

    /** Fixes traits before {@link #finalizeSpawn} so they are not rolled and spawn health starts at full max HP. */
    void presetTraits(CivilianTraits traits) {
        applyTraits(traits, true, false);
        traitsPreset = true;
    }

    private void applyTraits(CivilianTraits traits, boolean healToFull, boolean writeThrough) {
        this.traits = Objects.requireNonNull(traits, "traits");
        this.abilities = traits.abilities();
        this.quirks = traits.quirks();
        this.derivedStats = traits.derivedStats();
        applyTraitAttributes(healToFull);
        if (writeThrough && status == Status.CITIZEN && level() instanceof ServerLevel serverLevel) {
            EmpireSavedData data = EmpireSavedData.of(serverLevel);
            CivilianRecord record = ownRecord(data);
            if (record != null && !traits.equals(record.traits())) {
                record.setTraits(traits);
                data.setDirty();
            }
        }
    }

    private void applyTraitAttributes(boolean healToFull) {
        setAttributeBase(Attributes.MAX_HEALTH, derivedStats.maxHealth());
        setAttributeBase(Attributes.MOVEMENT_SPEED, derivedStats.movementSpeed());
        setAttributeBase(Attributes.JUMP_STRENGTH, derivedStats.jumpStrength());
        setAttributeBase(Attributes.SAFE_FALL_DISTANCE, derivedStats.safeFallDistance());
        setAttributeBase(Attributes.FALL_DAMAGE_MULTIPLIER, derivedStats.fallDamageMultiplier());
        setAttributeBase(Attributes.KNOCKBACK_RESISTANCE, derivedStats.knockbackResistance());
        setAttributeBase(Attributes.ATTACK_DAMAGE, derivedStats.attackDamage());
        setAttributeBase(Attributes.ATTACK_KNOCKBACK, derivedStats.attackKnockback());
        setAttributeBase(Attributes.MOVEMENT_EFFICIENCY, derivedStats.movementEfficiency());
        setAttributeBase(Attributes.WATER_MOVEMENT_EFFICIENCY, derivedStats.waterMovementEfficiency());
        setAttributeBase(Attributes.OXYGEN_BONUS, derivedStats.oxygenBonus());
        setAttributeBase(Attributes.FOLLOW_RANGE, derivedStats.followRange());
        if (healToFull) {
            setHealth(getMaxHealth());
        } else if (getHealth() > getMaxHealth()) {
            setHealth(getMaxHealth());
        }
        updateHungerStrengthModifier();
    }

    private void setAttributeBase(Holder<Attribute> attribute, double value) {
        AttributeInstance inst = getAttribute(attribute);
        if (inst != null && Double.compare(inst.getBaseValue(), value) != 0) {
            inst.setBaseValue(value);
        }
    }

    /** Applies or clears the transient hunger-weakness modifier on attack damage when food is below threshold. */
    void updateHungerStrengthModifier() {
        AttributeInstance atk = getAttribute(Attributes.ATTACK_DAMAGE);
        if (atk == null) {
            return;
        }
        double factor = derivedStats.hungerStrengthFactor(food.foodLevel());
        double delta = factor < 1.0 - 1e-6 ? factor - 1.0 : 0.0;
        boolean hasMod = atk.hasModifier(HUNGER_WEAKNESS_MODIFIER_ID);
        if (delta == 0.0) {
            if (hasMod) {
                atk.removeModifier(HUNGER_WEAKNESS_MODIFIER_ID);
            }
            appliedHungerWeakness = 0.0;
        } else if (!hasMod || Math.abs(delta - appliedHungerWeakness) > 1e-6) {
            atk.removeModifier(HUNGER_WEAKNESS_MODIFIER_ID);
            atk.addTransientModifier(new AttributeModifier(
                    HUNGER_WEAKNESS_MODIFIER_ID, delta, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            appliedHungerWeakness = delta;
        }
    }

    public CivilianFood food() {
        return food;
    }

    public CivilianSkills skills() {
        return skills;
    }

    /** Replaces skills before {@link #finalizeSpawn} so starting skills are not rolled. */
    void presetSkills(CivilianSkills skills) {
        this.skills = Objects.requireNonNull(skills, "skills");
        this.skillsPreset = true;
    }

    /** Current comfort reading on {@code [0, 1]} for cold/fatigue precision checks (defaults to {@code 1.0} if unsampled). */
    public double currentComfortScore() {
        return fulfilment.hasComfortSamples() ? fulfilment.averageComfort() : 1.0;
    }

    /** Live rookie fumble probability for {@code skill}, accounting for skill level, traits, and cold/fatigue stress. */
    public double currentFumbleChance(CivilianSkill skill) {
        return CivilianSkills.fumbleChance(
                skills.level(skill),
                derivedStats,
                currentComfortScore(),
                sleep.lastNight(),
                derivedStats.hungerStrengthFactor(food.foodLevel()));
    }

    /**
     * Awards XP for a completed action in {@code skill} scaled by time efficiency, trait learning rate, Fulfilment
     * productivity, and sleep consolidation, then writes through to the master record.
     */
    public double awardSkillActionXp(ServerLevel serverLevel, CivilianSkill skill, double baseActionXp) {
        double productivity = CivilianFulfilmentMeters.evaluate(serverLevel, this).productivity();
        double gained = skills.recordAction(skill, baseActionXp, derivedStats, productivity, sleep.lastNight());
        syncNeedsToRecord();
        return gained;
    }

    public CivilianSleep sleep() {
        return sleep;
    }

    public CivilianFulfilment fulfilment() {
        return fulfilment;
    }

    public CivilianSchedule schedule() {
        return schedule;
    }

    public RouteMemory routeMemory() {
        return routeMemory;
    }

    public CrowdMemory crowdMemory() {
        return crowdMemory;
    }

    @Nullable
    public WorkAssignment workAssignment() {
        return workAssignment;
    }

    /** Current assigned {@link Occupation}, or {@link Occupation#UNEMPLOYED} if unassigned or debug-only. */
    public Occupation occupation() {
        return CivilianOccupations.occupationOf(this);
    }

    public CivilianAiController aiController() {
        if (aiController == null) {
            aiController = new CivilianAiController(this);
        }
        return aiController;
    }

    public void setWorkAssignment(@Nullable WorkAssignment workAssignment) {
        this.workAssignment = workAssignment;
        if (status == Status.CITIZEN && level() instanceof ServerLevel serverLevel) {
            aiController().onWorkAssignmentChanged(serverLevel);
        } else {
            syncNeedsToRecord();
        }
    }

    /** Resets empire-specific schedule, route/crowd memory, and work assignment when joining a new empire. */
    void resetScheduleForNewEmpire() {
        schedule = new CivilianSchedule();
        routeMemory = new RouteMemory();
        crowdMemory = new CrowdMemory();
        workAssignment = null;
    }

    public CivilianScheduleNbt.SavedBundle saveScheduleBundle() {
        return new CivilianScheduleNbt.SavedBundle(
                schedule.save(), routeMemory.save(), crowdMemory.save(), workAssignment);
    }

    private void restoreScheduleBundle(CivilianScheduleNbt.SavedBundle bundle) {
        CivilianScheduleNbt.SavedBundle safe = bundle != null ? bundle : CivilianScheduleNbt.SavedBundle.EMPTY;
        schedule = CivilianSchedule.restore(safe.schedule());
        routeMemory = RouteMemory.restore(safe.routes());
        crowdMemory = CrowdMemory.restore(safe.crowd());
        workAssignment = safe.work();
    }

    /** Stable identity UUID for community/socializing deduplication: master record ID for citizens, entity UUID otherwise. */
    public UUID identityUuid() {
        return civilianId != null ? civilianId : getUUID();
    }

    public void setPreferences(CivilianPreferences preferences) {
        fulfilment.setPreferences(preferences);
        syncNeedsToRecord();
    }

    void presetPreferences(CivilianPreferences preferences) {
        fulfilment.setPreferences(preferences);
        preferencesPreset = true;
    }

    @Nullable
    public BlockPos claimedBed() {
        return claimedBed;
    }

    public void setClaimedBed(@Nullable BlockPos pos) {
        this.claimedBed = pos != null ? pos.immutable() : null;
        syncNeedsToRecord();
    }

    public void setLastNight(float value) {
        sleep.setLastNight(value);
        syncNeedsToRecord();
    }

    public int sleepNeedTicks() {
        return derivedStats.sleepNeedTicks();
    }

    public boolean isRoughSleeping() {
        return entityData.get(ROUGH_SLEEPING);
    }

    /** True while lying down either in a bed or rough on the ground. */
    public boolean isLyingDown() {
        return isSleeping() || isRoughSleeping();
    }

    public boolean isSleepWakeCooldown() {
        return tickCount < wakeCooldownUntil;
    }

    public int wakeCooldownRemaining() {
        return Math.max(0, wakeCooldownUntil - tickCount);
    }

    public void markUnreachableBed(long night, BlockPos bedPos, @Nullable RegionRecord region) {
        unreachableNight = night;
        unreachableBed = bedPos.immutable();
        unreachableRegionId = region != null ? region.id() : null;
        unreachableRegionRevision = region != null ? region.revision() : 0L;
    }

    public void clearUnreachableBed() {
        unreachableNight = CivilianSleep.NO_NIGHT;
        unreachableBed = null;
        unreachableRegionId = null;
        unreachableRegionRevision = 0L;
    }

    /**
     * True if {@code bedPos} timed out as unreachable earlier on {@code night} and its region has not been updated
     * since. Automatically clears the suppression if the region changed or a new night began.
     */
    public boolean isBedUnreachable(long night, @Nullable BlockPos bedPos, @Nullable RegionRecord region) {
        if (unreachableNight != night || unreachableBed == null || !unreachableBed.equals(bedPos)) {
            return false;
        }
        if (unreachableRegionId != null) {
            if (region == null || !unreachableRegionId.equals(region.id())
                    || region.revision() != unreachableRegionRevision) {
                clearUnreachableBed();
                return false;
            }
        } else if (region != null) {
            clearUnreachableBed();
            return false;
        }
        return true;
    }

    @Nullable
    public BlockPos unreachableBedFor(long night) {
        return unreachableNight == night ? unreachableBed : null;
    }

    /**
     * Temporary debug stats dump (shown on sneak + right-click and {@code /empire debug civilian info}).
     */
    public List<Component> debugInfoLines(ServerLevel serverLevel) {
        List<Component> lines = new ArrayList<>();
        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        long dayTime = serverLevel.getDayTime();
        long night = CivilianSleep.nightIndex(dayTime);

        String name = getName().getString();
        String homeStr = home != null ? home.toShortString() : "none";
        UUID empireId = travellerEmpire;
        if (civilianId != null) {
            CivilianRecord record = data.civilian(civilianId);
            empireId = record != null ? record.empire() : null;
        }
        String empireStr = empireId != null ? empireId.toString() : "none";
        String idStr = civilianId != null ? civilianId.toString() : "none";

        lines.add(Component.literal("=== " + name + " (" + status.name() + ") ==="));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Pos: %s (chunk %d, %d) | Home: %s | Look: %s",
                blockPosition().toShortString(), chunkPosition().x, chunkPosition().z, homeStr, appearance().code())));
        lines.add(Component.literal("ID: " + idStr + " | Empire: " + empireStr));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Abilities: BRA %d END %d PRE %d RES %d WIT %d ORG %d TEA %d (mean %.1f)",
                abilities.brawn(), abilities.endurance(), abilities.precision(), abilities.resolve(),
                abilities.wits(), abilities.organization(), abilities.teamwork(), abilities.mean())));
        String quirkList = quirks.isEmpty() ? "none"
                : String.join(", ", quirks.stream().map(CivilianQuirk::displayName).toList());
        lines.add(Component.literal("Quirks: " + quirkList));
        lines.add(Component.literal("Skills: " + skills.formatCompactSummary()));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Stats: Comfort %.1f..%.1fC | Hunger %.2fx basal / %.2fx act | Reach %.1fb | Speed %.2f | StrMult %.2fx",
                derivedStats.comfortMinC(EmpireConfig.fulfilmentComfortMinC()),
                derivedStats.comfortMaxC(EmpireConfig.fulfilmentComfortMaxC()),
                derivedStats.basalExhaustionMultiplier(), derivedStats.activityExhaustionMultiplier(),
                derivedStats.blockReach(), derivedStats.movementSpeed(),
                derivedStats.hungerStrengthFactor(food.foodLevel()))));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "HP: %.1f/%.1f | Food: %d/20, Sat: %.1f, Exh: %.2f | Eaten: %d today / %d yest | Active: %dt",
                getHealth(), getMaxHealth(), food.foodLevel(), food.saturation(), food.exhaustion(),
                food.foodEatenToday(), food.foodEatenYesterday(), food.activeTicks())));

        List<String> pockets = new ArrayList<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                pockets.add(stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }
        }
        StringBuilder invLine = new StringBuilder("Inventory: ")
                .append(pockets.isEmpty() ? "empty" : String.join(", ", pockets));
        if (!getMainHandItem().isEmpty() || !getOffhandItem().isEmpty()) {
            invLine.append(" | Hands: main=").append(formatStack(getMainHandItem()))
                    .append(", off=").append(formatStack(getOffhandItem()));
        }
        lines.add(Component.literal(invLine.toString()));

        if (isUsingItem()) {
            lines.add(Component.literal("Eating: " + BuiltInRegistries.ITEM.getKey(getUseItem().getItem())
                    + " (" + getUseItemRemainingTicks() + "t left)"));
        }
        if (!getActiveEffects().isEmpty()) {
            List<String> effects = new ArrayList<>();
            for (MobEffectInstance inst : getActiveEffects()) {
                String effId = BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()) != null
                        ? BuiltInRegistries.MOB_EFFECT.getKey(inst.getEffect().value()).toString()
                        : inst.getDescriptionId();
                effects.add(effId + " amp=" + inst.getAmplifier() + " (" + inst.getDuration() + "t)");
            }
            lines.add(Component.literal("Effects: " + String.join(", ", effects)));
        }

        int need = sleepNeedTicks();
        String sleepState;
        BlockPos sleepTarget = aiController().sleepTargetPos();
        if (isSleeping()) {
            sleepState = "sleeping in bed";
        } else if (isRoughSleeping()) {
            sleepState = "sleeping rough";
        } else if (aiController().isWalkingToBed() && sleepTarget != null) {
            sleepState = "walking to bed (" + sleepTarget.toShortString() + ")";
        } else if (aiController().isWalkingToRoughSpot() && sleepTarget != null) {
            sleepState = "walking to bonfire (" + sleepTarget.toShortString() + ")";
        } else {
            sleepState = "awake";
        }
        String lastNightStr = Float.isNaN(sleep.lastNight()) ? "none" : String.format(Locale.ROOT, "%.2f", sleep.lastNight());
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Sleep: %s | Need: %dt (%d->%d) | Slept: %dt (credit %.1f) | LastNight: %s",
                sleepState, need, CivilianSleep.bedtime(need), CivilianSleep.wakeTime(need),
                sleep.sleptTicks(), sleep.creditTicks(), lastNightStr)));

        boolean wantsSleep = status == Status.CITIZEN && sleep.wantsToSleep(dayTime, need);
        boolean rested = sleep.isRestedFor(night);
        int wakeCd = wakeCooldownRemaining();
        BlockPos unreach = unreachableBedFor(night);
        StringBuilder sleepFlags = new StringBuilder(String.format(Locale.ROOT,
                "Sleep flags: wantsToSleep=%s, rested=%s, wakeCooldown=%dt",
                wantsSleep, rested, wakeCd));
        if (unreach != null) {
            sleepFlags.append(", unreachableBed=").append(unreach.toShortString());
        }
        lines.add(Component.literal(sleepFlags.toString()));

        StringBuilder bedLine = new StringBuilder("Bed: ")
                .append(claimedBed != null ? claimedBed.toShortString() : "none");
        if (claimedBed != null) {
            RegionRecord region = data.regionAt(claimedBed);
            if (region != null) {
                int cap = RegionRules.homeCapacity(region.effectiveVolume());
                String validity = region.valid() ? "VALID" : "INVALID:" + region.invalidReason().id();
                bedLine.append(" | HomeRegion: ").append(region.id().toString(), 0, 8)
                        .append(" (").append(validity)
                        .append(", ").append(region.inhabitants().size()).append("/").append(cap)
                        .append(", rev ").append(region.revision()).append(")");
            } else {
                bedLine.append(" | HomeRegion: none");
            }
        }
        lines.add(Component.literal(bedLine.toString()));

        StringBuilder jobLine = new StringBuilder("Occupation: ");
        if (workAssignment != null && workAssignment.isOccupation() && workAssignment.workplaceRegionId() != null) {
            RegionRecord workplace = data.region(workAssignment.workplaceRegionId());
            jobLine.append(workAssignment.occupation().id())
                    .append(" | Workplace: ").append(workAssignment.workplaceRegionId().toString(), 0, 8);
            if (workplace != null) {
                String validity = workplace.valid() ? "VALID" : "INVALID:" + workplace.invalidReason().id();
                jobLine.append(" (").append(workplace.type().id()).append(", ").append(validity)
                        .append(", ").append(CivilianOccupations.activeWorkerCount(data, workplace))
                        .append("/").append(RegionRules.workerCapacity(workplace.type())).append(")");
            } else {
                jobLine.append(" (MISSING)");
            }
        } else {
            jobLine.append("unemployed");
        }
        lines.add(Component.literal(jobLine.toString()));

        if (status == Status.TRAVELLER) {
            TravellerWants.Want w = TravellerWants.WANTS.get(want);
            long rem = Math.max(0L, leaveAt - serverLevel.getGameTime());
            lines.add(Component.literal("Traveller: wants " + w.count() + "x " + w.itemId()
                    + ", leaves in " + rem + "t (at " + leaveAt + ")"));
        }
        if (dialoguePlayer != null || talkPartner != null) {
            List<String> social = new ArrayList<>();
            if (dialoguePlayer != null) {
                Player p = serverLevel.getPlayerByUUID(dialoguePlayer);
                social.add("dialogue=" + (p != null ? p.getName().getString() : dialoguePlayer.toString()));
            }
            if (talkPartner != null) {
                social.add("talking=" + talkPartner.getName().getString());
            }
            lines.add(Component.literal("Social: " + String.join(", ", social)));
        }
        lines.addAll(CivilianFulfilmentMeters.formatSummaryLines(serverLevel, this));
        if (status == Status.CITIZEN) {
            lines.add(aiController().formatSummaryLine(serverLevel));
        }
        return lines;
    }

    /** Full breakdown of hidden base traits, quirks, derived abilities, and derived stats for debug inspection. */
    public List<Component> traitsDebugLines() {
        List<Component> lines = new ArrayList<>();
        CivilianDerivedStats s = derivedStats;
        lines.add(Component.literal("=== Traits: " + getName().getString() + " (" + status.name() + ") ==="));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Big Five: O=%+d C=%+d E=%+d A=%+d N=%+d",
                traits.raw(BaseTrait.OPENNESS), traits.raw(BaseTrait.CONSCIENTIOUSNESS),
                traits.raw(BaseTrait.EXTRAVERSION), traits.raw(BaseTrait.AGREEABLENESS),
                traits.raw(BaseTrait.NEUROTICISM))));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Orthogonality: AS=%+d MR=%+d CF=%+d AE=%+d EC=%+d MS=%+d",
                traits.raw(BaseTrait.ALLOMETRIC_SCALE), traits.raw(BaseTrait.METABOLIC_RESERVE),
                traits.raw(BaseTrait.CONTRACTILE_FORCE), traits.raw(BaseTrait.AEROBIC_STAMINA),
                traits.raw(BaseTrait.ELASTIC_COMPLIANCE), traits.raw(BaseTrait.MOTOR_SENSORY))));
        String quirkStr = quirks.isEmpty() ? "none"
                : String.join(", ", quirks.stream().map(CivilianQuirk::displayName).toList());
        lines.add(Component.literal("Quirks: " + quirkStr));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Abilities: BRA %d END %d PRE %d RES %d WIT %d ORG %d TEA %d (mean %.1f)",
                abilities.brawn(), abilities.endurance(), abilities.precision(), abilities.resolve(),
                abilities.wits(), abilities.organization(), abilities.teamwork(), abilities.mean())));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Attributes: HP %.1f | Spd %.3f | Jump %.3f | Fall %.1fb (%.2fx) | KBRes %.2f | Atk %.2f (KB %.2f) | MoveEff %.2f | Water %.2f | O2 %.2f | Follow %.1f",
                s.maxHealth(), s.movementSpeed(), s.jumpStrength(), s.safeFallDistance(), s.fallDamageMultiplier(),
                s.knockbackResistance(), s.attackDamage(), s.attackKnockback(), s.movementEfficiency(),
                s.waterMovementEfficiency(), s.oxygenBonus(), s.followRange())));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Needs: Basal %.2fx | Act %.2fx | HealInt %.2fx | StarveInt %.2fx | Weak<%d (floor %.2fx, now %.2fx)",
                s.basalExhaustionMultiplier(), s.activityExhaustionMultiplier(),
                s.healingIntervalMultiplier(), s.starvationIntervalMultiplier(),
                s.hungerPenaltyThreshold(), s.hungerStrengthFloor(), s.hungerStrengthFactor(food.foodLevel()))));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Sleep/Fulfil: Need %dt | Rough %.2f | WakeCd %dt | Comfort %+.1fC (margin %.2fx) | Comm %.1f | Soc %.1f | Shelter %.1f | Griev +%.2fx/-%.2fx | Speech cd %.2fx talk %.2f",
                s.sleepNeedTicks(), s.roughSleepCreditRate(), s.wakeCooldownTicks(),
                s.comfortTempShiftC(), s.comfortDangerMarginMultiplier(),
                s.communityTargetPeople(), s.socializingTargetInteractions(), s.shelterFloorPerOccupant(),
                s.grievanceBuildMultiplier(), s.grievanceDrainMultiplier(),
                s.speechCooldownMultiplier(), s.talkChance())));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Jobs/Combat: Reach %.2fb/%.2fb | Torque %.2fx RPM %.2fx SustLabor %.2fx | Carry %.2fx | Heavy %.2fx Fine %.2fx ColdSens %.2fx | Fumble %.2fx Aim %.2fx | Learn %.2fx/%.2fx | Route %.2fx Team %.2fx | Brave %.2f Bleed %.2fx",
                s.blockReach(), s.meleeReach(), s.mechanicalTorqueMultiplier(), s.mechanicalSpeedMultiplier(),
                s.sustainedLaborStamina(), s.carryCapacityMultiplier(), s.heavyLaborSpeedMultiplier(),
                s.fineWorkSpeedMultiplier(), s.coldFatiguePrecisionSensitivity(), s.fumbleRateMultiplier(),
                s.rangedAccuracyMultiplier(), s.cognitiveLearningMultiplier(), s.motorLearningMultiplier(),
                s.routingEfficiencyMultiplier(), s.teamworkSynergyMultiplier(),
                s.braveryScore(), s.bleedOutDurationMultiplier())));
        return lines;
    }

    /** Full breakdown of occupational skills, XP, live modifiers, and decay status for {@code /empire debug civilian skills}. */
    public List<Component> skillsDebugLines(ServerLevel serverLevel) {
        List<Component> lines = new ArrayList<>();
        double productivity = CivilianFulfilmentMeters.evaluate(serverLevel, this).productivity();
        double comfort = currentComfortScore();
        float lastNight = sleep.lastNight();
        double sleepMult = CivilianSkills.sleepLearningMultiplier(lastNight);
        double stress = CivilianSkills.coldFatigueStress(comfort, lastNight, derivedStats.hungerStrengthFactor(food.foodLevel()));

        lines.add(Component.literal("=== Skills: " + getName().getString() + " (" + status.name() + ") ==="));
        lines.add(Component.literal(String.format(Locale.ROOT,
                "Learning: motor %.2fx / cog %.2fx | Prod %.2fx | Sleep %.2fx | Fumble %.2fx (stress %.2f, coldSens %.2fx)",
                derivedStats.motorLearningMultiplier(), derivedStats.cognitiveLearningMultiplier(),
                productivity, sleepMult, derivedStats.fumbleRateMultiplier(),
                stress, derivedStats.coldFatiguePrecisionSensitivity())));
        for (CivilianSkill skill : CivilianSkill.values()) {
            double lvl = skills.level(skill);
            double xpVal = skills.xp(skill);
            int idle = skills.inactiveDays(skill);
            boolean used = skills.usedToday(skill);
            double fumble = currentFumbleChance(skill);
            String state = used ? "active today"
                    : (xpVal <= 0.0 ? "untrained"
                    : (idle >= CivilianSkills.DECAY_GRACE_DAYS ? "decaying (" + idle + "d idle)" : idle + "/" + CivilianSkills.DECAY_GRACE_DAYS + "d grace"));
            lines.add(Component.literal(String.format(Locale.ROOT,
                    "  %s: %.2f [%s] (%.1f/%.0f XP) | spd %.2fx, exh %.2fx, dur %.0f%%, yld %.2fx, fmb %.0f%%, lrn %.2fx | %s",
                    skill.displayName(), lvl, CivilianSkills.SkillTier.forLevel(lvl).displayName(),
                    xpVal, CivilianSkills.MAX_XP,
                    CivilianSkills.speedMultiplier(lvl),
                    CivilianSkills.exhaustionMultiplier(lvl),
                    CivilianSkills.toolDurabilityChance(lvl) * 100.0,
                    CivilianSkills.cropYieldMultiplier(lvl),
                    fumble * 100.0,
                    skill.learningMultiplier(derivedStats),
                    state)));
        }
        return lines;
    }

    private static String formatStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        return stack.getCount() + "x " + BuiltInRegistries.ITEM.getKey(stack.getItem());
    }

    public void startRoughSleeping() {
        if (isPassenger()) {
            stopRiding();
        }
        if (isSleeping()) {
            super.stopSleeping();
        }
        endConversation();
        dialoguePlayer = null;
        getNavigation().stop();
        entityData.set(ROUGH_SLEEPING, true);
        setPose(Pose.SLEEPING);
        setDeltaMovement(Vec3.ZERO);
        hasImpulse = true;
    }

    public void stopRoughSleeping() {
        if (isRoughSleeping()) {
            entityData.set(ROUGH_SLEEPING, false);
            if (!isSleeping()) {
                setPose(Pose.STANDING);
            }
        }
    }

    /** Wakes from bed or rough sleep; {@code applyCooldown} delays lying down again by this civilian's wake cooldown. */
    public void wakeUp(boolean applyCooldown) {
        if (isSleeping()) {
            super.stopSleeping();
        }
        stopRoughSleeping();
        if (applyCooldown) {
            wakeCooldownUntil = tickCount + derivedStats.wakeCooldownTicks();
        }
    }

    @Override
    public void startSleeping(BlockPos pos) {
        stopRoughSleeping();
        endConversation();
        dialoguePlayer = null;
        getNavigation().stop();
        super.startSleeping(pos);
    }

    @Override
    public void stopSleeping() {
        super.stopSleeping();
        stopRoughSleeping();
    }

    @Override
    @Nullable
    public Direction getBedOrientation() {
        if (isRoughSleeping() && !isSleeping()) {
            return null;
        }
        return super.getBedOrientation();
    }

    @Override
    protected boolean isImmobile() {
        return super.isImmobile() || isLyingDown();
    }

    @Override
    public void push(Entity entity) {
        if (!isLyingDown()) {
            super.push(entity);
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean wasLying = !level().isClientSide && isLyingDown();
        boolean hurt = super.hurt(source, amount);
        if (wasLying && (hurt || !isLyingDown())) {
            wakeUp(true);
        }
        return hurt;
    }

    public SimpleContainer inventory() {
        return inventory;
    }

    /** Applies a master snapshot without writing it back during join. */
    void restoreNeeds(CivilianRecord record) {
        skills = CivilianSkills.restore(record.skills());
        food = CivilianFood.restore(record.food());
        sleep = CivilianSleep.restore(record.sleep());
        fulfilment = CivilianFulfilment.restore(record.fulfilment());
        restoreScheduleBundle(record.schedule());
        claimedBed = record.bed();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, record.inventory().get(slot).copy());
        }
        if (!Float.isNaN(record.health())) {
            setHealth(Math.clamp(record.health(), 1.0F, getMaxHealth()));
        }
        updateHungerStrengthModifier();
    }

    /** Debug edit: preserve timers and intake history, and immediately write through for citizens. */
    public void setFood(int level, float saturation) {
        CivilianFood.Saved saved = food.save();
        int clamped = Math.clamp(level, 0, 20);
        food = CivilianFood.restore(new CivilianFood.Saved(clamped,
                Math.clamp(Float.isFinite(saturation) ? saturation : 0.0F, 0.0F, (float) clamped),
                saved.exhaustion(), saved.tickTimer(), saved.foodEatenToday(), saved.foodEatenYesterday(), saved.activeTicks()));
        updateHungerStrengthModifier();
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
        CivilianSkills.Saved savedSkills = skills.save();
        CivilianFood.Saved saved = food.save();
        CivilianSleep.Saved savedSleep = sleep.save();
        CivilianFulfilment.Saved savedFulfilment = fulfilment.save();
        CivilianScheduleNbt.SavedBundle savedSchedule = saveScheduleBundle();
        boolean inventoryChanged = false;
        if (record != null) {
            for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
                if (!ItemStack.matches(inventory.getItem(slot), record.inventory().get(slot))) {
                    inventoryChanged = true;
                    break;
                }
            }
        }
        if (record != null && (inventoryChanged || !savedSkills.equals(record.skills())
                || !saved.equals(record.food()) || !savedSleep.equals(record.sleep())
                || !savedFulfilment.equals(record.fulfilment()) || !savedSchedule.equals(record.schedule())
                || !Objects.equals(claimedBed, record.bed()) || Float.compare(getHealth(), record.health()) != 0)) {
            record.setSkills(savedSkills);
            record.setFood(saved);
            record.setSleep(savedSleep);
            record.setFulfilment(savedFulfilment);
            record.setSchedule(savedSchedule);
            record.setBed(claimedBed);
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
            if (status == Status.CITIZEN && level() instanceof ServerLevel serverLevel) {
                EmpireSavedData data = EmpireSavedData.of(serverLevel);
                CivilianRecord record = ownRecord(data);
                EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
                if (empire != null && CivilianFulfilmentMeters.isSafetyHitSource(source, empire)) {
                    empire.safety().recordHit();
                    data.setDirty();
                    aiController().notifyHostileDamage(serverLevel.getGameTime());
                }
            }
        }
    }

    @Override
    public void jumpFromGround() {
        super.jumpFromGround();
        if (!level().isClientSide && status != Status.LEAVING) {
            food.addExhaustion((float) (0.05F * derivedStats.activityExhaustionMultiplier()));
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
                    food.addExhaustion((float) (0.01F * centimetres * 0.01F * derivedStats.activityExhaustionMultiplier()));
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
        wakeUp(false);
        if (level() instanceof ServerLevel serverLevel) {
            aiController().stopAll(serverLevel);
        }
        if (claimedBed != null && level() instanceof ServerLevel serverLevel) {
            CivilianRecord leavingRecord = ownRecord(EmpireSavedData.of(serverLevel));
            CivilianBeds.releaseAndDetach(serverLevel, leavingRecord != null ? leavingRecord.empire() : null,
                    civilianId, claimedBed);
            claimedBed = null;
        }
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
    public CivilianRecord ownRecord(EmpireSavedData data) {
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
        if (!traitsPreset) {
            applyTraits(CivilianTraits.roll(random::nextLong), true, false);
        } else {
            applyTraitAttributes(true);
        }
        if (!skillsPreset) {
            skills = CivilianSkills.rollStarting(random::nextLong, traits);
        }
        if (!preferencesPreset) {
            fulfilment.setPreferences(CivilianPreferences.roll(
                    random::nextLong,
                    CivilianFulfilmentMeters.resolveFavouriteFoodPool(),
                    traits.norm(BaseTrait.OPENNESS)));
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
        tag.put(TAG_SLEEP, CivilianSleepNbt.save(sleep.save()));
        tag.put(TAG_FULFILMENT, CivilianFulfilmentNbt.save(fulfilment.save()));
        tag.put(TAG_SCHEDULE, CivilianScheduleNbt.save(saveScheduleBundle()));
        if (claimedBed != null) {
            tag.put(TAG_BED, NbtUtils.writeBlockPos(claimedBed));
        }
        if (isRoughSleeping()) {
            tag.putBoolean(TAG_ROUGH_SLEEPING, true);
        }
        tag.put("Inventory", ContainerHelper.saveAllItems(new CompoundTag(), inventory.getItems(), level().registryAccess()));
        tag.putInt(TAG_APPEARANCE, entityData.get(APPEARANCE));
        tag.put(TAG_TRAITS, CivilianTraitsNbt.save(traits));
        tag.put(TAG_SKILLS, CivilianSkillsNbt.save(skills.save()));
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
        // Load traits first so derived attributes and fallback Openness-based preferences are ready.
        CivilianTraits loadedTraits = tag.contains(TAG_TRAITS, Tag.TAG_COMPOUND)
                ? CivilianTraitsNbt.load(tag.getCompound(TAG_TRAITS), () -> CivilianTraits.roll(random::nextLong))
                : CivilianTraits.roll(random::nextLong);
        applyTraits(loadedTraits, false, false);
        food = CivilianFood.restore(CivilianFoodNbt.load(tag.getCompound(TAG_FOOD)));
        sleep = CivilianSleep.restore(CivilianSleepNbt.load(tag.getCompound(TAG_SLEEP)));
        fulfilment = CivilianFulfilment.restore(CivilianFulfilmentNbt.load(
                tag.getCompound(TAG_FULFILMENT),
                () -> CivilianPreferences.roll(
                        random::nextLong,
                        CivilianFulfilmentMeters.resolveFavouriteFoodPool(),
                        traits.norm(BaseTrait.OPENNESS))));
        restoreScheduleBundle(CivilianScheduleNbt.load(tag.getCompound(TAG_SCHEDULE)));
        claimedBed = NbtUtils.readBlockPos(tag, TAG_BED).orElse(null);
        if (tag.getBoolean(TAG_ROUGH_SLEEPING) && !isSleeping()) {
            entityData.set(ROUGH_SLEEPING, true);
            setPose(Pose.SLEEPING);
        } else {
            entityData.set(ROUGH_SLEEPING, false);
        }
        inventory.clearContent();
        ContainerHelper.loadAllItems(tag.getCompound("Inventory"), inventory.getItems(), level().registryAccess());
        updateHungerStrengthModifier();
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
        if (tag.contains(TAG_SKILLS, Tag.TAG_COMPOUND)) {
            skills = CivilianSkills.restore(CivilianSkillsNbt.load(
                    tag.getCompound(TAG_SKILLS),
                    () -> CivilianSkills.defaultFor(identityUuid(), traits).save()));
        } else {
            skills = CivilianSkills.defaultFor(identityUuid(), traits);
        }
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
            if (food.countActiveTick()) {
                fulfilment.advanceDay();
                if (status == Status.CITIZEN && skills.advanceDay()) {
                    syncNeedsToRecord();
                }
            }
            food.addExhaustion((float) (EmpireConfig.civilianBasalExhaustionPerTick() * derivedStats.basalExhaustionMultiplier()));
            CivilianFood.TickResult result = food.tick(getHealth(), getMaxHealth(),
                    serverLevel.getGameRules().getBoolean(GameRules.RULE_NATURAL_REGENERATION),
                    serverLevel.getDifficulty() == Difficulty.PEACEFUL,
                    derivedStats.healingIntervalMultiplier(),
                    derivedStats.starvationIntervalMultiplier());
            if (result.healAmount() > 0.0F) heal(result.healAmount());
            if (result.starveDamage() > 0.0F) hurt(damageSources().starve(), result.starveDamage());
            updateHungerStrengthModifier();
            if (tickCount % EAT_CHECK_INTERVAL == 0) {
                // Citizens normally eat during scheduled MEAL slots; outside mealtime only eat at/below emergency floor.
                if (status != Status.CITIZEN || food.foodLevel() <= aiController().emergencyFoodThreshold()) {
                    tryEatFromInventory();
                }
            }
            if (status == Status.CITIZEN) {
                tickSleep(serverLevel);
                fulfilment.countCitizenActiveTick();
                tickFulfilment(serverLevel);
            }
            if (tickCount % RECORD_SYNC_INTERVAL == 0) syncNeedsToRecord();
        }
        tickDialogue();
        if (speechTick) {
            tickSpeech();
        }
    }

    private void tickSleep(ServerLevel serverLevel) {
        long dayTime = serverLevel.getDayTime();
        int need = sleepNeedTicks();
        float roughRate = derivedStats.roughSleepCreditRate();
        float prevLastNight = sleep.lastNight();
        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        CivilianRecord record = ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;

        long prev = sleep.lastDayTime();
        if (!sleepClockReady) {
            sleepClockReady = true;
            if (prev != CivilianSleep.NO_DAY_TIME && dayTime > prev + 1) {
                // D7 / R3: unloaded time counts at claimed-bed / rough rate without teleporting.
                boolean hasBed = isSleeping() || (empire != null && CivilianBeds.isValidClaimedBed(serverLevel, empire, claimedBed));
                sleep.catchUp(prev + 1, dayTime, hasBed, need, roughRate);
                if (isLyingDown() && (!CivilianSleep.isNight(dayTime) || sleep.isRestedFor(CivilianSleep.nightIndex(dayTime)))) {
                    wakeUp(false);
                }
            } else if (prev != CivilianSleep.NO_DAY_TIME && dayTime < prev) {
                sleep.catchUp(prev, dayTime, false, need, roughRate);
                if (isLyingDown() && !CivilianSleep.isNight(dayTime)) {
                    wakeUp(false);
                }
            }
        } else if (prev != CivilianSleep.NO_DAY_TIME && dayTime > prev + 1) {
            boolean prevWasNight = CivilianSleep.isNight(prev);
            long prevNight = CivilianSleep.nightIndex(prev);
            boolean crossedNightEnd = prevWasNight && dayTime >= CivilianSleep.nightEnd(prevNight);
            if (crossedNightEnd && !isLyingDown() && !sleep.isRestedFor(prevNight)) {
                // D6 / R4: forward jump past night end while awake and not rested teleports to bed / rough spot.
                BlockPos bed = empire != null ? CivilianBeds.ensureOrClaimBed(serverLevel, empire, this) : null;
                if (bed != null) {
                    Vec3 dest = CivilianBeds.bedTeleportPos(serverLevel, bed, this);
                    teleportTo(dest.x, dest.y, dest.z);
                    sleep.catchUp(prev + 1, dayTime, true, need, roughRate);
                } else {
                    UUID seed = civilianId != null ? civilianId : getUUID();
                    BlockPos rough = empire != null
                            ? CivilianBeds.roughSpot(serverLevel, empire, seed, blockPosition()) : blockPosition();
                    teleportTo(rough.getX() + 0.5, rough.getY(), rough.getZ() + 0.5);
                    sleep.catchUp(prev + 1, dayTime, false, need, roughRate);
                }
            } else {
                boolean hasBed = isSleeping()
                        || (!isRoughSleeping() && empire != null && CivilianBeds.isValidClaimedBed(serverLevel, empire, claimedBed));
                sleep.catchUp(prev + 1, dayTime, hasBed, need, roughRate);
            }
            if (isLyingDown() && (!CivilianSleep.isNight(dayTime) || sleep.isRestedFor(CivilianSleep.nightIndex(dayTime)))) {
                wakeUp(false);
            }
        } else if (prev != CivilianSleep.NO_DAY_TIME && dayTime < prev) {
            sleep.catchUp(prev, dayTime, false, need, roughRate);
            if (isLyingDown() && !CivilianSleep.isNight(dayTime)) {
                wakeUp(false);
            }
        }

        if (isRoughSleeping() && (isInWater() || isInLava())) {
            wakeUp(true);
        }
        sleep.tick(dayTime, isLyingDown(), isSleeping(), need, roughRate);
        if (isLyingDown() && (!CivilianSleep.isNight(dayTime) || sleep.isRestedFor(CivilianSleep.nightIndex(dayTime)))) {
            wakeUp(false);
        }
        if (Float.compare(prevLastNight, sleep.lastNight()) != 0) {
            syncNeedsToRecord();
        }
    }

    private void tickFulfilment(ServerLevel serverLevel) {
        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        CivilianRecord record = ownRecord(data);
        EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
        if (empire != null && empire.safety().countActiveTickIfNew(serverLevel.getGameTime())) {
            data.setDirty();
        }
        if ((tickCount + getId()) % CivilianFulfilment.COMMUNITY_SCAN_TICKS == 0) {
            if (CivilianFulfilmentMeters.scanCommunity(serverLevel, this, empire)) {
                aiController().recordNearbyPersonSighting(serverLevel, empire);
            }
        }
        if (!fulfilment.hasComfortSamples()) {
            CivilianFulfilmentMeters.sampleComfort(serverLevel, this);
        }
        if (fulfilment.isSlotDue()) {
            CivilianFulfilmentMeters.stepSlot(serverLevel, this, fulfilment.consumeSlotTicks());
        }
    }

    /** True if the civilian carries at least one pocket food item whose nutrition fits missing food without waste. */
    public boolean hasFittingPocketFood() {
        return findFittingPocketFoodSlot(false) >= 0;
    }

    /**
     * Attempts to start eating one fitting food item from pockets through vanilla item use. When {@code preferLiked}
     * is true, checks the civilian's favourite foods first before falling back to any fitting food. Raw farm produce
     * is eaten only as an emergency fallback when no proper food fits and hunger is at or below the emergency snack
     * threshold (Bright, 2026-11-26).
     */
    public boolean startEatingFromInventory(boolean preferLiked) {
        if (isLyingDown() || isUsingItem()) {
            return false;
        }
        if (!getMainHandItem().isEmpty()) {
            EquipAction.stowMainHand(this);
            if (!getMainHandItem().isEmpty()) {
                return false;
            }
        }
        int slot = findFittingPocketFoodSlot(preferLiked);
        if (slot < 0) {
            return false;
        }
        setItemInHand(InteractionHand.MAIN_HAND, inventory.removeItem(slot, 1));
        inventoryMeal = true;
        startUsingItem(InteractionHand.MAIN_HAND);
        // NeoForge can cancel the start-use event. The food still belongs in the pockets.
        if (!isUsingItem()) {
            returnInventoryMeal();
            return false;
        }
        return true;
    }

    private int findFittingPocketFoodSlot(boolean preferLiked) {
        int missing = 20 - food.foodLevel();
        if (missing <= 0) {
            return -1;
        }
        boolean allowRawProduce = food.foodLevel() <= aiController().emergencyFoodThreshold();
        int firstProper = -1;
        int firstRaw = -1;
        int likedRaw = -1;
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            FoodProperties properties = stack.getFoodProperties(this);
            if (stack.isEmpty() || properties == null || properties.nutrition() > missing) {
                continue;
            }
            boolean rawProduce = FarmCrops.isRawFarmProduceFood(stack);
            if (!rawProduce) {
                if (firstProper < 0) {
                    firstProper = slot;
                }
                if (!preferLiked) {
                    return slot;
                }
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null && fulfilment.preferences().likesFood(key.toString())) {
                    return slot;
                }
            } else if (allowRawProduce) {
                if (firstRaw < 0) {
                    firstRaw = slot;
                }
                if (preferLiked && likedRaw < 0) {
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (key != null && fulfilment.preferences().likesFood(key.toString())) {
                        likedRaw = slot;
                    }
                }
            }
        }
        if (firstProper >= 0) {
            return firstProper;
        }
        return likedRaw >= 0 ? likedRaw : firstRaw;
    }

    private void tryEatFromInventory() {
        startEatingFromInventory(false);
    }

    // LivingEntity detects equipment before aiStep starts eating, so the use flag reaches the client before the hand item.
    // Wait for that item instead of letting vanilla cancel client use with an empty stack (also when tracking mid-meal).
    @Override
    protected void updatingUsingItem() {
        if (level().isClientSide && isUsingItem() && useItem.isEmpty()) {
            ItemStack held = getItemInHand(getUsedItemHand());
            if (held.isEmpty()) {
                return;
            }
            useItem = held;
            useItemRemaining = held.getUseDuration(this);
        }
        super.updatingUsingItem();
    }

    @Override
    public ItemStack eat(Level level, ItemStack stack, FoodProperties properties) {
        if (!level.isClientSide) {
            food.eat(properties.nutrition(), properties.saturation());
            ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (itemKey != null && fulfilment.preferences().likesFood(itemKey.toString())) {
                fulfilment.recordLikedMeal();
            }
        }
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

    /**
     * Stops any active item use without moving the main-hand stack back into pockets, so direct debug inventory
     * edits to the main hand never duplicate or overwrite the edited stack.
     */
    public void cancelActiveUseForDirectHandEdit() {
        if (inventoryMeal) {
            inventoryMeal = false;
        }
        if (isUsingItem()) {
            stopUsingItem();
        }
    }

    private void returnInventoryMeal() {
        inventoryMeal = false;
        ItemStack remainder = getMainHandItem();
        setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        storeOrDrop(remainder);
        syncNeedsToRecord();
    }

    public void storeOrDrop(ItemStack stack) {
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
            if (player.isSecondaryUseActive() && status != Status.LEAVING && !LedgerSelection.isHoldingLedger(player)) {
                if (level() instanceof ServerLevel serverLevel) {
                    for (Component line : debugInfoLines(serverLevel)) {
                        player.sendSystemMessage(line);
                    }
                }
                return InteractionResult.SUCCESS;
            }
            if (status == Status.LEAVING) {
                return InteractionResult.CONSUME;
            }
            if (isLyingDown()) {
                wakeUp(true);
                say(CivilianLines.Category.WOKEN);
                return InteractionResult.SUCCESS;
            }
            ItemStack held = player.getMainHandItem();
            if (status == Status.CITIZEN && held.getFoodProperties(this) != null) {
                ItemStack remainder = inventory.addItem(held.copy());
                int moved = held.getCount() - remainder.getCount();
                if (!player.getAbilities().instabuild) held.shrink(moved);
                fulfilment.recordSocialInteraction(player.getUUID(), food.activeTicks());
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
            if (!travellerSpoke) {
                if (fulfilment.recordSocialInteraction(player.getUUID(), food.activeTicks())) {
                    syncNeedsToRecord();
                }
                if (tickCount - lastLineTick >= CLICK_GAP) {
                    say(started ? CivilianLines.Category.GREETING : CivilianLines.Category.REPLY);
                }
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
        if (isLyingDown()) {
            return;
        }
        if (nextLineTick < 0) {
            // The first wait is shorter and random, so a crowd does not speak in sync.
            int scaledMin = Math.max(SPEECH_INTERVAL, (int) Math.round(COOLDOWN_MIN * derivedStats.speechCooldownMultiplier()));
            nextLineTick = tickCount + random.nextInt(scaledMin + 1);
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
        if (random.nextDouble() < derivedStats.talkChance() && startConversation()) {
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
        double mult = derivedStats.speechCooldownMultiplier();
        int min = Math.max(SPEECH_INTERVAL, (int) Math.round(COOLDOWN_MIN * mult));
        int max = Math.max(min, (int) Math.round(COOLDOWN_MAX * mult));
        nextLineTick = tickCount + min + random.nextInt(max - min + 1);
    }

    /** Whether this civilian can be drawn into a conversation. */
    private boolean available() {
        return isAlive() && !isLyingDown() && talkPartner == null && dialoguePlayer == null;
    }

    /** The civilian this entity is currently conversing with, or null. */
    @Nullable
    public CivilianEntity talkPartner() {
        return talkPartner;
    }

    /** Attempts to start a scripted conversation with {@code partner} if both sides are available and within range. */
    public boolean tryStartConversationWith(CivilianEntity partner) {
        if (partner == null || partner == this || !available() || !partner.available()) {
            return false;
        }
        if (distanceToSqr(partner) > (double) TALK_RADIUS * TALK_RADIUS || !hasLineOfSight(partner)) {
            return false;
        }
        Conversation c = new Conversation(partner, random.nextInt(CivilianLines.TALK_LENGTHS.length));
        conversation = c;
        talkPartner = partner;
        partner.talkPartner = this;
        partner.conversation = null;
        c.nextTick = tickCount;
        fulfilment.recordSocialInteraction(partner.identityUuid(), food.activeTicks());
        partner.fulfilment.recordSocialInteraction(identityUuid(), partner.food.activeTicks());
        tickConversation();
        return true;
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
        return partner != null && tryStartConversationWith(partner);
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
