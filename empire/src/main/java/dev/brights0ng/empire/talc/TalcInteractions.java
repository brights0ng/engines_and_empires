// Written by Gemini.
package dev.brights0ng.empire.talc;

import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public final class TalcInteractions {

    private TalcInteractions() {
    }

    public static ItemInteractionResult tryBless(Level level, BlockPos pos, Player player, InteractionHand hand, ItemStack stack) {
        if (level.isClientSide()) {
            return ItemInteractionResult.SUCCESS;
        }

        // 1. Overworld check (P10)
        if (!level.dimension().equals(Level.OVERWORLD)) {
            player.displayClientMessage(Component.translatable("message.empire.not_overworld"), true);
            return ItemInteractionResult.FAIL;
        }

        // 2. Bonfire lit or roaring check (P7)
        BlockState state = level.getBlockState(pos);
        if (!state.getValue(BonfireBlock.LIT)) {
            player.displayClientMessage(Component.translatable("message.empire.bless.not_lit"), true);
            return ItemInteractionResult.FAIL;
        }

        // 3. Founder and main bonfire check (P7, P9)
        EmpireSavedData data = EmpireSavedData.of((ServerLevel) level);
        EmpireRecord record = data.getEmpireByFounder(player.getUUID());
        if (record == null) {
            player.displayClientMessage(Component.translatable("message.empire.no_empire"), true);
            return ItemInteractionResult.FAIL;
        }
        if (record.mainBonfire() == null || !record.mainBonfire().equals(pos)) {
            player.displayClientMessage(Component.translatable("message.empire.bless.not_main_bonfire"), true);
            return ItemInteractionResult.FAIL;
        }

        // 4. Success: consume 1 Raw Talc, give 1 Blessed Talc (P13)
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        ItemStack blessed = new ItemStack(EmpireContent.BLESSED_TALC.get());
        if (!player.getInventory().add(blessed)) {
            player.drop(blessed, false);
        }

        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 1.2F);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.ENCHANT,
                    pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                    15, 0.4, 0.4, 0.4, 0.1);
        }
        player.displayClientMessage(Component.translatable("message.empire.bless.success"), true);
        return ItemInteractionResult.SUCCESS;
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        ItemStack stack = event.getItemStack();

        if (!stack.is(EmpireContent.RAW_TALC.get())) {
            return;
        }
        if (!state.getBlock().equals(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)) {
            return;
        }

        // Intercept right-click so campfire cooking slots are never touched (P13, P14)
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide()));

        if (level.isClientSide()) {
            return;
        }

        // 1. Overworld check (P10)
        if (!level.dimension().equals(Level.OVERWORLD)) {
            event.getEntity().displayClientMessage(Component.translatable("message.empire.not_overworld"), true);
            return;
        }

        // 2. Lit check (P14)
        if (!state.getValue(CampfireBlock.LIT)) {
            event.getEntity().displayClientMessage(Component.translatable("message.empire.tarnish.not_lit"), true);
            return;
        }

        // 3. Success: consume 1 Raw Talc, give 1 Tarnished Talc (anyone can do this)
        if (!event.getEntity().hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        ItemStack tarnished = new ItemStack(EmpireContent.TARNISHED_TALC.get());
        if (!event.getEntity().getInventory().add(tarnished)) {
            event.getEntity().drop(tarnished, false);
        }

        level.playSound(null, pos, SoundEvents.SOUL_ESCAPE.value(), SoundSource.BLOCKS, 1.0F, 1.0F);
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SOUL,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    10, 0.25, 0.25, 0.25, 0.05);
        }
        event.getEntity().displayClientMessage(Component.translatable("message.empire.tarnish.success"), true);
    }
}
