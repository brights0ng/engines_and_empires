package dev.brights0ng.enginesandempires.frontier.spawn;

import java.util.Optional;

import dev.brights0ng.enginesandempires.frontier.FrontierConfig;
import dev.brights0ng.enginesandempires.frontier.Tier;
import dev.brights0ng.enginesandempires.frontier.tier.FrontierLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.EventHooks;

/**
 * Frontier's extra spawner: on top of vanilla's spawning, it places packs of the biome's usual monsters close around each
 * player (16–40 blocks away rather than vanilla's 24–128), but only on Frontier land. It keeps its own count of the mobs it
 * has put around each player ({@link FrontierConfig#FRONTIER_SPAWN_CAP}, about 35: roughly twice vanilla's), so vanilla's
 * cap is left alone.
 *
 * <p>Everything a natural spawn goes through still applies: the mob's placement rules (so it still needs the dark, the
 * Frontier way), NeoForge's spawn events, and vanilla despawning once players move away.
 */
public final class FrontierSpawner {

    /** Marks the mobs this spawner placed, so each player's count only counts its own. */
    public static final String TAG = "engines_and_empires.frontier_spawn";

    private static final int MAX_PACK = 3;
    private static final int PACK_SPREAD = 4;
    private static final int VERTICAL_RANGE = 24;
    private static final int FLOOR_SEARCH = 6;

    static void tick(FrontierLevel frontier, FrontierConfig.SpawnParams params) {
        ServerLevel level = frontier.level();
        if (params.attempts() <= 0 || params.cap() <= 0 || level.getGameTime() % params.interval() != 0
                || level.getDifficulty() == Difficulty.PEACEFUL || !level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            int around = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(params.maxDistance() + 16),
                    mob -> mob.getTags().contains(TAG)).size();
            for (int i = 0; i < params.attempts() && around < params.cap(); i++) {
                around += spawnPack(frontier, player, params, level.random);
            }
        }
    }

    /** Tries to place one pack somewhere around {@code player}. Returns how many mobs it placed. */
    static int spawnPack(FrontierLevel frontier, ServerPlayer player, FrontierConfig.SpawnParams params, RandomSource random) {
        ServerLevel level = frontier.level();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = params.minDistance() + random.nextDouble() * (params.maxDistance() - params.minDistance());
        int x = Mth.floor(player.getX() + Math.cos(angle) * distance);
        int z = Mth.floor(player.getZ() + Math.sin(angle) * distance);
        BlockPos column = new BlockPos(x, player.getBlockY(), z);
        if (!level.isPositionEntityTicking(column)) {
            return 0;
        }
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
        int y = Mth.clamp(player.getBlockY() + random.nextInt(VERTICAL_RANGE * 2 + 1) - VERTICAL_RANGE,
                level.getMinBuildHeight(), top);
        BlockPos centre = new BlockPos(x, y, z);
        if (frontier.tierAt(centre) != Tier.FRONTIER) {
            return 0;
        }

        Holder<Biome> biome = level.getBiome(centre);
        WeightedRandomList<MobSpawnSettings.SpawnerData> choices = EventHooks.getPotentialSpawns(level, MobCategory.MONSTER,
                centre, level.getChunkSource().getGenerator().getMobsAt(biome, level.structureManager(), MobCategory.MONSTER, centre));
        Optional<MobSpawnSettings.SpawnerData> picked = choices.getRandom(random);
        if (picked.isEmpty()) {
            return 0;
        }
        MobSpawnSettings.SpawnerData data = picked.get();
        int size = Math.min(MAX_PACK, data.minCount + random.nextInt(1 + Math.max(0, data.maxCount - data.minCount)));
        int placed = 0;
        for (int i = 0; i < size; i++) {
            BlockPos at = floorNear(level, data.type, centre.offset(random.nextInt(PACK_SPREAD * 2 + 1) - PACK_SPREAD, 0,
                    random.nextInt(PACK_SPREAD * 2 + 1) - PACK_SPREAD), random, params);
            if (at != null && frontier.tierAt(at) == Tier.FRONTIER && spawnOne(level, data.type, at)) {
                placed++;
            }
        }
        return placed;
    }

    /** A spot at or just below {@code start} where the mob could stand and its placement rules allow it, or null. */
    private static BlockPos floorNear(ServerLevel level, EntityType<?> type, BlockPos start, RandomSource random,
                                      FrontierConfig.SpawnParams params) {
        BlockPos.MutableBlockPos pos = start.mutable();
        for (int dy = 0; dy < FLOOR_SEARCH && pos.getY() > level.getMinBuildHeight(); dy++, pos.move(0, -1, 0)) {
            if (!level.isPositionEntityTicking(pos) || tooCloseToAPlayer(level, pos, params.minDistance())) {
                return null;
            }
            if (SpawnPlacements.isSpawnPositionOk(type, level, pos)
                    && SpawnPlacements.checkSpawnRules(type, level, MobSpawnType.NATURAL, pos, random)
                    && level.noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static boolean tooCloseToAPlayer(ServerLevel level, BlockPos pos, int minDistance) {
        return level.getNearestPlayer(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, minDistance, false) != null;
    }

    private static boolean spawnOne(ServerLevel level, EntityType<?> type, BlockPos pos) {
        Entity entity = type.create(level);
        if (!(entity instanceof Mob mob)) {
            if (entity != null) {
                entity.discard();
            }
            return false;
        }
        mob.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, level.random.nextFloat() * 360.0F, 0.0F);
        if (!EventHooks.checkSpawnPosition(mob, level, MobSpawnType.NATURAL)) {
            mob.discard();
            return false;
        }
        EventHooks.finalizeMobSpawn(mob, level, level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);
        if (mob.isSpawnCancelled()) {
            mob.discard();
            return false;
        }
        mob.addTag(TAG);
        level.addFreshEntityWithPassengers(mob);
        return true;
    }

    private FrontierSpawner() {
    }
}
