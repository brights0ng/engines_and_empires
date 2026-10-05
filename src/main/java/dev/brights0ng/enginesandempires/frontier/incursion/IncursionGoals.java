package dev.brights0ng.enginesandempires.frontier.incursion;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.EnumSet;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import dev.brights0ng.enginesandempires.frontier.deep.DeepSpawns;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenAi;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * How incursion mobs behave, on top of their own AI: they hunt players first, then villagers, pillagers and colonists, then
 * anything else alive that is not from the Deep; with nothing to fight, they march on their incursion's target; and when it
 * is over they leave.
 *
 * <p>Wardens run on a brain, not goals, so they are steered instead, through their own anger and disturbance memories.
 */
public final class IncursionGoals {

    private static final int HUNT_PRIORITY = 1;
    private static final int INHABITANT_PRIORITY = 2;
    private static final int ANYTHING_PRIORITY = 3;
    private static final int MARCH_PRIORITY = 4;
    /** Other mobs are only fought when they are this close (or strike first), so a march is not a hunt. */
    private static final double BYSTANDER_REACH = 3.0;
    /** How far incursion mobs notice players. */
    private static final double FOLLOW_RANGE = 48.0;
    private static final ResourceLocation MARCH_SPEED = ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "incursion_march");
    private static Field wormIdleTime;
    private static boolean wormLookedUp;
    /** How far a retreating mob must be from every player before it is gone. */
    public static final int RETREAT_GONE_DISTANCE = 32;
    /** How long a Warden takes to dig back down before it is gone. */
    public static final int WARDEN_DIG_TICKS = 100;

    /** Gives a mob joining the level its incursion behaviour. */
    public static void equip(Mob mob) {
        if (mob instanceof Warden) {
            return;
        }
        if (isRetreating(mob)) {
            mob.goalSelector.addGoal(0, new RetreatGoal(mob));
            return;
        }
        AttributeInstance follow = mob.getAttribute(Attributes.FOLLOW_RANGE);
        if (follow != null && follow.getBaseValue() < FOLLOW_RANGE) {
            follow.setBaseValue(FOLLOW_RANGE);
        }
        AttributeInstance speed = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null && !speed.hasModifier(MARCH_SPEED)) {
            speed.addTransientModifier(new AttributeModifier(MARCH_SPEED, 0.25, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        mob.targetSelector.addGoal(HUNT_PRIORITY, new NearestAttackableTargetGoal<>(mob, Player.class, 10, false, false,
                target -> !isRetreating(mob)));
        mob.targetSelector.addGoal(INHABITANT_PRIORITY, new NearestAttackableTargetGoal<>(mob, LivingEntity.class, 10, false,
                false, target -> !isRetreating(mob) && !(target instanceof Player) && target.getType().is(FrontierTags.INHABITANTS)));
        mob.targetSelector.addGoal(ANYTHING_PRIORITY, new NearestAttackableTargetGoal<>(mob, Mob.class, 10, true, false,
                target -> !isRetreating(mob) && !target.getTags().contains(DeepSpawns.TAG)
                        && target.distanceToSqr(mob) <= BYSTANDER_REACH * BYSTANDER_REACH));
        if (mob instanceof PathfinderMob pathfinder) {
            mob.targetSelector.addGoal(0, new HurtByTargetGoal(pathfinder).setAlertOthers());
        }
        mob.goalSelector.addGoal(MARCH_PRIORITY, new MarchGoal(mob));
        mob.goalSelector.addGoal(0, new RetreatGoal(mob));
    }

    /**
     * Keeps a shriek worm up out of its hole: left alone it sinks back down after a minute without a target, and is gone.
     * Its idle count is reset (by reflection: it is Deeper and Darker's private field) while its incursion runs.
     */
    static void keepUp(Mob mob) {
        if (!wormLookedUp) {
            wormLookedUp = true;
            try {
                wormIdleTime = mob.getClass().getDeclaredField("idleTime");
                wormIdleTime.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                EnginesAndEmpiresMod.LOGGER.warn("Can't keep shriek worms up in incursions: {}", e.toString());
            }
        }
        if (wormIdleTime == null || !wormIdleTime.getDeclaringClass().isInstance(mob)) {
            return;
        }
        try {
            wormIdleTime.setInt(mob, 0);
        } catch (ReflectiveOperationException | RuntimeException e) {
            wormIdleTime = null;
        }
    }

    public static boolean isRetreating(Mob mob) {
        return mob.getTags().contains(Incursion.RETREAT_TAG);
    }

    /** Sends a mob away: a Warden digs back down, anything else runs off until no player is near, then is gone. */
    public static void retreat(ServerLevel level, Mob mob) {
        if (isRetreating(mob)) {
            return;
        }
        mob.addTag(Incursion.RETREAT_TAG);
        mob.getPersistentData().putLong(Incursion.RETREAT_AT_KEY, level.getGameTime());
        mob.setTarget(null);
        mob.setGlowingTag(false);
        if (mob instanceof Warden warden) {
            warden.clearAnger(warden);
            warden.setPose(Pose.DIGGING);
            level.playSound(null, warden.blockPosition(), SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 5.0F, 1.0F);
        } else if (mob.getType().is(FrontierTags.STATIONARY)) {
            mob.setPose(Pose.DIGGING); // a worm sinks back into its hole
        }
    }

    /** Runs every second for a retreating mob: whether it is time for it to be gone. */
    public static boolean shouldVanish(ServerLevel level, Mob mob) {
        if (mob instanceof Warden || mob.getType().is(FrontierTags.STATIONARY)) {
            long since = level.getGameTime() - mob.getPersistentData().getLong(Incursion.RETREAT_AT_KEY);
            return since >= WARDEN_DIG_TICKS;
        }
        for (Player player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(mob) < RETREAT_GONE_DISTANCE * RETREAT_GONE_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Points a Warden at something to fight (a player, then an inhabitant, then anything alive nearby), or failing that
     * at its incursion's target, which it goes to investigate the way it would a vibration.
     */
    static void steerWarden(ServerLevel level, Incursion incursion, Mob mob) {
        if (!(mob instanceof Warden warden) || isRetreating(mob) || warden.hasPose(Pose.EMERGING)) {
            return;
        }
        if (warden.getTarget() != null && warden.getTarget().isAlive()) {
            return;
        }
        LivingEntity prey = level.getNearestPlayer(warden, 24);
        if (prey instanceof Player player && (player.isSpectator() || player.isCreative())) {
            prey = null;
        }
        if (prey == null) {
            prey = level.getNearestEntity(level.getEntitiesOfClass(LivingEntity.class, warden.getBoundingBox().inflate(16),
                            e -> e.getType().is(FrontierTags.INHABITANTS) && !(e instanceof Player)),
                    net.minecraft.world.entity.ai.targeting.TargetingConditions.forCombat(), warden,
                    warden.getX(), warden.getY(), warden.getZ());
        }
        if (prey != null) {
            warden.increaseAngerAt(prey, 100, false);
        } else {
            WardenAi.setDisturbanceLocation(warden, incursion.destination(level, warden));
        }
    }

    /** With nothing to fight, march on the incursion's target, a leg at a time (as raiders march on a village). */
    static final class MarchGoal extends Goal {

        private static Method slimeSetDirection;
        private static boolean slimeLookedUp;

        private final Mob mob;
        private BlockPos destination;
        private int recalc;

        MarchGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        private Incursion incursion() {
            return mob.level() instanceof ServerLevel level ? Incursions.of(level).forMob(mob) : null;
        }

        @Override
        public boolean canUse() {
            if (isRetreating(mob) || mob.getTarget() != null || mob.isPassenger()) {
                return false;
            }
            Incursion incursion = incursion();
            if (incursion == null || incursion.isOver()) {
                return false;
            }
            destination = incursion.destination((ServerLevel) mob.level(), mob);
            return mob.distanceToSqr(Vec3.atBottomCenterOf(destination)) > incursion.arrivedWithin() * incursion.arrivedWithin();
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public void start() {
            recalc = 0;
        }

        @Override
        public void stop() {
            mob.getNavigation().stop();
        }

        @Override
        public void tick() {
            if (destination == null || --recalc > 0) {
                return;
            }
            recalc = 20;
            Vec3 target = Vec3.atBottomCenterOf(destination);
            if (mob instanceof Slime slime) {
                steerSlime(slime, target);
                return;
            }
            if (mob.distanceToSqr(target) > 16 * 16 && mob instanceof PathfinderMob pathfinder) {
                Vec3 leg = DefaultRandomPos.getPosTowards(pathfinder, 16, 7, target, Math.PI / 2);
                if (leg != null) {
                    target = leg;
                }
            }
            mob.getNavigation().moveTo(target.x, target.y, target.z, 1.0);
        }

        /** Slimes (and Deeper and Darker's sludge) steer by hopping in a direction, not by walking a path. */
        private static void steerSlime(Slime slime, Vec3 target) {
            if (!slimeLookedUp) {
                slimeLookedUp = true;
                try {
                    Class<?> control = Class.forName("net.minecraft.world.entity.monster.Slime$SlimeMoveControl");
                    slimeSetDirection = control.getDeclaredMethod("setDirection", float.class, boolean.class);
                    slimeSetDirection.setAccessible(true);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    EnginesAndEmpiresMod.LOGGER.warn("Can't steer slimes in incursions: {}", e.toString());
                }
            }
            if (slimeSetDirection == null) {
                return;
            }
            float yaw = (float) (Mth.atan2(target.z - slime.getZ(), target.x - slime.getX()) * Mth.RAD_TO_DEG) - 90.0F;
            try {
                slimeSetDirection.invoke(slime.getMoveControl(), yaw, false);
            } catch (ReflectiveOperationException | RuntimeException e) {
                slimeSetDirection = null;
            }
        }
    }

    /** Runs from the nearest player. */
    static final class RetreatGoal extends Goal {

        private final Mob mob;
        private int recalc;

        RetreatGoal(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            return isRetreating(mob);
        }

        @Override
        public void tick() {
            mob.setTarget(null);
            if (--recalc > 0) {
                return;
            }
            recalc = 20;
            Player nearest = mob.level().getNearestPlayer(mob, 64);
            if (nearest == null) {
                return;
            }
            if (mob instanceof PathfinderMob pathfinder) {
                Vec3 away = DefaultRandomPos.getPosAway(pathfinder, 16, 7, nearest.position());
                if (away != null) {
                    mob.getNavigation().moveTo(away.x, away.y, away.z, 1.2);
                }
            } else if (mob instanceof Slime slime) {
                Vec3 from = nearest.position();
                MarchGoal.steerSlime(slime, slime.position().scale(2).subtract(from));
            }
        }
    }

    private IncursionGoals() {
    }
}
