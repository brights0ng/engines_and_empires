package dev.brights0ng.enginesandempires.geophone.client;

import java.util.function.Consumer;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;

import dev.brights0ng.enginesandempires.geophone.MechanicalThumperBlockEntity;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;

/**
 * The mechanical thumper's Flywheel visual, used whenever Flywheel is on (the default). Modelled on Create's
 * {@code MixerVisual} and {@code PressVisual}: the parent class spins Create's shaftless cogwheel on the thumper's axis
 * (tooth alignment and overstress tint included), and this adds the head as an oriented instance, moved every frame.
 *
 * <p>The lit lamp is not drawn here: {@link ThumperRenderer} always draws it, Flywheel or not.
 */
public class ThumperVisual extends SingleAxisRotatingVisual<MechanicalThumperBlockEntity> implements SimpleDynamicVisual {

    private final OrientedInstance head;

    public ThumperVisual(VisualizationContext context, MechanicalThumperBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick, Models.partial(AllPartialModels.SHAFTLESS_COGWHEEL));
        head = instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(ThumperClient.HEAD))
                .createInstance();
        head.rotation(ThumperClient.modelRotation(blockState));
        moveHead(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        moveHead(ctx.partialTick());
    }

    private void moveHead(float partialTick) {
        head.position(getVisualPosition())
                .translatePosition(0, -blockEntity.getRenderedHeadOffset(partialTick), 0)
                .setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        super.updateLight(partialTick);
        // The slab hangs below the block for its whole travel, so it takes the light of the block below, as the mixer's head does.
        relight(pos.below(), head);
    }

    @Override
    protected void _delete() {
        super._delete();
        head.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        super.collectCrumblingInstances(consumer);
        consumer.accept(head);
    }
}
