package dev.brights0ng.enginesandempires.geophone;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.brights0ng.enginesandempires.frontier.deep.ShotSource;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * The combustive thumper's block entity, which runs the whole three-tall machine from its base. Create torque, coming in
 * through a shaft, lifts the ram up the rails into the fuel cylinder; a charge of fuel from the tank, ignited on a redstone
 * pulse, drives it back down onto the anvil and through it into the strike plate.
 *
 * <p>What a shot does depends on what is in the tank; see {@link CombustiveFiring} for the rules and {@link ThumperFluids}
 * for how a fluid is sorted. In short: diesel reaches 1024, gasoline and biodiesel 768, other combustibles 1024 but
 * explode, and an empty tank or a non-combustible just drops the ram for a 128 thump. A non-combustible also jams the ram
 * down until it is drained, by pipe, bucket, or a sneak-click with a wrench.
 *
 * <h2>Firing</h2>
 * A shot is not instant: the pulse releases the latch, and the rest plays out over a few ticks (see
 * {@link CombustiveAnimation}): injection hiss, ignition (flame and smoke from the exhaust), the ram slamming down, and the
 * vibration exactly as it lands. The shot's outcome, and the fuel it burns, are settled at the pulse; the server then
 * only plays the sequence out. Clients are sent the tick the latch let go and animate the ram from that alone.
 *
 * <p>Between shots, the ram's lift is predicted on the client from the shaft speed, the same way the mechanical thumper's
 * head is, and corrected every half second while it climbs.
 */
public class CombustiveThumperBlockEntity extends KineticBlockEntity {

    /** A full lift at {@link ThumperWinding#REFERENCE_RPM} takes this long: three seconds, against the mechanical's ten. */
    public static final int LIFT_REFERENCE_TICKS = 60;

    /** How far the ram travels between the anvil and fully raised, in blocks (20 pixels). */
    public static final float RAM_TRAVEL = 20.0F / 16.0F;

    /** The fuel tank, a bucket's worth: from four to six shots, depending on the fuel. */
    public static final int TANK_CAPACITY = 1000;

    /** How big the blast is when a volatile fluid is fired. TNT is 4. */
    public static final float EXPLOSION_POWER = 3.0F;

    /** How many ratchet clicks a full lift makes. */
    private static final int RATCHET_CLICKS = 8;

    private SmartFluidTankBehaviour tank;

    /** How far raised the ram is, from 0 (on the anvil) to 1 (fully up). */
    private float lift;

    /** Whether the ram is fully up, latched, and waiting for a redstone pulse. */
    private boolean armed;

    // The shot in progress: the game tick the latch let go (-1 when there is none), and what it will do.
    private long fireStart = -1;
    private boolean fireIgnited;
    private int fireRange;
    private boolean fireExplodes;
    private boolean ignitionDone;
    private boolean impactDone;

    /** Server only: whether the lift has changed since it was last sent to clients. */
    private boolean liftUnsent;

    /** Client only: last tick's lift, to draw between ticks. */
    private float prevLift;

