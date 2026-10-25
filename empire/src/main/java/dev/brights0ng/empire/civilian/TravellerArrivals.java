// Written by Claude.
package dev.brights0ng.empire.civilian;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.Empire;
import dev.brights0ng.empire.EmpireContent;
import dev.brights0ng.empire.bonfire.BonfireBlock;
import dev.brights0ng.empire.data.EmpireRecord;
import dev.brights0ng.empire.data.EmpireSavedData;
import dev.brights0ng.empire.data.TravellerSlot;
import dev.brights0ng.empire.rules.ArrivalRule;
import dev.brights0ng.empire.rules.ChunkKeys;
import dev.brights0ng.empire.rules.TravellerWants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Travellers come to an empire's lit main bonfire, one a day, until it has {@link ArrivalRule#PIONEER_LIMIT} citizens
 * (Bright, 2026-10-24). One appears at the edge of the empire's land, walks to the bonfire and waits there a day to be
 * given the food it wants ({@link CivilianRoster#travellerClicked}); then it walks off and vanishes.
 */
@EventBusSubscriber(modid = Empire.MODID)
public final class TravellerArrivals {

    /** Ticks between arrival checks (provisional). */
    public static final int ARRIVAL_CHECK = 100;
    /** A member must be this close (horizontally) to the main bonfire for a traveller to come (provisional). */
    public static final int MEMBER_NEAR = 64;
    /** Ticks a traveller waits to be accepted (provisional). */
    public static final long TRAVELLER_WAIT = 24000;

    /** A slot this long past its leave time has lost its body (it would have freed the slot itself). */
    private static final long LOST_SLOT_GRACE = 1200;
    private static final int SPOT_TRIES = 10;

    /** What a forced arrival did: the traveller and where it appeared, or why none came. */
    public record Forced(@Nullable CivilianEntity traveller, @Nullable BlockPos spot, @Nullable String failure) {
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % ARRIVAL_CHECK != 0) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        EmpireSavedData data = EmpireSavedData.of(level);
        long now = level.getGameTime();
        for (EmpireRecord record : List.copyOf(data.allEmpires())) {
            TravellerSlot slot = data.traveller(record.id());
            if (slot != null && now >= slot.leaveAt() + LOST_SLOT_GRACE) {
                data.setTraveller(record.id(), null);
                slot = null;
            }
            BlockPos main = record.mainBonfire();
            if (main == null || !ArrivalRule.canArrive(bonfireLit(level, main), memberNear(level, record, main),
                    data.citizenCount(record.id()), slot != null, now, record.nextTravellerTime())) {
                continue;
            }
            BlockPos spot = findSpawnSpot(level, record, level.random);
            if (spot != null) {
                spawnTraveller(record, level, spot);
            }
        }
    }

    /**
     * An arrival now for {@code record}, skipping only the day timer (the debug command): every other gate and a spawn
     * spot are still needed. Gates are checked in this order: main bonfire, citizens, waiting traveller, lit, member near.
     */
    public static Forced force(EmpireRecord record, ServerLevel level) {
        EmpireSavedData data = EmpireSavedData.of(level);
        BlockPos main = record.mainBonfire();
        int citizens = data.citizenCount(record.id());
        String failure = null;
        if (main == null) {
            failure = "the empire has no main bonfire";
        } else if (citizens >= ArrivalRule.PIONEER_LIMIT) {
            failure = "the empire has " + citizens + "/" + ArrivalRule.PIONEER_LIMIT + " citizens (the cap)";
        } else if (data.traveller(record.id()) != null) {
            failure = "a traveller is already waiting";
        } else if (!bonfireLit(level, main)) {
            failure = "the main bonfire is not lit";
        } else if (!memberNear(level, record, main)) {
            failure = "no member is within " + MEMBER_NEAR + " blocks of the main bonfire";
        }
        if (failure != null) {
            return new Forced(null, null, failure);
        }
        BlockPos spot = findSpawnSpot(level, record, level.random);
        if (spot == null) {
            return new Forced(null, null, "no spawn spot on the empire's edge");
        }
        return new Forced(spawnTraveller(record, level, spot), spot, null);
    }

    public static boolean bonfireLit(ServerLevel level, BlockPos main) {
        if (!level.isLoaded(main)) {
            return false;
        }
        BlockState state = level.getBlockState(main);
        return state.is(EmpireContent.BONFIRE.get()) && state.getValue(BonfireBlock.LIT);
    }

    /** Whether an online member is in the Overworld within {@link #MEMBER_NEAR} blocks (horizontally) of {@code main}. */
    public static boolean memberNear(ServerLevel level, EmpireRecord record, BlockPos main) {
        double r2 = (double) MEMBER_NEAR * MEMBER_NEAR;
        for (UUID member : record.members()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(member);
            if (player != null && player.level().dimension() == Level.OVERWORLD) {
                double dx = player.getX() - (main.getX() + 0.5);
                double dz = player.getZ() - (main.getZ() + 0.5);
                if (dx * dx + dz * dz <= r2) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A spot on the outward side of a random edge chunk (a staked chunk with an unstaked cardinal neighbour) whose centre
     * is entity-ticking: the surface of a random column along that side, on a sturdy, dry block with two free blocks
     * above. Tries up to {@link #SPOT_TRIES} chunks; null if none works.
     */
    @Nullable
    public static BlockPos findSpawnSpot(ServerLevel level, EmpireRecord record, RandomSource random) {
        List<Long> edges = new ArrayList<>();
        for (long chunk : record.state().staked()) {
            int cx = ChunkKeys.getX(chunk);
            int cz = ChunkKeys.getZ(chunk);
            if (!outwardSides(record, cx, cz).isEmpty()
                    && level.isPositionEntityTicking(new BlockPos((cx << 4) + 8, 0, (cz << 4) + 8))) {
                edges.add(chunk);
            }
        }
        Collections.shuffle(edges, new Random(random.nextLong()));
        for (int i = 0; i < Math.min(SPOT_TRIES, edges.size()); i++) {
            int cx = ChunkKeys.getX(edges.get(i));
            int cz = ChunkKeys.getZ(edges.get(i));
            List<Direction> sides = outwardSides(record, cx, cz);
            Direction side = sides.get(random.nextInt(sides.size()));
            int along = random.nextInt(16);
            int x = (cx << 4) + switch (side) {
                case EAST -> 15;
                case WEST -> 0;
                default -> along;
            };
            int z = (cz << 4) + switch (side) {
                case SOUTH -> 15;
                case NORTH -> 0;
                default -> along;
            };
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (standable(level, pos)) {
                return pos;
            }
        }
        return null;
    }

    private static List<Direction> outwardSides(EmpireRecord record, int cx, int cz) {
        List<Direction> sides = new ArrayList<>(4);
        for (Direction side : Direction.Plane.HORIZONTAL) {
            if (!record.state().staked().contains(ChunkKeys.pack(cx + side.getStepX(), cz + side.getStepZ()))) {
                sides.add(side);
            }
        }
        return sides;
    }

    private static boolean standable(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                && level.getFluidState(below).isEmpty()
                && free(level, pos) && free(level, pos.above());
    }

    private static boolean free(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty() && level.getFluidState(pos).isEmpty();
    }

    /**
     * Spawns {@code record}'s traveller at {@code pos}: it wants a random food, gives up after {@link #TRAVELLER_WAIT}
     * ticks and walks in to wait near the bonfire. Takes the empire's slot and starts the timer to the next arrival. No
     * gate is checked here (the game tests and the debug command use it).
     */
    public static CivilianEntity spawnTraveller(EmpireRecord record, ServerLevel level, BlockPos pos) {
        EmpireSavedData data = EmpireSavedData.of(level);
        long now = level.getGameTime();
        int want = TravellerWants.pick(level.random::nextLong);
        BlockPos home = CivilianRoster.homeFor(record, level, pos.getY());
        CivilianEntity traveller = CivilianSpawning.spawn(level, Vec3.atBottomCenterOf(pos), null, civilian -> {
            civilian.becomeTraveller(record.id(), want, now + TRAVELLER_WAIT, home);
            data.setTraveller(record.id(), new TravellerSlot(civilian.getUUID(), want, now + TRAVELLER_WAIT));
        });
        record.setNextTravellerTime(now + ArrivalRule.ARRIVAL_INTERVAL);
        data.setDirty();
        return traveller;
    }

    private TravellerArrivals() {
    }
}
