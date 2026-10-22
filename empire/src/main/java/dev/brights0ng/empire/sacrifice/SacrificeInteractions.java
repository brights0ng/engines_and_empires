// Written by Gemini.
package dev.brights0ng.empire.sacrifice;

import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.DawnClock;
import dev.brights0ng.empire.rules.EmpireState;
import dev.brights0ng.empire.rules.FlatSacrificeCost;
import dev.brights0ng.empire.tier.TierChecks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class SacrificeInteractions {

    public static ItemInteractionResult handleSacrifice(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand) {
        if (!stack.is(Items.BREAD)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // 1. Overworld check (P10)
        if (level.dimension() != Level.OVERWORLD) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.overworld_only"), true);
            return ItemInteractionResult.FAIL;
        }

        // 2. Bonfire must be lit or roaring
        if (!state.getValue(BonfireBlock.LIT)) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.unlit"), true);
            return ItemInteractionResult.FAIL;
        }

        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        EmpireSavedData data = EmpireSavedData.of(serverLevel.getServer());
        ChunkPos chunkPos = new ChunkPos(pos);
        long chunk = ChunkKeys.pack(chunkPos.x, chunkPos.z);
        EmpireRecord owner = data.getEmpireByChunk(chunk);

        EmpireRecord recordToUpdate;

        // 3. Resolve owner
        if (owner == null) {
            // Unclaimed bonfire
            if (data.getEmpireByFounder(player.getUUID()) != null) {
                player.displayClientMessage(Component.translatable("message.empire.sacrifice.already_founded"), true);
                return ItemInteractionResult.FAIL;
            }

            // D5 checks: 9 chunks free
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long c = ChunkKeys.pack(chunkPos.x + dx, chunkPos.z + dz);
                    if (data.isChunkStaked(c)) {
                        player.displayClientMessage(Component.translatable("message.empire.stake.owned_by_other"), true);
                        return ItemInteractionResult.FAIL;
                    }
                }
            }

            // Own chunk Settled
            if (!TierChecks.columnSettled(serverLevel, chunkPos)) {
                player.displayClientMessage(Component.translatable("message.empire.stake.not_settled"), true);
                return ItemInteractionResult.FAIL;
            }

            recordToUpdate = null; // Will be created upon payment
        } else {
            // Claimed bonfire: player must be founder, and must be main bonfire
            if (!player.getUUID().equals(owner.founder())) {
                player.displayClientMessage(Component.translatable("message.empire.sacrifice.not_founder"), true);
                return ItemInteractionResult.FAIL;
            }

            if (!pos.equals(owner.mainBonfire())) {
                player.displayClientMessage(Component.translatable("message.empire.sacrifice.not_main"), true);
                return ItemInteractionResult.FAIL;
            }

            recordToUpdate = owner;
        }

        // 4. Cost check (P5: >= 5 bread in held hand)
        int currentDominion = recordToUpdate != null ? recordToUpdate.state().dominion() : 0;
        var costList = FlatSacrificeCost.INSTANCE.costFor(currentDominion);
        int needed = costList.isEmpty() ? 5 : costList.get(0).count();
        if (stack.getCount() < needed) {
            player.displayClientMessage(Component.translatable("message.empire.sacrifice.insufficient_bread", needed), true);
            return ItemInteractionResult.FAIL;
        }

        // 5. Consume items
        if (!player.getAbilities().instabuild) {
            stack.shrink(needed);
        }

        // Apply effect
        if (recordToUpdate == null) {
            recordToUpdate = data.createEmpire(player.getUUID(), chunk, pos, serverLevel.getGameTime());
        } else {
            recordToUpdate.state().addDominion(EmpireState.DOMINION_PER_SACRIFICE);
            data.setDirty();
        }

        // 6. Feedback: sound + particles + action bar
        serverLevel.playSound(null, pos, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 1.0F, 1.0F);
        serverLevel.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 20, 0.5, 0.5, 0.5, 0.1);
        serverLevel.sendParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 25, 0.4, 0.4, 0.4, 0.05);

        long day = DawnClock.dayOf(serverLevel.getDayTime());
        int d = recordToUpdate.state().dominion();
        int a = recordToUpdate.state().available(day);
        player.displayClientMessage(Component.translatable("message.empire.sacrifice.success", d, a), true);

        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    private SacrificeInteractions() {
    }
}
