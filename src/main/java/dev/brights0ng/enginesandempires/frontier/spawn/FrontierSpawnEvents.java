package dev.brights0ng.enginesandempires.frontier.spawn;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.FrontierTags;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.deep.DeepSpawns;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Spawning by tier:
 * <ul>
 *   <li>Settled and Civilized land turn natural spawns away ({@link SpawnRules}).</li>
 *   <li>Frontier land gets an extra spawner close around players ({@link FrontierSpawner}), and torchlight does not keep
 *       monsters away there ({@link FrontierDarkness}, through {@code MonsterDarknessMixin}).</li>
 *   <li>Neutral mobs in Frontier land attack players on sight (Overworld only; the {@code never_provoked} tag is left out).
 *       Mobs in the {@code provoked_from_below} tag that came up from the Deep attack players anywhere, in any dimension.</li>
 *   <li>Players in Civilized land have Regeneration I.</li>
 * </ul>
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class FrontierSpawnEvents {

    /** Priority of the "attack players in Frontier land" goal: after a mob's own reasons to fight (hurt by, owner...). */
    private static final int PROVOKED_GOAL_PRIORITY = 4;
    private static final int REGENERATION_INTERVAL = 50;

    /** Natural spawns only: spawners, eggs, structures, raids and patrols are left alone. */
    @SubscribeEvent
    static void onPositionCheck(MobSpawnEvent.PositionCheck event) {
        if (event.getSpawnType() != MobSpawnType.NATURAL || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        FrontierLevel frontier = FrontierLevels.of(level);
        if (frontier == null) {
            return;
        }
        Mob mob = event.getEntity();
        BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
        if (denies(frontier, mob.getType(), pos, level.random.nextDouble())) {
            event.setResult(MobSpawnEvent.PositionCheck.Result.FAIL);
        }
    }

    /** Whether a natural spawn of {@code type} at {@code pos} is turned away by its tier. */
    public static boolean denies(FrontierLevel frontier, EntityType<?> type, BlockPos pos, double roll) {
        Tier tier = frontier.tierAt(pos);
        if (tier == Tier.FRONTIER || tier == Tier.UNINHABITED) {
            return false;
        }
        return SpawnRules.denies(tier, type.getCategory() == MobCategory.MONSTER, type == EntityType.CREEPER,
                type.is(FrontierTags.DAYTIME_HOSTILE), frontier.level().canSeeSky(pos), roll,
                FrontierConfig.spawn().settledSurfaceMonsters());
    }

    /**
     * Neutral mobs joining the Overworld learn to attack players while they stand in Frontier land; mobs brought up from the
     * Deep that are in the {@code provoked_from_below} tag learn to attack them anywhere.
     */
    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        boolean frontierNeutral = mob instanceof NeutralMob && !mob.getType().is(FrontierTags.NEVER_PROVOKED)
                && FrontierLevels.of(event.getLevel()) != null;
        if (frontierNeutral || isFromBelow(mob)) {
            mob.targetSelector.addGoal(PROVOKED_GOAL_PRIORITY, new ProvokedTargetGoal(mob));
        }
    }

    /** A neutral mob's urge to attack players on sight while it stands in Frontier land. */
    public static final class ProvokedTargetGoal extends NearestAttackableTargetGoal<Player> {
        public ProvokedTargetGoal(Mob mob) {
            super(mob, Player.class, 10, true, false, target -> isProvoked(mob));
        }
    }

    /** Whether a neutral mob is out in Frontier land right now (or came up from the Deep provoked), and nobody's pet. */
    public static boolean isProvoked(Mob mob) {
        if (mob instanceof OwnableEntity owned && owned.getOwnerUUID() != null) {
            return false;
        }
        if (isFromBelow(mob)) {
            return true;
        }
        FrontierLevel frontier = FrontierLevels.of(mob.level());
        return frontier != null && frontier.tierAt(mob.blockPosition()) == Tier.FRONTIER;
    }

    /** Whether {@code mob} came up from the Deep and is one that comes up angry ({@code provoked_from_below}). */
    public static boolean isFromBelow(Mob mob) {
        return mob.getTags().contains(DeepSpawns.TAG) && mob.getType().is(FrontierTags.PROVOKED_FROM_BELOW);
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        FrontierLevel frontier = FrontierLevels.of(event.getLevel());
        if (frontier == null) {
            return;
        }
        FrontierConfig.SpawnParams spawn = FrontierConfig.spawn();
        FrontierSpawner.tick(frontier, spawn);
        ServerLevel level = frontier.level();
        if (spawn.civilizedRegeneration() && level.getGameTime() % REGENERATION_INTERVAL == 0) {
            for (ServerPlayer player : level.players()) {
                if (!player.isSpectator() && frontier.tierAt(player.blockPosition()) == Tier.CIVILIZED) {
                    player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGENERATION_INTERVAL * 2, 0, true, false, true));
                }
            }
        }
    }

    private FrontierSpawnEvents() {
    }
}
