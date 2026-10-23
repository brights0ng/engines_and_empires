// Written by Gemini; member check edited by Claude.
package dev.brights0ng.empire.talc;

import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.UnstakeResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public class TarnishedTalcItem extends Item {

    public TarnishedTalcItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        ServerLevel serverLevel = (ServerLevel) level;

        // 1. Overworld check (P10)
        if (!level.dimension().equals(Level.OVERWORLD)) {
            player.displayClientMessage(Component.translatable("message.empire.not_overworld"), true);
            return InteractionResult.FAIL;
        }

        // 2. Empire member check (P9)
        EmpireSavedData data = EmpireSavedData.of(serverLevel);
        EmpireRecord record = data.getEmpireOf(player.getUUID());
        if (record == null) {
            player.displayClientMessage(Component.translatable("message.empire.no_empire"), true);
            return InteractionResult.FAIL;
        }

        // 3. Un-staking logic (D32-D35, P8, §3.8)
        BlockPos clickedPos = context.getClickedPos();
        ChunkPos chunkPos = new ChunkPos(clickedPos);
        long chunk = ChunkKeys.pack(chunkPos.x, chunkPos.z);
        long day = DawnClock.dayOf(serverLevel.getDayTime());

        UnstakeResult result = data.unstakeChunk(record, chunk, day);
        if (result == UnstakeResult.OK) {
            if (!player.hasInfiniteMaterials()) {
                context.getItemInHand().shrink(1);
            }

            level.playSound(null, clickedPos, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.8F, 1.0F);
            spawnChunkCornerParticles(serverLevel, chunkPos, clickedPos.getY() + 1);

            player.displayClientMessage(
                    Component.translatable("message.empire.unstake.ok", chunkPos.x, chunkPos.z), true);
            return InteractionResult.SUCCESS;
        } else {
            String key = "message.empire.unstake." + result.name().toLowerCase(java.util.Locale.ROOT);
            player.displayClientMessage(Component.translatable(key, chunkPos.x, chunkPos.z), true);
            return InteractionResult.FAIL;
        }
    }

    private static void spawnChunkCornerParticles(ServerLevel level, ChunkPos chunk, int y) {
        int minX = chunk.getMinBlockX();
        int maxX = chunk.getMaxBlockX();
        int minZ = chunk.getMinBlockZ();
        int maxZ = chunk.getMaxBlockZ();

        int[][] corners = new int[][] {
            { minX, minZ },
            { minX, maxZ },
            { maxX, minZ },
            { maxX, maxZ }
        };

        for (int[] corner : corners) {
            level.sendParticles(ParticleTypes.SMOKE,
                    corner[0] + 0.5, y + 0.5, corner[1] + 0.5,
                    10, 0.2, 0.5, 0.2, 0.02);
        }
    }
}
