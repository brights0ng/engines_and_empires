// Written by Gemini.
package dev.brights0ng.empire.ledger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.RegionRecord;
import dev.brights0ng.empire.region.RegionNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Tracks each player's currently selected region while holding the {@link AdministrativeLedgerItem}.
 * <p>
 * A selection stays active as long as the player holds an Administrative Ledger in their main hand or offhand,
 * belongs to the region's empire, and the region still exists. Unequipping the ledger, logging out, left-clicking
 * while holding the ledger, or deleting the region clears the selection automatically.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class LedgerSelection {

    private record Entry(UUID regionId, WeakReference<Player> playerRef) {
    }

    /** Player UUID -> active selection entry. */
    private static final Map<UUID, Entry> SELECTED = new HashMap<>();

    /** True if {@code player} is holding an {@link AdministrativeLedgerItem} in either hand. */
    public static boolean isHoldingLedger(Player player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return main.getItem() instanceof AdministrativeLedgerItem
                || off.getItem() instanceof AdministrativeLedgerItem;
    }

    /** Returns the raw selected region id for {@code playerId}, or {@code null}. */
    @Nullable
    public static UUID selectedRegionId(@Nullable UUID playerId) {
        if (playerId == null) {
            return null;
        }
        Entry entry = SELECTED.get(playerId);
        return entry != null ? entry.regionId() : null;
    }

    /**
     * Resolves {@code player}'s currently selected region if they are still holding the ledger, belong to
     * {@code empire}, and the region still exists; otherwise clears any stale selection and returns {@code null}.
     */
    @Nullable
    public static RegionRecord selectedRegion(ServerLevel overworld, Player player, EmpireSavedData data,
                                              EmpireRecord empire) {
        Entry entry = SELECTED.get(player.getUUID());
        if (entry == null) {
            return null;
        }
        if (!isHoldingLedger(player)) {
            clear(overworld, player);
            return null;
        }
        RegionRecord region = data.region(entry.regionId());
        if (region == null || !region.empireId().equals(empire.id())) {
            clear(overworld, player);
            return null;
        }
        return region;
    }

    /** Selects {@code regionId} for {@code player} and syncs the updated selection to the client. */
    public static void select(ServerLevel overworld, Player player, UUID regionId) {
        SELECTED.put(player.getUUID(), new Entry(regionId, new WeakReference<>(player)));
        if (player instanceof ServerPlayer serverPlayer) {
            RegionNetwork.pushToPlayer(overworld, serverPlayer);
        }
    }

    /** Clears {@code player}'s selection (if any) and syncs to the client. Returns true if a selection was cleared. */
    public static boolean clear(ServerLevel overworld, Player player) {
        Entry removed = SELECTED.remove(player.getUUID());
        if (removed == null) {
            return false;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            RegionNetwork.pushToPlayer(overworld, serverPlayer);
        }
        return true;
    }

    /** Clears every player's selection pointing at {@code regionId} (called when a region is deleted). */
    public static void clearForRegion(ServerLevel overworld, UUID regionId) {
        if (SELECTED.isEmpty() || regionId == null) {
            return;
        }
        List<UUID> affected = null;
        for (Map.Entry<UUID, Entry> entry : SELECTED.entrySet()) {
            if (regionId.equals(entry.getValue().regionId())) {
                if (affected == null) {
                    affected = new ArrayList<>();
                }
                affected.add(entry.getKey());
            }
        }
        if (affected == null) {
            return;
        }
        MinecraftServer server = overworld.getServer();
        for (UUID playerId : affected) {
            SELECTED.remove(playerId);
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && player.level().dimension().equals(Level.OVERWORLD)) {
                RegionNetwork.pushToPlayer(overworld, player);
            }
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (SELECTED.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel overworld = server.overworld();
        EmpireSavedData data = EmpireSavedData.of(overworld);
        List<UUID> toClear = null;
        for (Map.Entry<UUID, Entry> mapEntry : SELECTED.entrySet()) {
            UUID playerId = mapEntry.getKey();
            Entry entry = mapEntry.getValue();
            Player player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                Player ref = entry.playerRef().get();
                // Real ServerPlayers not in the player list are offline; non-ServerPlayer mock players in GameTests
                // stay tracked until removed or unequipped.
                if (ref != null && !(ref instanceof ServerPlayer) && !ref.isRemoved()) {
                    player = ref;
                }
            }
            boolean valid = false;
            if (player != null && player.level().dimension().equals(Level.OVERWORLD) && isHoldingLedger(player)) {
                RegionRecord region = data.region(entry.regionId());
                EmpireRecord empire = data.getEmpireOf(playerId);
                valid = region != null && empire != null && region.empireId().equals(empire.id());
            }
            if (!valid) {
                if (toClear == null) {
                    toClear = new ArrayList<>();
                }
                toClear.add(playerId);
            }
        }
        if (toClear != null) {
            for (UUID playerId : toClear) {
                SELECTED.remove(playerId);
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player != null && player.level().dimension().equals(Level.OVERWORLD)) {
                    RegionNetwork.pushToPlayer(overworld, player);
                }
            }
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SELECTED.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onServerStarted(ServerStartedEvent event) {
        SELECTED.clear();
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        SELECTED.clear();
    }

    private LedgerSelection() {
    }
}
