// Written by Claude.
package dev.brights0ng.empire.civilian;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.civilian.CivilianNetwork.CivilianSpeechPayload;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.CivilianLines;
import dev.brights0ng.empire.rules.CivilianNames;
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
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * A civilian of an empire: a slim-player-shaped townsperson with a composited skin ({@link CivilianAppearance}), a name
 * from {@link CivilianNames}, and a home it wanders around. Never despawns.
 *
 * <p>Speech runs on the server once a second and only near players: a line now and then ({@link CivilianLines}), a
 * greeting for a player who comes close, and short scripted conversations with a nearby civilian. Each line goes to the
 * tracking clients ({@link CivilianNetwork}), which draw it as a bubble.
 *
 * <p>Right-clicking (main hand, any item, never used up) starts a dialogue: the civilian stops, faces the player and
 * greets them, and each later click gets a reply, until the player leaves, goes idle or is gone.
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

    private static final double RAIN_CHANCE = 0.5;
    private static final double NIGHT_CHANCE = 0.4;
    private static final double GREET_CHANCE = 0.5;
    /** Partners further apart than this end their conversation. */
    private static final double TALK_BREAK_DISTANCE = 6.0;
    private static final int SPEECH_INTERVAL = 20;
    /** Clicks this soon after the civilian's last line get no new line. */
    private static final int CLICK_GAP = 10;

    private static final EntityDataAccessor<Integer> APPEARANCE =
            SynchedEntityData.defineId(CivilianEntity.class, EntityDataSerializers.INT);

    private static final String TAG_APPEARANCE = "Appearance";
    private static final String TAG_HOME = "Home";
    private static final String TAG_EMPIRE = "Empire";

    @Nullable
    private BlockPos home;
    @Nullable
    private UUID empire;
    /** Set when the look was chosen before spawning (debug commands), so finalizeSpawn keeps it. */
    private boolean appearancePreset;

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
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.5));
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

    public void setAppearance(CivilianAppearance appearance) {
        entityData.set(APPEARANCE, appearance.pack());
    }

    /** Fixes the look before {@link #finalizeSpawn} so it is not rolled. */
    void presetAppearance(CivilianAppearance appearance) {
        setAppearance(appearance);
        appearancePreset = true;
    }

    @Nullable
    public BlockPos home() {
        return home;
    }

    @Nullable
    public UUID empire() {
        return empire;
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
        setCustomName(Component.literal(CivilianNames.pick(random::nextLong)));
        setCustomNameVisible(true);

        ServerLevel serverLevel = level.getLevel();
        BlockPos pos = blockPosition();
        home = pos;
        empire = null;
        // Claims are Overworld chunks; elsewhere a civilian belongs to no empire.
        if (serverLevel.dimension() == Level.OVERWORLD) {
            EmpireRecord record = EmpireSavedData.of(serverLevel).getEmpireByChunk(ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
            if (record != null) {
                empire = record.id();
                if (record.mainBonfire() != null) {
                    home = record.mainBonfire();
                }
            }
        }
        restrictTo(home, HOME_RADIUS);
        return data;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putInt(TAG_APPEARANCE, entityData.get(APPEARANCE));
        if (home != null) {
            tag.put(TAG_HOME, NbtUtils.writeBlockPos(home));
        }
        if (empire != null) {
            tag.putUUID(TAG_EMPIRE, empire);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(TAG_APPEARANCE)) {
            try {
                setAppearance(CivilianAppearance.unpack(tag.getInt(TAG_APPEARANCE)));
            } catch (IllegalArgumentException ignored) {
                // A corrupt look keeps the default rather than failing the whole entity.
            }
        }
        home = NbtUtils.readBlockPos(tag, TAG_HOME).orElse(null);
        empire = tag.hasUUID(TAG_EMPIRE) ? tag.getUUID(TAG_EMPIRE) : null;
        // Vanilla does not save the restriction.
        if (home != null) {
            restrictTo(home, HOME_RADIUS);
        }
    }

    @Override
    public void aiStep() {
        super.aiStep();
        if (!level().isClientSide) {
            tickDialogue();
            if ((tickCount + getId()) % SPEECH_INTERVAL == 0) {
                tickSpeech();
            }
        }
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

    /** A right-click: starts or continues the dialogue with {@code player}. The held item is never used. */
    InteractionResult talkTo(Player player) {
        if (!level().isClientSide) {
            endConversation();
            boolean started = !player.getUUID().equals(dialoguePlayer);
            dialoguePlayer = player.getUUID();
            lastClickTick = tickCount;
            awayTicks = 0;
            if (tickCount - lastLineTick >= CLICK_GAP) {
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
        if (level().isClientSide) {
            return;
        }
        lastLineTick = tickCount;
        PacketDistributor.sendToPlayersTrackingEntity(this, new CivilianSpeechPayload(getId(), key));
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
