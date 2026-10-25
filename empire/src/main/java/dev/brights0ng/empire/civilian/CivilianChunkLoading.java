// Written by Claude.
package dev.brights0ng.empire.civilian;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireConfig;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.LoadPlan;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Civilians keep the empire chunks around them running (civilian chunk-loading plan §3.2; Bright, D2, D5, D8, D10,
 * D11). Every {@link LoadPlan#LOAD_TICKS} each empire wants the staked chunks among the 3x3 around each citizen's
 * {@link CivilianRecord#lastChunk() last chunk}, and holds a ticking ticket ({@link #TICKETS}, owned by the empire) on
 * each while it may load. Ticking tickets give random ticks too (verified, {@code gametest.ChunkLoadingGameTests}).
 *
 * <p>The records are the only source (Bright, 2026-10-25): NeoForge's saved tickets are all dropped on load and the
 * set is rebuilt from the records on the first recompute, so a restart needs no special handling.
 *
 * <p>Not here yet: moving civilians outside empire land back (D12, with the civilian work AI). A civilian outside gets
 * no tickets of its own, so it freezes when no player is near.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class CivilianChunkLoading {

    /** Empire's civilian tickets; every saved one is dropped on load (the records rebuild them). */
    public static final TicketController TICKETS = new TicketController(Empire.id("civilians"),
            (level, helper) -> List.copyOf(helper.getEntityTickets().keySet()).forEach(helper::removeAllTickets));

    /** Each empire's held tickets; transient. */
    private static final Map<UUID, LoadPlan.Tickets> HELD = new HashMap<>();
    /** Game tests only: replaces the configured mode while set. */
    @Nullable
    private static LoadPlan.Mode modeOverride;

    /** On the mod bus (registered from {@code Empire}). */
    public static void registerTicketControllers(RegisterTicketControllersEvent event) {
        event.register(TICKETS);
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % LoadPlan.LOAD_TICKS == 0) {
            recompute(event.getServer().overworld());
        }
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        HELD.clear();
    }

    /** One recompute of every empire's tickets (all in the Overworld). Public for the game tests. */
    public static void recompute(ServerLevel overworld) {
        MinecraftServer server = overworld.getServer();
        EmpireSavedData data = EmpireSavedData.of(overworld);
        LoadPlan.Mode mode = modeOverride != null ? modeOverride : EmpireConfig.civilianLoading();
        long inactiveLimit = EmpireConfig.civilianLoadingInactiveTicks();
        long now = overworld.getGameTime();
        boolean dirty = false;

        Set<UUID> seen = new HashSet<>();
        for (EmpireRecord empire : data.allEmpires()) {
            seen.add(empire.id());
            boolean memberOnline = memberOnline(server, empire);
            if (memberOnline || empire.lastMemberOnline() == EmpireRecord.UNKNOWN) {
                // An unknown time (a record from before it was kept) starts the count now.
                empire.setLastMemberOnline(now);
                dirty = true;
            }
            boolean mayLoad = LoadPlan.mayLoad(mode, !empire.members().isEmpty(), memberOnline, now,
                    empire.lastMemberOnline(), inactiveLimit);
            List<Long> civilianChunks = new ArrayList<>();
            for (CivilianRecord civilian : data.civiliansOf(empire.id())) {
                civilianChunks.add(civilian.lastChunk());
            }
            Set<Long> staked = empire.state().staked();
            LoadPlan.Tickets tickets = HELD.computeIfAbsent(empire.id(), k -> new LoadPlan.Tickets());
            LoadPlan.Changes changes = tickets.update(LoadPlan.wanted(civilianChunks, staked), staked, mayLoad, now,
                    LoadPlan.KEEP_TICKS);
            for (long chunk : changes.remove()) {
                force(overworld, empire.id(), chunk, false);
            }
            for (long chunk : changes.add()) {
                force(overworld, empire.id(), chunk, true);
            }
            if (tickets.held().isEmpty() && !mayLoad) {
                HELD.remove(empire.id());
            }
        }
        // Empires disbanded since the last recompute.
        HELD.entrySet().removeIf(entry -> {
            if (seen.contains(entry.getKey())) {
                return false;
            }
            for (long chunk : entry.getValue().clear()) {
                force(overworld, entry.getKey(), chunk, false);
            }
            return true;
        });
        if (dirty) {
            data.setDirty();
        }
    }

    private static boolean memberOnline(MinecraftServer server, EmpireRecord empire) {
        for (UUID member : empire.members()) {
            if (server.getPlayerList().getPlayer(member) != null) {
                return true;
            }
        }
        return false;
    }

    private static void force(ServerLevel level, UUID empire, long chunk, boolean add) {
        TICKETS.forceChunk(level, empire, ChunkKeys.getX(chunk), ChunkKeys.getZ(chunk), add, true);
    }

    /** The chunks the empire holds tickets on now. */
    public static Set<Long> held(UUID empire) {
        LoadPlan.Tickets tickets = HELD.get(empire);
        return tickets != null ? tickets.held() : Set.of();
    }

    /** Whether the empire's civilians may load chunks now, under the configured mode. */
    public static boolean mayLoad(MinecraftServer server, EmpireRecord empire) {
        LoadPlan.Mode mode = modeOverride != null ? modeOverride : EmpireConfig.civilianLoading();
        return LoadPlan.mayLoad(mode, !empire.members().isEmpty(), memberOnline(server, empire),
                server.overworld().getGameTime(), empire.lastMemberOnline(), EmpireConfig.civilianLoadingInactiveTicks());
    }

    /** Game tests only: sets (or with null clears) a mode used instead of the config. */
    public static void setModeOverride(@Nullable LoadPlan.Mode mode) {
        modeOverride = mode;
    }

    private CivilianChunkLoading() {
    }
}
