// Written by Gemini.
package dev.brights0ng.empire.record;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Server-side half of the player motion/action recorder. Stays completely dormant (O(1) empty-map check) unless a
 * player actively runs {@code /empire debug record start}, at which point confirmed world events (crop/block breaks,
 * placements/seeding, hoe tilling, farmland tramples, item pickups, and container inventory diffs) are streamed to
 * that player's client recorder for unified timeline logging.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class MotionRecorderServer {

    private static final Map<UUID, ActiveSession> ACTIVE = new ConcurrentHashMap<>();

    private record OpenContainerState(String menuId, long openedMs, Map<String, Integer> beforeInventory) {
    }

    private static final class ActiveSession {
        final String label;
        final long startedWallMs;
        @Nullable
        OpenContainerState openContainer;

        ActiveSession(String label, long startedWallMs) {
            this.label = label;
            this.startedWallMs = startedWallMs;
        }
    }

    public static boolean isRecording(UUID playerId) {
        return ACTIVE.containsKey(playerId);
    }

    public static boolean start(ServerPlayer player, String rawLabel) {
        String label = MotionTraceJson.sanitizeLabel(rawLabel);
        if (!MotionRecorderNetwork.sendControl(player, RecordPayloads.Action.START, label)) {
            return false;
        }
        ACTIVE.put(player.getUUID(), new ActiveSession(label, System.currentTimeMillis()));
        return true;
    }

    public static boolean stop(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        return MotionRecorderNetwork.sendControl(player, RecordPayloads.Action.STOP, "");
    }

    public static boolean queryStatus(ServerPlayer player) {
        return MotionRecorderNetwork.sendControl(player, RecordPayloads.Action.STATUS, "");
    }

    public static boolean mark(ServerPlayer player, String note) {
        if (!ACTIVE.containsKey(player.getUUID())) {
            return false;
        }
        return MotionRecorderNetwork.sendControl(player, RecordPayloads.Action.MARK, note);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ACTIVE.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (ACTIVE.isEmpty() || event.isCanceled() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.containsKey(player.getUUID())) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = event.getState();
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"pos\":[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(']')
                .append(",\"block\":").append(MotionTraceJson.jsonString(blockId(state.getBlock())));
        appendCropFields(sb, state);
        sb.append(",\"tool\":").append(MotionTraceJson.jsonString(itemId(player.getMainHandItem())))
                .append(",\"dist\":").append(MotionTraceJson.fmt(eyeDistToBlock(player, pos), 3))
                .append('}');
        MotionRecorderNetwork.sendServerEvent(player, player.level().getGameTime(), "block_break", sb.toString());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (ACTIVE.isEmpty() || event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.containsKey(player.getUUID())) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState placed = event.getPlacedBlock();
        BlockState replaced = event.getBlockSnapshot().getState();
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"pos\":[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(']')
                .append(",\"block\":").append(MotionTraceJson.jsonString(blockId(placed.getBlock())))
                .append(",\"replaced\":").append(MotionTraceJson.jsonString(blockId(replaced.getBlock())));
        appendCropFields(sb, placed);
        sb.append(",\"held\":").append(MotionTraceJson.jsonString(itemId(player.getMainHandItem())))
                .append(",\"dist\":").append(MotionTraceJson.fmt(eyeDistToBlock(player, pos), 3))
                .append('}');
        MotionRecorderNetwork.sendServerEvent(player, player.level().getGameTime(), "block_place", sb.toString());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onToolModify(BlockEvent.BlockToolModificationEvent event) {
        if (ACTIVE.isEmpty() || event.isCanceled() || event.isSimulated()
                || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.containsKey(player.getUUID()) || event.getItemAbility() != ItemAbilities.HOE_TILL) {
            return;
        }
        BlockPos pos = event.getPos();
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"pos\":[").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ()).append(']')
                .append(",\"before\":").append(MotionTraceJson.jsonString(blockId(event.getState().getBlock())))
                .append(",\"tool\":").append(MotionTraceJson.jsonString(itemId(event.getHeldItemStack())))
                .append(",\"dist\":").append(MotionTraceJson.fmt(eyeDistToBlock(player, pos), 3))
                .append('}');
        MotionRecorderNetwork.sendServerEvent(player, player.level().getGameTime(), "block_till", sb.toString());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (ACTIVE.isEmpty() || event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.containsKey(player.getUUID())) {
            return;
        }
        BlockPos pos = event.getPos();
        String json = "{\"pos\":[" + pos.getX() + ',' + pos.getY() + ',' + pos.getZ() + "]}";
        MotionRecorderNetwork.sendServerEvent(player, player.level().getGameTime(), "farmland_trample", json);
    }

    @SubscribeEvent
    static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (ACTIVE.isEmpty() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!ACTIVE.containsKey(player.getUUID())) {
            return;
        }
        ItemStack before = event.getOriginalStack();
        ItemStack remaining = event.getCurrentStack();
        int pickedCount = before.getCount() - remaining.getCount();
        if (pickedCount <= 0) {
            return;
        }
        Vec3 p = event.getItemEntity().position();
        String json = "{\"item\":" + MotionTraceJson.jsonString(itemId(before))
                + ",\"count\":" + pickedCount
                + ",\"pos\":[" + MotionTraceJson.fmt(p.x, 2) + ',' + MotionTraceJson.fmt(p.y, 2) + ','
                + MotionTraceJson.fmt(p.z, 2) + "]}";
        MotionRecorderNetwork.sendServerEvent(player, player.level().getGameTime(), "item_pickup", json);
    }

    @SubscribeEvent
    static void onContainerOpen(PlayerContainerEvent.Open event) {
        if (ACTIVE.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ActiveSession session = ACTIVE.get(player.getUUID());
        if (session == null) {
            return;
        }
        String menuId = describeMenu(event.getContainer());
        session.openContainer = new OpenContainerState(menuId, System.currentTimeMillis(), snapshotInventory(player));
        String json = "{\"menu\":" + MotionTraceJson.jsonString(menuId) + "}";
        MotionRecorderNetwork.sendServerEvent(player, player.level().getGameTime(), "container_open", json);
    }

    @SubscribeEvent
    static void onContainerClose(PlayerContainerEvent.Close event) {
        if (ACTIVE.isEmpty() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ActiveSession session = ACTIVE.get(player.getUUID());
        if (session == null) {
            return;
        }
        String menuId = describeMenu(event.getContainer());
        OpenContainerState prev = session.openContainer;
        session.openContainer = null;
        long durationMs = prev != null ? Math.max(0L, System.currentTimeMillis() - prev.openedMs()) : 0L;
        Map<String, Integer> delta = prev != null
                ? MotionTraceJson.diffInventoryCounts(prev.beforeInventory(), snapshotInventory(player))
                : Map.of();
        String json = "{\"menu\":" + MotionTraceJson.jsonString(menuId)
                + ",\"durationMs\":" + durationMs
                + ",\"delta\":" + MotionTraceJson.intMapJson(delta) + "}";
        MotionRecorderNetwork.sendServerEvent(player, player.level().getGameTime(), "container_close", json);
    }

    private static void appendCropFields(StringBuilder sb, BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) {
            sb.append(",\"cropAge\":").append(crop.getAge(state))
                    .append(",\"mature\":").append(crop.isMaxAge(state));
        }
    }

    private static Map<String, Integer> snapshotInventory(ServerPlayer player) {
        Map<String, Integer> counts = new HashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                counts.merge(itemId(s), s.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    private static String describeMenu(AbstractContainerMenu menu) {
        if (menu instanceof InventoryMenu) {
            return "minecraft:inventory";
        }
        try {
            ResourceLocation key = BuiltInRegistries.MENU.getKey(menu.getType());
            if (key != null) {
                return key.toString();
            }
        } catch (UnsupportedOperationException ignored) {
        }
        return menu.getClass().getSimpleName();
    }

    private static double eyeDistToBlock(ServerPlayer player, BlockPos pos) {
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
    }

    private static String blockId(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key != null ? key.toString() : "";
    }

    private static String itemId(ItemStack stack) {
        if (stack.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : "";
    }

    private MotionRecorderServer() {
    }
}
