// Written by Claude.
package dev.brights0ng.empire.civilian;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.data.CivilianRecord;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.TravellerSlot;
import dev.brights0ng.empire.rules.ArrivalRule;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.CivilianAppearance;
import dev.brights0ng.empire.rules.TravellerWants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Keeps civilian bodies in step with the records in {@link EmpireSavedData} (Bright, 2026-10-24): a record is the
 * master copy and its entity only the body. A body whose record is gone, or that is not the record's current body, is
 * refused when it joins the level; a record whose body is lost gets a new one (not a death); a dead citizen's record is
 * deleted. Citizens and waiting travellers live around their empire's home ({@link #homeFor}), which follows the main
 * bonfire.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class CivilianRoster {

    /** Ticks between checks for lost bodies (provisional). */
    public static final int MISSING_CHECK = 200;
    /** Checks in a row a body must be missing, with its last chunk's entities loaded, before it is replaced (provisional). */
    public static final int MISSING_STRIKES = 2;

    /** Missing-body strikes by civilian id; transient. */
    private static final Map<UUID, Integer> STRIKES = new HashMap<>();

    /**
     * Where an empire's citizens and waiting traveller live: the main bonfire if there is one, else the core chunk's
     * centre (on its surface when the chunk is loaded, else at {@code fallbackY}).
     */
    public static BlockPos homeFor(EmpireRecord record, ServerLevel level, int fallbackY) {
        if (record.mainBonfire() != null) {
            return record.mainBonfire();
        }
        long core = record.coreChunk();
        int cx = ChunkKeys.getX(core);
        int cz = ChunkKeys.getZ(core);
        BlockPos centre = new BlockPos((cx << 4) + 8, fallbackY, (cz << 4) + 8);
        return level.hasChunk(cx, cz) ? level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre) : centre;
    }

    @SubscribeEvent
    static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof CivilianEntity civilian)) {
            return;
        }
        EmpireSavedData data = EmpireSavedData.of(level);
        switch (civilian.status()) {
            case CITIZEN -> {
                // No record: its empire was disbanded or it died while unloaded. Another body: this one is a duplicate.
                CivilianRecord record = civilian.ownRecord(data);
                EmpireRecord empire = record != null ? data.getEmpire(record.empire()) : null;
                if (empire == null) {
                    event.setCanceled(true);
                    return;
                }
                civilian.setCustomName(Component.literal(record.name()));
                try {
                    civilian.setAppearance(CivilianAppearance.unpack(record.appearance()));
                } catch (IllegalArgumentException ignored) {
                    // A corrupt look in the record keeps the body's.
                }
                civilian.setHome(homeFor(empire, level, civilian.getBlockY()), CivilianEntity.HOME_RADIUS);
            }
            case TRAVELLER -> {
                UUID empireId = civilian.travellerEmpire();
                TravellerSlot slot = empireId != null ? data.traveller(empireId) : null;
                EmpireRecord empire = empireId != null ? data.getEmpire(empireId) : null;
                if (slot == null || empire == null || !slot.body().equals(civilian.getUUID())) {
                    event.setCanceled(true);
                    return;
                }
                civilian.setHome(homeFor(empire, level, civilian.getBlockY()), CivilianEntity.TRAVELLER_RADIUS);
            }
            default -> {
            }
        }
    }

    /** Moves the empire's loaded citizens and waiting traveller to its current home; called when the main bonfire changes. */
    public static void refreshHomes(@Nullable MinecraftServer server, EmpireRecord record) {
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        EmpireSavedData data = EmpireSavedData.of(server);
        for (CivilianRecord civilian : data.civiliansOf(record.id())) {
            if (overworld.getEntity(civilian.body()) instanceof CivilianEntity body && body.status() == CivilianEntity.Status.CITIZEN) {
                body.setHome(homeFor(record, overworld, body.getBlockY()), CivilianEntity.HOME_RADIUS);
            }
        }
        TravellerSlot slot = data.traveller(record.id());
        if (slot != null && overworld.getEntity(slot.body()) instanceof CivilianEntity body
                && body.status() == CivilianEntity.Status.TRAVELLER) {
            body.setHome(homeFor(record, overworld, body.getBlockY()), CivilianEntity.TRAVELLER_RADIUS);
        }
    }

    /**
     * A disbanded empire's loaded bodies walk off and vanish (Bright, D7); unloaded ones are refused when they next load,
     * as their records and slot are gone.
     */
    public static void onDisband(@Nullable MinecraftServer server, List<CivilianRecord> records, @Nullable TravellerSlot traveller) {
        for (CivilianRecord record : records) {
            STRIKES.remove(record.id());
        }
        if (server == null) {
            return;
        }
        ServerLevel overworld = server.overworld();
        for (CivilianRecord record : records) {
            if (overworld.getEntity(record.body()) instanceof CivilianEntity body) {
                body.startLeaving();
            }
        }
        if (traveller != null && overworld.getEntity(traveller.body()) instanceof CivilianEntity body) {
            body.startLeaving();
        }
    }

    /** Death is permanent (Bright, D5): a citizen's record is deleted and its empire told; a traveller's slot is freed. */
    static void onDeath(CivilianEntity civilian, ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        if (civilian.status() == CivilianEntity.Status.CITIZEN) {
            CivilianRecord record = civilian.ownRecord(data);
            if (record != null) {
                data.removeCivilian(record.id());
                STRIKES.remove(record.id());
                EmpireRecord empire = data.getEmpire(record.empire());
                if (empire != null) {
                    EmpireSavedData.messageMembers(empire, level.getServer(),
                            Component.translatable("message.empire.civilian.died", record.name()));
                }
            }
        } else if (civilian.status() == CivilianEntity.Status.TRAVELLER) {
            clearTravellerSlot(data, civilian);
        }
    }

    /** Clears the slot of the empire {@code traveller} waits at, if the slot is still its. */
    public static void clearTravellerSlot(EmpireSavedData data, CivilianEntity traveller) {
        UUID empireId = traveller.travellerEmpire();
        TravellerSlot slot = empireId != null ? data.traveller(empireId) : null;
        if (slot != null && slot.body().equals(traveller.getUUID())) {
            data.setTraveller(empireId, null);
        }
    }

    /**
     * A right-click on a waiting traveller. A member holding at least the wanted count of the wanted food is accepted
     * (or, at the cap, turned away); a member holding too few is told so; a non-member holding the food is told to wait
     * for the empire's folk; anyone else hears what it wants. Freshness is not checked (provisional).
     */
    static void travellerClicked(CivilianEntity traveller, Player player) {
        ServerLevel level = (ServerLevel) traveller.level();
        EmpireSavedData data = EmpireSavedData.of(level);
        UUID empireId = traveller.travellerEmpire();
        EmpireRecord empire = empireId != null ? data.getEmpire(empireId) : null;
        TravellerWants.Want want = TravellerWants.WANTS.get(traveller.want());
        ItemStack held = player.getMainHandItem();
        boolean holdsWanted = !held.isEmpty() && held.is(item(want));
        boolean member = empire != null && empire.isMember(player.getUUID());
        if (member && holdsWanted && held.getCount() >= want.count()) {
            if (data.citizenCount(empire.id()) >= ArrivalRule.PIONEER_LIMIT) {
                traveller.sayKey("civilian.empire.traveller.full");
                clearTravellerSlot(data, traveller);
                traveller.startLeaving();
                return;
            }
            if (!player.getAbilities().instabuild) {
                held.shrink(want.count());
            }
            recruit(traveller, empire, level);
            traveller.sayKey("civilian.empire.traveller.accepted");
        } else if (member && holdsWanted) {
            traveller.sayKey("civilian.empire.traveller.not_enough", String.valueOf(want.count()));
        } else if (holdsWanted) {
            traveller.sayKey("civilian.empire.traveller.not_member");
        } else {
            traveller.sayWant();
        }
    }

    public static Item item(TravellerWants.Want want) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(want.itemId()));
    }

    /**
     * Makes {@code civilian} a citizen of {@code empire}: a new record with its current name and look, whose body it is.
     * Frees any traveller slot it held and drops any record it was the body of. The empire's online members are told. No
     * cap is checked here.
     */
    public static CivilianRecord recruit(CivilianEntity civilian, EmpireRecord empire, ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        if (civilian.status() == CivilianEntity.Status.TRAVELLER) {
            clearTravellerSlot(data, civilian);
        } else if (civilian.status() == CivilianEntity.Status.CITIZEN) {
            CivilianRecord old = civilian.ownRecord(data);
            if (old != null) {
                data.removeCivilian(old.id());
            }
        }
        String name = civilian.getCustomName() != null ? civilian.getCustomName().getString() : civilian.getName().getString();
        CivilianRecord record = new CivilianRecord(UUID.randomUUID(), empire.id(), civilian.getUUID(), name,
                civilian.appearance().pack(), civilian.blockPosition(), civilian.chunkPosition().toLong(), level.getGameTime());
        data.addCivilian(record);
        civilian.becomeCitizen(record.id(), homeFor(empire, level, civilian.getBlockY()));
        EmpireSavedData.messageMembers(empire, level.getServer(), Component.translatable("message.empire.civilian.joined", name));
        return record;
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % MISSING_CHECK == 0) {
            runMissingCheck(event.getServer().overworld());
        }
    }

    /**
     * One check for lost bodies (Bright, D6): a record whose last chunk has its entities loaded but whose body is not in
     * the level gets a strike; at {@link #MISSING_STRIKES} it gets a new body with the same name and look. A body found
     * clears the strikes. Public for the game tests.
     */
    public static void runMissingCheck(ServerLevel overworld) {
        EmpireSavedData data = EmpireSavedData.of(overworld);
        for (CivilianRecord record : List.copyOf(data.allCivilians())) {
            if (!overworld.areEntitiesLoaded(record.lastChunk())) {
                continue;
            }
            Entity body = overworld.getEntity(record.body());
            if (body != null) {
                STRIKES.remove(record.id());
                continue;
            }
            int strikes = STRIKES.merge(record.id(), 1, Integer::sum);
            if (strikes >= MISSING_STRIKES) {
                STRIKES.remove(record.id());
                CivilianSpawning.respawnBody(record, overworld);
            }
        }
        STRIKES.keySet().removeIf(id -> data.civilian(id) == null);
    }

    private CivilianRoster() {
    }
}