    public CombustiveThumperBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        super.addBehaviours(behaviours);
        tank = SmartFluidTankBehaviour.single(this, TANK_CAPACITY);
        behaviours.add(tank);
    }

    /** The tank, as pipes, pumps and buckets see it. Registered as the fluid capability of all three blocks. */
    public IFluidHandler fluidCapability() {
        return tank.getCapability();
    }

    public FluidStack fluid() {
        return tank.getPrimaryHandler().getFluid();
    }

    public float lift() {
        return lift;
    }

    public boolean isArmed() {
        return armed;
    }

    private boolean firing() {
        return fireStart >= 0;
    }

    private boolean jammed() {
        return !armed && !firing() && !CombustiveFiring.canLift(ThumperFluids.classify(fluid()));
    }

    // ---- ticking ----

    @Override
    public void tick() {
        super.tick();
        if (level.isClientSide) {
            tickClient();
            return;
        }
        if (firing()) {
            tickShot();
            return;
        }
        if (armed || jammed()) {
            return;
        }
        float next = ThumperWinding.advance(lift, getSpeed(), LIFT_REFERENCE_TICKS);
        if (next == lift) {
            return;
        }
        if ((int) (next * RATCHET_CLICKS) > (int) (lift * RATCHET_CLICKS) && next < ThumperWinding.FULLY_WOUND) {
            level.playSound(null, getBlockPos(), SoundEvents.CHAIN_PLACE, SoundSource.BLOCKS, 0.35F, 0.9F + 0.5F * next);
        }
        lift = next;
        liftUnsent = true;
        setChanged();
        if (ThumperWinding.isFullyWound(lift)) {
            lift = 1.0F;
            armed = true;
            setChanged();
            sendData();
            level.playSound(null, getBlockPos().above(2), SoundEvents.IRON_TRAPDOOR_CLOSE, SoundSource.BLOCKS, 0.9F, 0.8F);
        }
    }

    /** The client's own running guess at the lift, so the ram climbs smoothly between the server's corrections. */
    private void tickClient() {
        prevLift = lift;
        if (armed || shotPlaying(0) || jammed()) {
            return;
        }
        lift = Math.min(1.0F, ThumperWinding.advance(lift, getSpeed(), LIFT_REFERENCE_TICKS));
    }

    /** Plays out a shot in progress: ignition, then impact, then back to lifting. */
    private void tickShot() {
        ServerLevel server = (ServerLevel) level;
        long t = level.getGameTime() - fireStart;
        BlockPos pos = getBlockPos();
        if (fireIgnited && !ignitionDone && t >= CombustiveAnimation.IGNITE_TICK) {
            ignitionDone = true;
            ignite(server, pos);
        }
        if (!impactDone && t >= CombustiveAnimation.impactTick(fireIgnited)) {
            impactDone = true;
            setChanged();
            SeismicShots.thump(server, pos, server.getBlockState(pos.below()), fireRange,
                    fireIgnited ? ShotSource.COMBUSTIVE : ShotSource.COMBUSTIVE_DRY);
            if (fireIgnited) {
                server.sendParticles(ParticleTypes.CLOUD, pos.getX() + 0.5, pos.getY() + 1.05, pos.getZ() + 0.5,
                        10, 0.45, 0.02, 0.45, 0.04);
            }
            if (fireExplodes) {
                explode(server, pos);
                return;
            }
        }
        if (t >= CombustiveAnimation.endTick(fireIgnited)) {
            fireStart = -1;
            setChanged();
            sendData();
        }
    }

    /** The charge going off in the cylinder: a bang, and flame and smoke out of the exhaust and around the ram. */
    private static void ignite(ServerLevel server, BlockPos pos) {
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;
        // The top of the exhaust stack: the fuel cylinder is two blocks up, and its stack reaches 22 pixels above that.
        double exhaust = pos.getY() + 2 + 22.0 / 16.0;
        server.playSound(null, pos.above(2), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.BLOCKS, 0.9F, 1.35F);
        server.playSound(null, pos.above(2), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F, 0.7F);
        server.sendParticles(ParticleTypes.FLASH, x, exhaust + 0.2, z, 1, 0, 0, 0, 0);
        server.sendParticles(ParticleTypes.FLAME, x, exhaust, z, 12, 0.08, 0.1, 0.08, 0.06);
        server.sendParticles(ParticleTypes.LARGE_SMOKE, x, exhaust + 0.1, z, 18, 0.12, 0.2, 0.12, 0.05);
        server.sendParticles(ParticleTypes.POOF, x, pos.getY() + 2.35, z, 8, 0.35, 0.05, 0.35, 0.03);
    }

    /**
     * Once in a while: checks the column above is still whole (an explosion or a command can take a part away without the
     * base knowing) and, if not, breaks the base too, dropping the machine. Also sends the lift to clients while climbing,
     * and hisses and drips when jammed.
     */
    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level == null || level.isClientSide) {
            return;
        }
        if (!CombustiveThumperBlock.columnIntact(level, getBlockPos())) {
            level.destroyBlock(getBlockPos(), true);
            return;
        }
        if (liftUnsent) {
            liftUnsent = false;
            sendData();
        }
        if (jammed() && level.random.nextInt(3) == 0 && level instanceof ServerLevel server) {
            BlockPos pos = getBlockPos();
            server.playSound(null, pos.above(2), SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.25F, 1.8F);
            server.sendParticles(ParticleTypes.DRIPPING_DRIPSTONE_WATER, pos.getX() + 0.5, pos.getY() + 2.35,
                    pos.getZ() + 0.5, 2, 0.3, 0.0, 0.3, 0.0);
        }
    }

    // ---- firing ----

    /** Called by any block of the column on a redstone pulse. Does nothing unless the ram is latched at the top. */
    public void fireIfArmed() {
        if (!armed || firing() || !(level instanceof ServerLevel server)) {
            return;
        }
        FluidStack inTank = fluid();
        CombustiveFiring.Shot shot = CombustiveFiring.fire(ThumperFluids.classify(inTank), inTank.getAmount());
        if (shot.burnMb() > 0) {
            tank.getPrimaryHandler().drain(shot.burnMb(), IFluidHandler.FluidAction.EXECUTE);
        }

        armed = false;
        lift = 0.0F;
        fireStart = level.getGameTime();
        fireIgnited = shot.ignited();
        fireRange = shot.range();
        fireExplodes = shot.explodes();
        ignitionDone = false;
        impactDone = false;
        setChanged();
        sendData();

        BlockPos cylinder = getBlockPos().above(2);
        server.playSound(null, cylinder, SoundEvents.IRON_TRAPDOOR_OPEN, SoundSource.BLOCKS, 0.8F, 0.7F);
        if (shot.ignited()) {
            // Fuel injection: a sharp hiss while the ram hangs at the top.
            server.playSound(null, cylinder, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6F, 1.6F);
        }
    }

    private void explode(ServerLevel server, BlockPos pos) {
        Vec3 centre = pos.above().getCenter();
        server.explode(null, null, new FromInsideTheThumper(Set.of(pos, pos.above(), pos.above(2))),
                centre.x, centre.y, centre.z, EXPLOSION_POWER, false, Level.ExplosionInteraction.TNT);
    }

    /**
     * Explosion rules for a blast that starts inside the thumper. An explosion's rays lose strength to every block they
     * pass through, starting with the one they start in, and the thumper is tough (blast resistance 10): left alone, the
     * rays would spend nearly all their strength getting out of it, and the blast would only ever break the thumper and its
     * plate. So the thumper's own blocks count as open air to the blast, the way a TNT block is already gone by the time
     * it explodes. They are still broken (and the machine dropped) by it; they just do not shield anything.
     */
    private static final class FromInsideTheThumper extends ExplosionDamageCalculator {
        private final Set<BlockPos> thumper;

        FromInsideTheThumper(Set<BlockPos> thumper) {
            this.thumper = thumper;
        }

        @Override
        public Optional<Float> getBlockExplosionResistance(Explosion explosion, BlockGetter reader, BlockPos pos,
                                                           BlockState state, FluidState fluid) {
            if (thumper.contains(pos)) {
                return Optional.of(0.0F);
            }
            return super.getBlockExplosionResistance(explosion, reader, pos, state, fluid);
        }
    }

    /** Empties the tank completely, voiding whatever was in it. Returns whether there was anything to empty. */
    public boolean flush() {
        if (fluid().isEmpty()) {
            return false;
        }
        tank.getPrimaryHandler().setFluid(FluidStack.EMPTY);
        setChanged();
        sendData();
        return true;
    }

    // ---- for the renderer ----

    /** Whether a shot is still playing out, {@code partialTick} into the current tick. Client and server. */
    private boolean shotPlaying(float partialTick) {
        return firing() && CombustiveAnimation.playing(level.getGameTime() - fireStart + partialTick, fireIgnited);
    }

    /** The ram's height, as a fraction of {@link #RAM_TRAVEL}, blended across the partial tick. */
    public float renderedRamHeight(float partialTick) {
        if (shotPlaying(partialTick)) {
            return CombustiveAnimation.ramHeight(level.getGameTime() - fireStart + partialTick, fireIgnited);
        }
        if (armed) {
            return 1.0F;
        }
        return Mth.lerp(partialTick, prevLift, lift);
    }

    /** How full the tank looks, from 0 to 1, eased the way Create eases its own tanks' fluid levels. */
    public float renderedFill(float partialTick) {
        return tank.getPrimaryTank().getFluidLevel().getValue(partialTick);
    }

    /** Whether the lamp is lit: only while the ram is latched at the top, ready to fire. */
    public boolean lampLit() {
        return armed && !firing();
    }

    /** Whether what is in the tank will not ignite: the fuel gauge's needle turns red. */
    public boolean gaugeWarning() {
        return ThumperFluids.classify(fluid()) == CombustiveFiring.Charge.INERT;
    }

    /** All three blocks, plus the exhaust stack and the lift rods, which stand well clear of the top when raised. */
    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).inflate(1.0 / 16.0).expandTowards(0, 3.5, 0);
    }

    // ---- goggles ----

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        super.addToGoggleTooltip(tooltip, isPlayerSneaking);
        FluidStack inTank = fluid();
        CombustiveFiring.Charge charge = ThumperFluids.classify(inTank);
        tooltip.add(goggleLine(headLine(charge)));
        tooltip.add(goggleLine(chargeLine(charge, inTank.getAmount())));
        containedFluidTooltip(tooltip, isPlayerSneaking, fluidCapability());
        return true;
    }

    /** The ram: jammed, ready, or how far raised. */
    private Component headLine(CombustiveFiring.Charge charge) {
        if (!CombustiveFiring.canLift(charge) && !armed) {
            return Component.translatable(LANG + "head.jammed").withStyle(ChatFormatting.RED);
        }
        if (armed) {
            return Component.translatable(LANG + "head.ready").withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable(LANG + "head.lifting", Math.round(lift * 100)).withStyle(ChatFormatting.GRAY);
    }

    /** What the next shot will do with what is in the tank. */
    private static Component chargeLine(CombustiveFiring.Charge charge, int amount) {
        CombustiveFiring.Shot shot = CombustiveFiring.fire(charge, amount);
        return switch (charge) {
            case PREMIUM_FUEL, FUEL -> shot.ignited()
                    ? Component.translatable(LANG + "charge.fuel", shot.range(), shot.burnMb()).withStyle(ChatFormatting.GOLD)
                    : Component.translatable(LANG + "charge.short").withStyle(ChatFormatting.YELLOW);
            case VOLATILE -> shot.ignited()
                    ? Component.translatable(LANG + "charge.volatile").withStyle(ChatFormatting.RED)
                    : Component.translatable(LANG + "charge.short").withStyle(ChatFormatting.YELLOW);
            case INERT -> Component.translatable(LANG + "charge.inert").withStyle(ChatFormatting.RED);
            case EMPTY -> Component.translatable(LANG + "charge.empty").withStyle(ChatFormatting.GRAY);
        };
    }

    private static Component goggleLine(Component text) {
        // The same four-space indent Create's own goggle lines use.
        return Component.literal("    ").append(text);
    }

    private static final String LANG = "engines_and_empires.combustive_thumper.";

    // ---- saving and syncing ----

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        compound.putFloat("Lift", lift);
        compound.putBoolean("Armed", armed);
        compound.putLong("FireStart", fireStart);
        compound.putBoolean("FireIgnited", fireIgnited);
        if (!clientPacket) {
            compound.putInt("FireRange", fireRange);
            compound.putBoolean("FireExplodes", fireExplodes);
            compound.putBoolean("IgnitionDone", ignitionDone);
            compound.putBoolean("ImpactDone", impactDone);
        }
        super.write(compound, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        lift = compound.getFloat("Lift");
        armed = compound.getBoolean("Armed");
        fireStart = compound.contains("FireStart") ? compound.getLong("FireStart") : -1;
        fireIgnited = compound.getBoolean("FireIgnited");
        if (!clientPacket) {
            fireRange = compound.getInt("FireRange");
            fireExplodes = compound.getBoolean("FireExplodes");
            ignitionDone = compound.getBoolean("IgnitionDone");
            impactDone = compound.getBoolean("ImpactDone");
        } else {
            prevLift = lift;
        }
        super.read(compound, registries, clientPacket);
    }
}
