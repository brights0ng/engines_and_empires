// Written by Claude.
package dev.brights0ng.empire.protect;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import javax.annotation.Nullable;

import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfirePartBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.sacrifice.SacrificeInteractions;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * Divine protection and bonfire break rights (Bright's design).
 * <ul>
 *   <li>Each empire's divine area (the 3x3 chunks around its core chunk, full column, Overworld only; see
 *       {@link EmpireSavedData#getDivineOwner}) is closed to outsiders, players who are not its members: they cannot
 *       break, place, right-click blocks, use buckets there, touch item frames, paintings or armour stands, or trample
 *       farmland. At the empire's bonfire an outsider may still look with an empty hand and offer its god's founding meat
 *       to join. A frozen empire (no members) stays protected.</li>
 *   <li>Explosions, whoever set them off, break no blocks in any divine area; what they do to entities is unchanged.</li>
 *   <li>A bonfire (or part) whose main block stands in a staked chunk can be broken only by that empire's members,
 *       anywhere. Unclaimed bonfires are anyone's to break; the iron axe rule ({@code BonfireEvents}) still applies.</li>
 * </ul>
 * Physical access through Create is intended: fake players (deployers) are never stopped, and contraptions, drills, mobs
 * and fire spread fire none of these events. Creative players with permission level {@link #BYPASS_PERMISSION} pass.
 */
public final class DivineProtection {

    /** PROVISIONAL: creative players with at least this permission level are not outsiders anywhere. */
    public static final int BYPASS_PERMISSION = 2;
    /** At most one "protected" message per player in this many ticks. */
    private static final int MESSAGE_COOLDOWN_TICKS = 20;
    private static final Map<Player, Long> LAST_MESSAGE = new WeakHashMap<>();

    // ---- breaking ----

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        BlockPos pos = event.getPosition().orElse(null);
        if (pos != null && mayNotBreak(player, player.level(), pos, event.getState())) {
            event.setNewSpeed(0.0F);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (event.getLevel() instanceof Level level && mayNotBreak(player, level, event.getPos(), event.getState())) {
            event.setCanceled(true);
            tell(player);
        }
    }

    /** Whether {@code player} is stopped from breaking: an outsider in a divine area, or not of the bonfire's empire. */
    private static boolean mayNotBreak(Player player, Level level, BlockPos pos, BlockState state) {
        return outsiderAt(player, level, pos) || isOutsider(player, bonfireOwner(level, pos, state));
    }

    /** The empire whose staked chunk holds this bonfire's main block, or null (not a bonfire, or unclaimed). */
    @Nullable
    private static EmpireRecord bonfireOwner(Level level, BlockPos pos, BlockState state) {
        BlockPos main;
        if (state.is(EmpireContent.BONFIRE.get())) {
            main = pos;
        } else if (state.is(EmpireContent.BONFIRE_PART.get())) {
            main = pos.subtract(BonfirePartBlock.PART_OFFSETS[state.getValue(BonfirePartBlock.OFFSET)]);
        } else {
            return null;
        }
        EmpireSavedData data = data(level);
        return data != null ? data.getEmpireByChunk(chunkOf(main)) : null;
    }

    // ---- placing ----

    /** Also receives {@link BlockEvent.EntityMultiPlaceEvent}, which extends it: every placed block is checked. */
    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player) || !(event.getLevel() instanceof Level level)) {
            return;
        }
        List<BlockSnapshot> placed = event instanceof BlockEvent.EntityMultiPlaceEvent multi
                ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
        for (BlockSnapshot snapshot : placed) {
            if (outsiderAt(player, level, snapshot.getPos())) {
                event.setCanceled(true);
                tell(player);
                return;
            }
        }
    }

    // ---- right clicks ----

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        EmpireRecord owner = divineOwner(level, pos);
        if (!isOutsider(player, owner)) {
            return;
        }
        // At the empire's bonfire an outsider may read the demand (empty hand) or offer the god's meat to join.
        BlockState state = level.getBlockState(pos);
        ItemStack stack = event.getItemStack();
        if ((state.is(EmpireContent.BONFIRE.get()) || state.is(EmpireContent.BONFIRE_PART.get()))
                && (stack.isEmpty() || SacrificeInteractions.isJoinOffering(stack, owner))) {
            return;
        }
        event.setUseBlock(TriState.FALSE);
        event.setUseItem(TriState.FALSE);
        tell(player);
    }

    /**
     * Buckets: NeoForge 21.1 has no bucket event ({@code BucketItem.use} fires only this one), so the block the bucket
     * would act on is found the way the bucket finds it, and both it and the block in front of it are checked.
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        ItemStack stack = event.getItemStack();
        Player player = event.getEntity();
        Level level = event.getLevel();
        if (!(stack.getItem() instanceof BucketItem) || level.isClientSide()) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        ClipContext.Fluid fluid = stack.is(Items.BUCKET) ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE;
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, fluid, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        BlockPos front = pos.relative(hit.getDirection());
        if (outsiderAt(player, level, pos) || outsiderAt(player, level, front)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            if (player instanceof ServerPlayer serverPlayer) {
                // The client may already show the fluid moved and the bucket changed.
                serverPlayer.connection.send(new ClientboundBlockUpdatePacket(level, pos));
                serverPlayer.connection.send(new ClientboundBlockUpdatePacket(level, front));
                serverPlayer.inventoryMenu.sendAllDataToRemote();
            }
            tell(player);
        }
    }

    // ---- item frames, paintings and armour stands ----

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (guardedEntity(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (guardedEntity(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (guardedEntity(event.getEntity(), event.getTarget())) {
            event.setCanceled(true);
        }
    }

    private static boolean guardedEntity(Player player, Entity target) {
        if ((target instanceof HangingEntity || target instanceof ArmorStand)
                && outsiderAt(player, target.level(), target.blockPosition())) {
            tell(player);
            return true;
        }
        return false;
    }

    // ---- farmland and explosions ----

    @SubscribeEvent
    public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof Player player && event.getLevel() instanceof Level level
                && outsiderAt(player, level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        EmpireSavedData data = data(event.getLevel());
        if (data != null) {
            event.getAffectedBlocks().removeIf(pos -> data.getDivineOwner(chunkOf(pos)) != null);
        }
    }

    // ---- shared ----

    /** Whether {@code player} is an outsider of the divine area holding {@code pos}. */
    private static boolean outsiderAt(Player player, Level level, BlockPos pos) {
        return isOutsider(player, divineOwner(level, pos));
    }

    private static boolean isOutsider(Player player, @Nullable EmpireRecord owner) {
        return owner != null && !owner.isMember(player.getUUID()) && !passes(player);
    }

    /** Fake players (Create's deployers) and creative operators are never stopped. */
    private static boolean passes(Player player) {
        return player.isFakePlayer() || (player.isCreative() && player.hasPermissions(BYPASS_PERMISSION));
    }

    @Nullable
    private static EmpireRecord divineOwner(Level level, BlockPos pos) {
        EmpireSavedData data = data(level);
        return data != null ? data.getDivineOwner(chunkOf(pos)) : null;
    }

    /** The empires, for the server's Overworld only (empires claim nothing elsewhere), else null. */
    @Nullable
    private static EmpireSavedData data(Level level) {
        return level instanceof ServerLevel serverLevel && level.dimension() == Level.OVERWORLD
                ? EmpireSavedData.of(serverLevel) : null;
    }

    private static long chunkOf(BlockPos pos) {
        return ChunkKeys.pack(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static void tell(Player player) {
        long now = player.level().getGameTime();
        Long last = LAST_MESSAGE.get(player);
        if (last == null || now - last >= MESSAGE_COOLDOWN_TICKS || now < last) {
            LAST_MESSAGE.put(player, now);
            player.displayClientMessage(Component.translatable("message.empire.protected"), true);
        }
    }

    private DivineProtection() {
    }
}
