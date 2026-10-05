package dev.brights0ng.enginesandempires.frontier.deep;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Bringing mobs up from the Deep: picking from a {@link DeepRoster}, finding ground for them, and putting them there with a
 * rumble and a spray of dirt, as if they had dug their way up. They spawn as an EVENT, so Frontier's per-tier spawn rules
 * (for natural spawns) do not turn them away, and are tagged {@link #TAG}.
 */
public final class DeepSpawns {

    public static final String TAG = "engines_and_empires.deep";

    private static final int TRIES = 12;
    /** How far down from the surface (or the chosen height) ground is looked for: enough to get under a roof or a tree. */
    private static final int FLOOR_SEARCH = 12;

    /**
     * Brings up one pack from roster {@code roster} somewhere between {@code minDistance} and {@code maxDistance} blocks of
     * {@code centre}, on the surface if {@code surface}, otherwise around {@code centre}'s height (caves). Returns how many
     * came up.
     */
    public static int bringUp(ServerLevel level, ResourceLocation roster, BlockPos centre, int minDistance, int maxDistance,
                              boolean surface, RandomSource random) {
        Optional<DeepRoster.Entry> picked = DeepRoster.get(level, roster).flatMap(r -> r.pick(random));
        if (picked.isEmpty()) {
            return 0;
        }
        DeepRoster.Entry entry = picked.get();
        EntityType<?> type = entry.type().orElseThrow();
        int count = entry.min() + random.nextInt(Math.max(1, entry.max() - entry.min() + 1));
        return pack(level, type, count, entry.health(), centre, minDistance, maxDistance, surface, random, mob -> { }).size();
    }

    /**
     * Brings up {@code count} of {@code type} together, somewhere between {@code minDistance} and {@code maxDistance} blocks
     * of {@code centre}, and returns them (fewer, or none, if there was no room). {@code prepare} is given each mob before it
     * joins the level (to tag it, so what happens as it joins can see the tag).
     */
    public static List<Mob> pack(ServerLevel level, EntityType<?> type, int count, double health, BlockPos centre,
                                 int minDistance, int maxDistance, boolean surface, RandomSource random,
                                 java.util.function.Consumer<Mob> prepare) {
        List<Mob> placed = new ArrayList<>();
        BlockPos spot = findSpot(level, type, centre, minDistance, maxDistance, surface, random);
        if (spot == null) {
            return placed;
        }
        for (int i = 0; i < count; i++) {
            BlockPos at = i == 0 ? spot : findSpot(level, type, spot, 0, 3, surface, random);
            Mob mob = at == null ? null : spawn(level, type, at, health, prepare);
            if (mob != null) {
                placed.add(mob);
            }
        }
        if (!placed.isEmpty()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, level.getBlockState(spot.below())),
                    spot.getX() + 0.5, spot.getY() + 0.2, spot.getZ() + 0.5, 30, 0.6, 0.1, 0.6, 0.1);
            level.playSound(null, spot, SoundEvents.WARDEN_DIG, SoundSource.HOSTILE, 1.0F, 1.3F);
        }
        return placed;
    }

    public static BlockPos findSpot(ServerLevel level, EntityType<?> type, BlockPos centre, int minDistance, int maxDistance,
                                     boolean surface, RandomSource random) {
        for (int t = 0; t < TRIES; t++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minDistance + random.nextDouble() * Math.max(0, maxDistance - minDistance);
            int x = Mth.floor(centre.getX() + 0.5 + Math.cos(angle) * distance);
            int z = Mth.floor(centre.getZ() + 0.5 + Math.sin(angle) * distance);
            if (!level.isPositionEntityTicking(new BlockPos(x, centre.getY(), z))) {
                continue;
            }
            int startY = surface ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z)
                    : centre.getY() + random.nextInt(7) - 3;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, startY, z);
            for (int dy = 0; dy < FLOOR_SEARCH && pos.getY() > level.getMinBuildHeight(); dy++, pos.move(0, -1, 0)) {
                if (SpawnPlacements.isSpawnPositionOk(type, level, pos)
                        && level.noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    private static Mob spawn(ServerLevel level, EntityType<?> type, BlockPos pos, double health,
                             java.util.function.Consumer<Mob> prepare) {
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return null;
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        // A Warden brought up TRIGGERED digs its way out of the ground, as one called by a shrieker does.
        MobSpawnType spawnType = type == EntityType.WARDEN ? MobSpawnType.TRIGGERED : MobSpawnType.EVENT;
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), spawnType, null);
        if (mob.isSpawnCancelled()) {
            mob.discard();
            return null;
        }
        if (health != 1.0) {
            AttributeInstance max = mob.getAttribute(Attributes.MAX_HEALTH);
            if (max != null) {
                max.setBaseValue(max.getBaseValue() * health);
                mob.setHealth(mob.getMaxHealth());
            }
        }
        mob.addTag(TAG);
        prepare.accept(mob);
        level.addFreshEntityWithPassengers(mob);
        return mob;
    }

    private DeepSpawns() {
    }
}
