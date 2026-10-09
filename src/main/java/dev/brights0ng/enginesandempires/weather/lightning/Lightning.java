package dev.brights0ng.enginesandempires.weather.lightning;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.mixin.ServerLevelLightningInvoker;
import dev.brights0ng.enginesandempires.weather.WeatherOwnership;
import dev.brights0ng.enginesandempires.weather.cloud.CloudLife;
import dev.brights0ng.enginesandempires.weather.cloud.CloudScale;
import dev.brights0ng.enginesandempires.weather.cloud.sim.SimCloud;
import dev.brights0ng.enginesandempires.weather.rain.WeatherConfig;
import dev.brights0ng.enginesandempires.weather.ships.ShipCover;
import dev.brights0ng.enginesandempires.weather.sim.world.WeatherSim;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Lightning from the pack's thunderstorms (weather phase 6b; Bright, 2026-10-08). Vanilla's own strikes are behind its
 * global thunder, which is held clear, so this is the only lightning in the Overworld.
 *
 * <p>Once a second, every thunder cloud within {@link LightningModel.Settings#range} of a player may flash
 * ({@link LightningModel#chance}). A flash is placed in the storm ({@link LightningModel#where}) and then:
 * <ul>
 *   <li><b>Ground (25%):</b> attaches to the tallest thing within {@link LightningModel.Settings#attachRadius} of the
 *       spot under it, terrain or a ship ({@link LightningModel#tallest}). Vanilla then has its say from that column:
 *       a lightning rod within 128 blocks takes it, else a living thing around the column may. A real vanilla
 *       {@link LightningBolt} (fire, charged creepers, conversions, rods), with vanilla's skeleton-trap roll on terrain.
 *       On a ship the bolt is where the deck is seen, and its fire is put on the deck in the ship's own grid (vanilla's
 *       bolt would look for it in the empty world there).</li>
 *   <li><b>In the cloud (75%):</b> hits the nearest ship or airborne living thing inside the cloud within
 *       {@link LightningModel.Settings#skyReach} of the flash, if any; otherwise lights the cloud only.</li>
 * </ul>
 * Every flash is sent to the players within range ({@link LightningPayload}), so all of them see the same one. Strikes
 * need their chunk ticking; one that would land in a chunk that isn't stays in the cloud.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class Lightning {

    private static final String NET_VERSION = "1";
    private static final int PERIOD = 20;
    private static final int OFFSET = 7;
    /** At most this many flashes a second in a level, however many storms. */
    private static final int MAX_PER_PASS = 8;
    /** Grid step (blocks) of the columns looked at for the tallest point. */
    private static final int ATTACH_STEP = 1;
    /** How far below a cloud's base a flying thing still counts as inside it, blocks. */
    private static final double BELOW_BASE = 8;
    /** Vanilla's extra fires around a new bolt. */
    private static final int EXTRA_FIRES = 4;

    /** What a strike hit: where the bolt goes, the ship deck block to set alight (or null), and on terrain or not. */
    public record Strike(Vec3 at, BlockPos deckFire, boolean terrain) {
    }

    @SubscribeEvent
    static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NET_VERSION).optional();
        registrar.playToClient(LightningPayload.TYPE, LightningPayload.STREAM_CODEC,
                (payload, context) -> LightningFlashes.accept(payload));
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getGameTime() % PERIOD != OFFSET
                || level.players().isEmpty() || !WeatherOwnership.owns(level)) {
            return;
        }
        LightningModel.Settings s = WeatherConfig.lightning();
        WeatherSim sim = WeatherSim.of(level);
        if (!s.enabled() || sim == null) {
            return;
        }
        long now = level.getGameTime();
        List<ServerPlayer> players = level.players();
        int fired = 0;
        for (SimCloud c : new ArrayList<>(sim.cloudSim().clouds())) {
            if (c.lightning <= 0 || !near(players, c.xAt(now), c.zAt(now), s.range())) {
                continue;
            }
            if (level.random.nextDouble() >= LightningModel.chance(c.lightning, s.perMinute(), PERIOD)) {
                continue;
            }
            SplittableRandom rng = new SplittableRandom(level.random.nextLong());
            LightningModel.Kind kind = LightningModel.kind(rng.nextDouble(), s.groundShare());
            if (flash(level, c, now, kind, s, rng) != null && ++fired >= MAX_PER_PASS) {
                break;
            }
        }
    }

    /**
     * Makes {@code cloud} flash now, as {@code kind} would like (a ground strike that can't land stays in the cloud).
     * Returns the flash sent, or null if the cloud isn't showing. Public for {@code /eae weather lightning}.
     */
    public static LightningPayload flash(ServerLevel level, SimCloud cloud, long now, LightningModel.Kind kind,
                                         LightningModel.Settings s, SplittableRandom rng) {
        CloudLife.Phase phase = cloud.phase(now);
        if (!phase.visible()) {
            return null;
        }
        CloudScale.Heights h = CloudScale.heights(cloud.type, cloud.baseY, cloud.thickness, phase.growth());
        double cx = cloud.xAt(now);
        double cz = cloud.zAt(now);
        List<LightningModel.Dome> domes = new ArrayList<>(cloud.domes.size());
        for (SimCloud.Dome d : cloud.domes) {
            domes.add(new LightningModel.Dome(cx + d.dx(), cz + d.dz(), d.radius()));
        }
        LightningModel.Point p = LightningModel.where(domes, cx, cz, cloud.reach(), h.base(), h.top(), rng);
        if (p == null) {
            return null;
        }
        Strike strike = null;
        LightningModel.Kind did = LightningModel.Kind.CLOUD;
        if (kind == LightningModel.Kind.GROUND) {
            strike = groundTarget(level, p.x(), p.y(), p.z(), s.attachRadius());
            if (strike != null) {
                did = LightningModel.Kind.GROUND;
            }
        } else {
            strike = skyTarget(level, p.x(), p.y(), p.z(), h.base() - BELOW_BASE, h.top(), s.skyReach());
            if (strike != null) {
                did = LightningModel.Kind.SKY;
            }
        }
        if (strike != null) {
            strike(level, strike);
        }
        LightningPayload payload = new LightningPayload(p.x(), p.y(), p.z(), (float) p.radius(), cloud.lightning, did,
                strike == null ? Double.NaN : strike.at().x, strike == null ? Double.NaN : strike.at().y,
                strike == null ? Double.NaN : strike.at().z);
        send(level, payload, s.range());
        return payload;
    }

    /**
     * Where a ground strike from a flash at (x, y, z) lands, or null if nowhere it could is ticking. The tallest column
     * in reach (terrain, or a ship's top below the flash), then vanilla's targeting from there: a lightning rod, else
     * a living thing around the column. Public for the game tests.
     */
    public static Strike groundTarget(ServerLevel level, double x, double y, double z, double radius) {
        ShipCover.Area ships = ShipCover.area(level, x - radius, z - radius, x + radius, z + radius);
        List<LightningModel.Column> columns = new ArrayList<>();
        List<Boolean> onShip = new ArrayList<>();
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx += ATTACH_STEP) {
            for (int dz = -r; dz <= r; dz += ATTACH_STEP) {
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }
                int bx = (int) Math.floor(x) + dx;
                int bz = (int) Math.floor(z) + dz;
                BlockPos column = new BlockPos(bx, level.getMinBuildHeight(), bz);
                if (!level.isPositionEntityTicking(column)) {
                    continue;
                }
                double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
                double ship = ships.top(bx, bz);
                boolean deck = ship > ground && ship < y;
                columns.add(new LightningModel.Column(bx + 0.5, bz + 0.5, deck ? ship : ground));
                onShip.add(deck);
            }
        }
        // Each ship's own nearest strike point too: a small ship can sit between sampled columns, and a column on
        // the very edge of a ship's block can miss it at large coordinates.
        for (SubLevel s : ships.ships()) {
            Vec3 p = ShipCover.strikePoint(level, s, x, z);
            if (p == null || p.y >= y || Math.hypot(p.x - x, p.z - z) > radius
                    || !level.isPositionEntityTicking(BlockPos.containing(p))) {
                continue;
            }
            int px = (int) Math.floor(p.x);
            int pz = (int) Math.floor(p.z);
            if (p.y > level.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz)) {
                columns.add(new LightningModel.Column(p.x, p.z, p.y));
                onShip.add(true);
            }
        }
        LightningModel.Column best = LightningModel.tallest(columns, x, z);
        if (best == null) {
            return null;
        }
        boolean deck = onShip.get(columns.indexOf(best));
        int bx = (int) Math.floor(best.x());
        int bz = (int) Math.floor(best.z());
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, new BlockPos(bx, 0, bz));
        BlockPos vanilla = ((ServerLevelLightningInvoker) level).engines_and_empires$findLightningTargetAround(surface);
        boolean rod = level.getBlockState(vanilla.below()).is(Blocks.LIGHTNING_ROD);
        if (!deck || rod) {
            return new Strike(Vec3.atBottomCenterOf(vanilla), null, true);
        }
        // On a ship: a living thing standing on (or above) the deck still takes it, as vanilla's targeting would.
        if (!vanilla.equals(surface) && vanilla.getY() >= best.height() - 1) {
            return new Strike(Vec3.atBottomCenterOf(vanilla), null, false);
        }
        Vec3 at = new Vec3(best.x(), best.height(), best.z());
        return new Strike(at, ShipCover.plotAt(level, at.x, at.y + 0.05, at.z), false);
    }

    /**
     * What an in-cloud flash at (x, y, z) hits: the nearest ship or airborne living thing inside the cloud
     * (between {@code bottom} and {@code top}) within {@code reach}, or null. Public for the game tests.
     */
    public static Strike skyTarget(ServerLevel level, double x, double y, double z, double bottom, double top,
                                   double reach) {
        Strike best = null;
        double bestD = Double.POSITIVE_INFINITY;
        AABB box = new AABB(x - reach, bottom, z - reach, x + reach, top, z + reach);
        for (Entity e : level.getEntities((Entity) null, box, Lightning::airborne)) {
            double d = e.position().distanceToSqr(x, y, z);
            if (d < bestD && level.isPositionEntityTicking(e.blockPosition())) {
                bestD = d;
                best = new Strike(e.position(), null, false);
            }
        }
        for (SubLevel ship : ShipCover.shipsIn(level, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
            Vec3 at = ShipCover.strikePoint(level, ship, x, z);
            if (at == null || at.y < bottom || at.y > top) {
                continue;
            }
            double d = at.distanceToSqr(x, y, z);
            if (d < bestD && level.isPositionEntityTicking(BlockPos.containing(at))) {
                bestD = d;
                best = new Strike(at, ShipCover.plotAt(level, at.x, at.y + 0.05, at.z), false);
            }
        }
        return best;
    }

    /** A living thing off the ground and clear of the terrain below it (not riding: a ship or mount takes it). */
    private static boolean airborne(Entity e) {
        if (!(e instanceof LivingEntity) || !e.isAlive() || e.isSpectator() || e.onGround() || e.isPassenger()) {
            return false;
        }
        int ground = e.level().getHeight(Heightmap.Types.MOTION_BLOCKING, e.getBlockX(), e.getBlockZ());
        return e.getY() > ground + 2;
    }

    /** The bolt itself, with vanilla's skeleton-trap roll on terrain, and its fire on a ship's deck. */
    public static void strike(ServerLevel level, Strike strike) {
        BlockPos pos = BlockPos.containing(strike.at());
        boolean trap = strike.terrain() && level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING)
                && level.random.nextDouble() < level.getCurrentDifficultyAt(pos).getEffectiveDifficulty() * 0.01
                && !level.getBlockState(pos.below()).is(Blocks.LIGHTNING_ROD);
        if (trap) {
            SkeletonHorse horse = EntityType.SKELETON_HORSE.create(level);
            if (horse != null) {
                horse.setTrap(true);
                horse.setAge(0);
                horse.setPos(strike.at().x, strike.at().y, strike.at().z);
                level.addFreshEntity(horse);
            }
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null) {
            return;
        }
        bolt.moveTo(strike.at());
        bolt.setVisualOnly(trap);
        level.addFreshEntity(bolt);
        if (!trap && strike.deckFire() != null) {
            deckFire(level, strike.deckFire());
        }
    }

    /** Vanilla's new-bolt fires (at the spot and up to four around it), on a ship's deck in its own grid. */
    private static void deckFire(ServerLevel level, BlockPos pos) {
        Difficulty d = level.getDifficulty();
        if (!level.getGameRules().getBoolean(GameRules.RULE_DOFIRETICK) || (d != Difficulty.NORMAL && d != Difficulty.HARD)
                || !ShipCover.inPlot(level, pos)) {
            return;
        }
        light(level, pos);
        for (int i = 0; i < EXTRA_FIRES; i++) {
            light(level, pos.offset(level.random.nextInt(3) - 1, level.random.nextInt(3) - 1,
                    level.random.nextInt(3) - 1));
        }
    }

    private static void light(ServerLevel level, BlockPos pos) {
        BlockState fire = BaseFireBlock.getState(level, pos);
        if (level.getBlockState(pos).isAir() && fire.canSurvive(level, pos)) {
            level.setBlockAndUpdate(pos, fire);
        }
    }

    private static boolean near(List<ServerPlayer> players, double x, double z, double range) {
        double r2 = range * range;
        for (ServerPlayer p : players) {
            double dx = p.getX() - x;
            double dz = p.getZ() - z;
            if (dx * dx + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    private static void send(ServerLevel level, LightningPayload payload, double range) {
        double r2 = range * range;
        for (ServerPlayer p : level.players()) {
            double dx = p.getX() - payload.x();
            double dz = p.getZ() - payload.z();
            if (dx * dx + dz * dz <= r2) {
                PacketDistributor.sendToPlayer(p, payload);
            }
        }
    }

    private Lightning() {
    }
}
