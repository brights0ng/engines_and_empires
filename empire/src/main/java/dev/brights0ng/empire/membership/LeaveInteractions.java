// Written by Claude; protection push on leaving by Claude.
package dev.brights0ng.empire.membership;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.god.GodDefinition;
import dev.brights0ng.empire.god.Gods;
import dev.brights0ng.empire.map.ClaimMapNetwork;
import dev.brights0ng.empire.rules.ChunkKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Leaving an empire (Bright's design): a member offers {@link #LEAVE_TALC} tarnished talc at any bonfire (main or part,
 * lit or not) in their own empire's land. The first offer only warns. An offer within {@link #LEAVE_LOCK_TICKS} of the
 * warning is ignored; one in the {@link #LEAVE_CONFIRM_TICKS} after that (up to 30 s after the warning) takes the talc and
 * the player leaves; a later one warns again. The last member leaving does not disband the empire: it stays, frozen, for
 * anyone to join.
 */
public final class LeaveInteractions {

    public static final int LEAVE_TALC = 5;
    public static final int LEAVE_LOCK_TICKS = 60;
    /** Bright, 2026-10-24: the warning lapses 30 s (600 ticks) after it was given. */
    public static final int LEAVE_CONFIRM_TICKS = 600 - LEAVE_LOCK_TICKS;

    /** When each player was last warned, in game time. Server-side and transient: a restart forgets it. */
    private static final Map<UUID, Long> PENDING_LEAVE = new HashMap<>();

    /**
     * Handles a right click on the bonfire at {@code pos} with tarnished talc. Anything that is not a leave offer (not a
     * member here, fewer than {@link #LEAVE_TALC}) passes, so the talc does what it otherwise would.
     */
    public static ItemInteractionResult tryLeave(ItemStack stack, Level level, BlockPos pos, Player player) {
        if (!stack.is(EmpireContent.TARNISHED_TALC.get()) || stack.getCount() < LEAVE_TALC
                || level.dimension() != Level.OVERWORLD) {
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

        UUID id = player.getUUID();
        long now = serverLevel.getGameTime();
        Long warnedAt = PENDING_LEAVE.get(id);
        long elapsed = warnedAt != null ? now - warnedAt : -1L;
        if (warnedAt == null || elapsed < 0 || elapsed > LEAVE_LOCK_TICKS + LEAVE_CONFIRM_TICKS) {
            PENDING_LEAVE.put(id, now);
            player.displayClientMessage(Component.translatable("message.empire.leave.warning", empireName(owner)), true);
            return ItemInteractionResult.CONSUME;
        }
        if (elapsed < LEAVE_LOCK_TICKS) {
            player.displayClientMessage(Component.translatable("message.empire.leave.wait"), true);
            return ItemInteractionResult.CONSUME;
        }

        PENDING_LEAVE.remove(id);
        if (!player.hasInfiniteMaterials()) {
            stack.shrink(LEAVE_TALC);
        }
        data.removeMember(owner, id);
        if (player instanceof ServerPlayer serverPlayer) {
            ClaimMapNetwork.push(serverPlayer);
        }
        serverLevel.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.8F, 0.8F);
        player.displayClientMessage(Component.translatable("message.empire.leave.left", empireName(owner)), true);
        EmpireSavedData.messageMembers(owner, serverLevel.getServer(),
                Component.translatable("message.empire.leave.member_left", player.getDisplayName()));
        return ItemInteractionResult.SUCCESS;
    }

    /** Forgets every pending warning (game tests). */
    public static void clearPending() {
        PENDING_LEAVE.clear();
    }

    /** Empires have no names of their own: "the empire of" its god, or a godless empire. */
    private static Component empireName(EmpireRecord record) {
        GodDefinition god = Gods.get(record.god());
        if (god != null) {
            return Component.translatable("message.empire.name", god.displayName());
        }
        return Component.translatable("message.empire.name.godless");
    }

    private LeaveInteractions() {
    }
}
