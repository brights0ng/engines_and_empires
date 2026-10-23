// Written by Claude.
package dev.brights0ng.empire.sacrifice;

import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.god.GodDefinition;
import dev.brights0ng.empire.god.Gods;
import dev.brights0ng.empire.rules.ChunkKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Leaving an empire (Bright's design): a member offers {@link #LEAVE_TALC} tarnished talc at any bonfire in their own
 * empire's land, lit or not. The first offering only warns; offering again from {@link #LEAVE_LOCK_TICKS} to
 * {@link #LEAVE_LOCK_TICKS} + {@link #LEAVE_CONFIRM_TICKS} ticks later takes the talc and the member leaves. Sooner is
 * ignored, later warns again. The warnings are not saved. The last member leaving does not disband the empire: it keeps
 * its land, dominion, god, demand and bonfires, for anyone to join.
 */
public final class LeaveInteractions {

    public static final int LEAVE_TALC = 5;
    /** Ticks after the warning during which offering again does nothing. */
    public static final long LEAVE_LOCK_TICKS = 60L;
    /** Ticks after the lock during which offering again leaves. */
    public static final long LEAVE_CONFIRM_TICKS = 200L;

    /** Tarnished talc at a bonfire: a member leaving, or (for anyone else) whatever the talc does by itself. */
    public static ItemInteractionResult handleLeave(ItemStack stack, Level level, BlockPos pos, Player player) {
        if (!stack.is(EmpireContent.TARNISHED_TALC.get()) || level.dimension() != Level.OVERWORLD) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide()) {
            // The client does not know who belongs to which empire; the server decides.
            return ItemInteractionResult.SUCCESS;
        }
        ServerLevel serverLevel = (ServerLevel) level;
        EmpireSavedData data = EmpireSavedData.of(serverLevel.getServer());
        EmpireRecord owner = data.getEmpireByChunk(ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4));
        if (owner == null || !owner.isMember(player.getUUID())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        return tryLeave(serverLevel, data, owner, pos, player, stack, serverLevel.getGameTime());
    }

    /** One offering of {@code stack} by a member of {@code owner}, at game time {@code now}. */
    public static ItemInteractionResult tryLeave(ServerLevel level, EmpireSavedData data, EmpireRecord owner, BlockPos pos,
            Player player, ItemStack stack, long now) {
        if (stack.getCount() < LEAVE_TALC) {
            player.displayClientMessage(Component.translatable("message.empire.leave.need_talc", LEAVE_TALC), true);
            return ItemInteractionResult.FAIL;
        }
        Long warnedAt = data.leaveWarnedAt(player.getUUID());
        long since = warnedAt != null ? now - warnedAt : -1L;
        if (warnedAt == null || since < 0L || since > LEAVE_LOCK_TICKS + LEAVE_CONFIRM_TICKS) {
            data.warnLeave(player.getUUID(), now);
            level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1.0F, 0.6F);
            player.displayClientMessage(Component.translatable("message.empire.leave.warning", empireName(owner)), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (since < LEAVE_LOCK_TICKS) {
            player.displayClientMessage(Component.translatable("message.empire.leave.wait"), true);
            return ItemInteractionResult.FAIL;
        }

        if (!player.getAbilities().instabuild) {
            stack.shrink(LEAVE_TALC);
        }
        data.removeMember(owner, player.getUUID());
        level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 0.6F);
        player.displayClientMessage(Component.translatable("message.empire.leave.left", empireName(owner)), true);
        EmpireSavedData.messageMembers(owner, level.getServer(),
                Component.translatable("message.empire.leave.member_left", player.getDisplayName()), null);
        return ItemInteractionResult.SUCCESS;
    }

    /** Empires have no names of their own; they are known by their god. */
    private static Component empireName(EmpireRecord record) {
        if (record.god() == null) {
            return Component.translatable("message.empire.name.godless");
        }
        GodDefinition god = Gods.get(record.god());
        Component godName = god != null ? god.displayName() : Component.translatable(GodDefinition.nameKey(record.god()));
        return Component.translatable("message.empire.name", godName);
    }

    private LeaveInteractions() {
    }
}
