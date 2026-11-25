// Written by Claude; extended with D12 off-empire anchor tickets and move-back by Gemini.
package dev.brights0ng.empire.civilian;

import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
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

    /** Memory-only level-33 anchor ticket for a loaded citizen outside its empire's staked chunks (D12). */
    private static final TicketType<ChunkPos> OFF_EMPIRE_ANCHOR = TicketType.create(
            "empire_civilian_anchor", Comparator.comparingLong(ChunkPos::toLong));
    /** Brief entity-loading ticket (level 31) used after restart to pull an unloaded off-empire citizen home (D12). */
    private static final TicketType<ChunkPos> RESTART_PULL_BACK = TicketType.create(
            "empire_civilian_pullback", Comparator.comparingLong(ChunkPos::toLong));
    private static final int ANCHOR_DISTANCE = 0; // level 33 (memory-only, nothing ticks)
    private static final int PULL_BACK_DISTANCE = 2; // level 31 (brief entity load to move citizen back)

    /** Each empire's held tickets; transient. */
    private static final Map<UUID, LoadPlan.Tickets> HELD = new HashMap<>();
    /** Level-33 anchor chunk currently held per off-empire citizen id; transient. */
    private static final Map<UUID, ChunkPos> ANCHORS = new HashMap<>();
    /** Temporary pull-back chunk currently held per unloaded off-empire citizen id; transient. */
    private static final Map<UUID, ChunkPos> PULL_BACKS = new HashMap<>();
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
        ANCHORS.clear();
        PULL_BACKS.clear();
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
        if (reconcileOffEmpire(overworld, data)) {
            dirty = true;
        }
        if (dirty) {
            data.setDirty();
        }
    }

    /**
     * D12 off-empire move-back (civilian chunk-loading plan §3.2):
     * <ul>
     *   <li>A loaded citizen outside its empire's staked chunks holds a memory-only level-33 anchor ticket while a
     *       non-spectator player is within 128 blocks; once no player is within 128 blocks it is teleported to the
     *       nearest staked chunk's surface and its anchor is released.</li>
     *   <li>An unloaded citizen record saved outside its empire's staked chunks (e.g. after a restart) briefly loads
     *       its chunk to pull the body back inside staked territory.</li>
     * </ul>
     */
    public static boolean reconcileOffEmpire(ServerLevel overworld) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        boolean dirty = reconcileOffEmpire(overworld, data);
        if (dirty) {
            data.setDirty();
        }
        return dirty;
    }

    private static boolean reconcileOffEmpire(ServerLevel overworld, EmpireSavedData data) {
        boolean dirty = false;
        Set<UUID> liveIds = new HashSet<>();
        double r2 = LoadPlan.MOVE_BACK_PLAYER_RADIUS * LoadPlan.MOVE_BACK_PLAYER_RADIUS;

        for (CivilianRecord record : List.copyOf(data.allCivilians())) {
            EmpireRecord empire = data.getEmpire(record.empire());
            if (empire == null) {
                continue;
            }
            Set<Long> staked = empire.state().staked();
            if (staked.isEmpty()) {
                continue;
            }
            liveIds.add(record.id());

            Entity entity = overworld.getEntity(record.body());
            if (entity instanceof CivilianEntity citizen) {
                if (citizen.status() != CivilianEntity.Status.CITIZEN || !citizen.isAlive()) {
                    releaseAnchor(overworld, record.id());
                    releasePullBack(overworld, record.id());
                    continue;
                }
                long actualChunk = citizen.chunkPosition().toLong();
                if (staked.contains(actualChunk)) {
                    releaseAnchor(overworld, record.id());
                    releasePullBack(overworld, record.id());
                    continue;
                }
                // Outside staked territory!
                if (hasNearbyNonSpectatorPlayer(overworld, citizen.getX(), citizen.getZ(), r2)) {
                    setAnchor(overworld, record.id(), citizen.chunkPosition());
                    releasePullBack(overworld, record.id());
                } else {
                    if (teleportToNearestStaked(overworld, citizen, record, staked)) {
                        dirty = true;
                    }
                    releaseAnchor(overworld, record.id());
                    releasePullBack(overworld, record.id());
                }
            } else if (entity == null) {
                // Record's body is not currently loaded; if saved outside staked chunks, briefly load to pull it back.
                releaseAnchor(overworld, record.id());
                if (!staked.contains(record.lastChunk())
                        && !hasNearbyNonSpectatorPlayer(overworld, record.lastPos().getX() + 0.5, record.lastPos().getZ() + 0.5, r2)) {
                    setPullBack(overworld, record.id(), new ChunkPos(record.lastChunk()));
                } else {
                    releasePullBack(overworld, record.id());
                }
            } else {
                // Non-civilian stand-in entity (used in chunk-loading tests): never relocate.
                releaseAnchor(overworld, record.id());
                releasePullBack(overworld, record.id());
            }
        }

        // Release tickets for removed/dead/disbanded citizens.
        ANCHORS.entrySet().removeIf(e -> {
            if (liveIds.contains(e.getKey())) {
                return false;
            }
            overworld.getChunkSource().removeRegionTicket(OFF_EMPIRE_ANCHOR, e.getValue(), ANCHOR_DISTANCE, e.getValue());
            return true;
        });
        PULL_BACKS.entrySet().removeIf(e -> {
            if (liveIds.contains(e.getKey())) {
                return false;
            }
            overworld.getChunkSource().removeRegionTicket(RESTART_PULL_BACK, e.getValue(), PULL_BACK_DISTANCE, e.getValue());
            return true;
        });
        return dirty;
    }

    private static boolean teleportToNearestStaked(ServerLevel level, CivilianEntity citizen,
                                                   CivilianRecord record, Set<Long> staked) {
        LoadPlan.BlockXZ target = LoadPlan.nearestStakedPoint(citizen.getBlockX(), citizen.getBlockZ(), staked);
        if (target == null) {
            return false;
        }
        int cx = target.x() >> 4;
        int cz = target.z() >> 4;
        int y = level.hasChunk(cx, cz)
                ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.x(), target.z())
                : citizen.getBlockY();
        BlockPos dest = new BlockPos(target.x(), y, target.z());
        citizen.getNavigation().stop();
        citizen.teleportTo(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
        long destChunk = ChunkKeys.pack(cx, cz);
        record.setLast(dest, destChunk);
        citizen.aiController().stopAll(level);
        return true;
    }

    private static boolean hasNearbyNonSpectatorPlayer(ServerLevel level, double x, double z, double r2) {
        for (Player player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) {
                continue;
            }
            double dx = player.getX() - x;
            double dz = player.getZ() - z;
            if (dx * dx + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    private static void setAnchor(ServerLevel level, UUID civId, ChunkPos chunk) {
        ChunkPos prev = ANCHORS.get(civId);
        if (chunk.equals(prev)) {
            return;
        }
        if (prev != null) {
            level.getChunkSource().removeRegionTicket(OFF_EMPIRE_ANCHOR, prev, ANCHOR_DISTANCE, prev);
        }
        level.getChunkSource().addRegionTicket(OFF_EMPIRE_ANCHOR, chunk, ANCHOR_DISTANCE, chunk);
        ANCHORS.put(civId, chunk);
    }

    private static void releaseAnchor(ServerLevel level, UUID civId) {
        ChunkPos prev = ANCHORS.remove(civId);
        if (prev != null) {
            level.getChunkSource().removeRegionTicket(OFF_EMPIRE_ANCHOR, prev, ANCHOR_DISTANCE, prev);
        }
    }

    private static void setPullBack(ServerLevel level, UUID civId, ChunkPos chunk) {
        ChunkPos prev = PULL_BACKS.get(civId);
        if (chunk.equals(prev)) {
            return;
        }
        if (prev != null) {
            level.getChunkSource().removeRegionTicket(RESTART_PULL_BACK, prev, PULL_BACK_DISTANCE, prev);
        }
        level.getChunkSource().addRegionTicket(RESTART_PULL_BACK, chunk, PULL_BACK_DISTANCE, chunk);
        PULL_BACKS.put(civId, chunk);
    }

    private static void releasePullBack(ServerLevel level, UUID civId) {
        ChunkPos prev = PULL_BACKS.remove(civId);
        if (prev != null) {
            level.getChunkSource().removeRegionTicket(RESTART_PULL_BACK, prev, PULL_BACK_DISTANCE, prev);
        }
    }

    /** Number of off-empire level-33 anchor tickets currently held (for diagnostics and tests). */
    public static int anchorCount() {
        return ANCHORS.size();
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
