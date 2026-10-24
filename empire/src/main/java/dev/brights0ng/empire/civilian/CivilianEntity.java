// Written by Claude.
package dev.brights0ng.empire.civilian;

import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianAppearance;
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
import net.minecraft.world.damagesource.DamageSource;
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

/**
 * A civilian of an empire: a slim-player-shaped townsperson with a composited skin ({@link CivilianAppearance}), a name
 * from {@link CivilianNames}, and a home it wanders around. Never despawns.
 */
public class CivilianEntity extends PathfinderMob {

    /** How far from home a civilian wanders (provisional). */
    public static final int HOME_RADIUS = 16;

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
