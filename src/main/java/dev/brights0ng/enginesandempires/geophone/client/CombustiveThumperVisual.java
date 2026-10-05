package dev.brights0ng.enginesandempires.geophone.client;

import java.util.function.Consumer;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.content.kinetics.base.SingleAxisRotatingVisual;
import com.simibubi.create.foundation.render.AllInstanceTypes;

import dev.brights0ng.enginesandempires.geophone.CombustiveThumperBlockEntity;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import net.minecraft.core.Direction;

/**
 * The combustive thumper's Flywheel visual, used whenever Flywheel is on (the default). The parent class spins the shaft
 * through the base; this adds the ram and the lift rods standing on it, moved up and down together every frame from
 * {@link CombustiveThumperBlockEntity#renderedRamHeight}. The rods are turned to match the column's axis, since they run
 * through slots in the cylinder's cap either side of the exhaust.
 *
 * <p>The gauges and the lamp are not drawn here: {@link CombustiveThumperRenderer} always draws them.
 */
public class CombustiveThumperVisual extends SingleAxisRotatingVisual<CombustiveThumperBlockEntity>
        implements SimpleDynamicVisual {

    private final OrientedInstance ram;
    private final OrientedInstance rods;

    public CombustiveThumperVisual(VisualizationContext context, CombustiveThumperBlockEntity blockEntity, float partialTick) {
        super(context, blockEntity, partialTick, Models.partial(AllPartialModels.SHAFT));
        ram = instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(ThumperClient.COMBUSTIVE_RAM))
                .createInstance();
        rods = instancerProvider().instancer(InstanceTypes.ORIENTED, Models.partial(ThumperClient.COMBUSTIVE_RODS))
                .createInstance();
        rods.rotation(ThumperClient.modelRotation(blockState));
        moveRam(partialTick);
    }

    @Override
    public void beginFrame(DynamicVisual.Context ctx) {
        moveRam(ctx.partialTick());
    }

    private void moveRam(float partialTick) {
        float rise = blockEntity.renderedRamHeight(partialTick) * CombustiveThumperBlockEntity.RAM_TRAVEL;
        ram.position(getVisualPosition()).translatePosition(0, rise, 0).setChanged();
        // The rods are modelled in the top block's pixels, two blocks up.
        rods.position(getVisualPosition()).translatePosition(0, 2 + rise, 0).setChanged();
    }

    @Override
    public void updateLight(float partialTick) {
        super.updateLight(partialTick);
        // The ram spends its life in the rail section, one block up.
        relight(pos.above(), ram);
        relight(pos.above(2), rods);
    }

    @Override
    protected void _delete() {
        super._delete();
        ram.delete();
        rods.delete();
    }

    @Override
    public void collectCrumblingInstances(Consumer<Instance> consumer) {
        super.collectCrumblingInstances(consumer);
        consumer.accept(ram);
        consumer.accept(rods);
    }
}
