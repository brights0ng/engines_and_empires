package dev.brights0ng.enginesandempires.geophone;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The mechanical thumper's block entity: winds up from Create's rotational power (see {@link ThumperWinding}), arms itself
 * once fully wound, and waits for a redstone pulse before it lets go. Releasing slams it down onto the strike plate beneath
 * it and sends a vibration out to {@link SeismicShots#MECHANICAL_RANGE}, the same way a hand blow or a strike plate does,
 * just far stronger.
 *
 * <p>How much charge one tick of rotation adds depends on the shaft's speed, not just whether it is turning at all; see
 * {@link ThumperWinding}. If the shaft stops or slows below {@link ThumperWinding#MIN_RPM}, whatever charge it has holds
 * rather than draining away: nothing is lost by a factory briefly running short on power.
 *
 * <p>Once armed it stops winding (there is nowhere for the extra charge to go) and stays that way, indefinitely, until a
 * redstone pulse tells it to go. That keeps a factory line in control of exactly when the thump happens, rather than firing
 * the instant the shaft has turned enough.
 *
 * <h2>Client side</h2>
 * The head's height follows the charge: down on the strike plate when unwound, up at the top when fully wound. To keep that
 * smooth without a packet every tick, the client winds its own copy of the charge with the same {@link ThumperWinding}
 * maths, from the shaft speed it already knows; the server corrects it every {@link #lazyTick} while winding, and at once
 * on arming and release. When the charge drops (a release), the head does not snap down: it falls, accelerating, the way
 * something heavy would, over a few ticks.
 */
public class MechanicalThumperBlockEntity extends KineticBlockEntity {

    /** How far the head travels between fully wound and unwound, in blocks. */
    public static final float HEAD_TRAVEL = ThumperDataGen.HEAD_TRAVEL_PIXELS / 16.0F;

    /** How fast a falling head speeds up, in head heights (0 to 1) per tick, per tick. A full drop takes 4 ticks. */
    private static final float FALL_ACCELERATION = 0.15F;

    /** A drop smaller than this is a server correction to the client's guess, not a release: it snaps rather than falls. */
    private static final float FALL_THRESHOLD = 0.05F;

    /** How much charge it holds, from 0 (unwound) to 1 (fully wound). Stops changing once armed. */
    private float charge;

    /** Whether it is fully wound and waiting for a redstone pulse. */
    private boolean armed;

    /** Server only: whether the charge has changed since it was last sent to clients. */
    private boolean chargeUnsent;

    // Client only: the head's drawn height (0 = on the strike plate, 1 = fully up), last tick's, and how fast it is falling.
    private float headHeight = Float.NaN;
    private float prevHeadHeight;
    private float fallSpeed;

    public MechanicalThumperBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Whether it is fully wound and waiting to be released. Meant for the renderer. */
    public boolean isArmed() {
        return armed;
    }

    /** How far along its wind it is, from 0 to 1. Meant for the renderer. */
    public float charge() {
        return charge;
    }

    /** How far below its fully wound position the head is drawn, in blocks, blended across the partial tick. */
    public float getRenderedHeadOffset(float partialTick) {
        if (Float.isNaN(headHeight)) {
            return (1.0F - charge) * HEAD_TRAVEL;
        }
        return (1.0F - Mth.lerp(partialTick, prevHeadHeight, headHeight)) * HEAD_TRAVEL;
    }

    @Override
    public void tick() {
        super.tick();
        if (armed) {
            if (level.isClientSide) {
                tickHead();
            }
            return;
        }
        float next = ThumperWinding.advance(charge, getSpeed());
        if (next != charge) {
            charge = next;
            if (!level.isClientSide) {
                chargeUnsent = true;
                setChanged();
            }
        }
        if (level.isClientSide) {
            // Only the server decides it is armed; the client just stops at a full wind and waits to be told.
            tickHead();
            return;
        }
        if (ThumperWinding.isFullyWound(charge)) {
            arm();
        }
    }

    /** Moves the drawn head towards the charge: straight there going up, falling under "gravity" coming down. */
    private void tickHead() {
        if (Float.isNaN(headHeight)) {
            headHeight = prevHeadHeight = charge;
            return;
        }
        prevHeadHeight = headHeight;
        if (headHeight - charge > FALL_THRESHOLD || fallSpeed > 0) {
            fallSpeed += FALL_ACCELERATION;
            headHeight = Math.max(charge, headHeight - fallSpeed);
            if (headHeight <= charge) {
                fallSpeed = 0;
            }
        } else {
            headHeight = charge;
        }
    }

    /** Every half second or so while winding, corrects the clients' own running guess at the charge. */
    @Override
    public void lazyTick() {
        super.lazyTick();
        if (level != null && !level.isClientSide && chargeUnsent) {
            chargeUnsent = false;
            sendData();
        }
    }

    /** The moment it finishes winding: it locks in place and waits, with a clunk to say so. */
    private void arm() {
        armed = true;
        chargeUnsent = false;
        setChanged();
        sendData();
        level.playSound(null, getBlockPos(), SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 0.8F, 0.7F);
    }

    /** Called by the block when it senses a redstone signal. Does nothing unless it is actually armed. */
    public void releaseIfArmed() {
        if (!armed || !(level instanceof ServerLevel server)) {
            return;
        }
        armed = false;
        charge = 0.0F;
        chargeUnsent = false;
        setChanged();
        sendData();
        SeismicShots.thump(server, getBlockPos(), server.getBlockState(getBlockPos().below()));
    }

    /**
     * Big enough for everything drawn outside the block: the head reaches most of the way into the strike plate's block
     * below, the prongs up to half a block above, and the cog's teeth a pixel past each side.
     */
    @Override
    protected AABB createRenderBoundingBox() {
        return new AABB(worldPosition).inflate(1.0 / 16.0).expandTowards(0, -1, 0).expandTowards(0, 0.5, 0);
    }

    @Override
    protected void write(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        compound.putFloat("Charge", charge);
        compound.putBoolean("Armed", armed);
        super.write(compound, registries, clientPacket);
    }

    @Override
    protected void read(CompoundTag compound, HolderLookup.Provider registries, boolean clientPacket) {
        charge = compound.getFloat("Charge");
        armed = compound.getBoolean("Armed");
        super.read(compound, registries, clientPacket);
    }
}
