// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Local air temperature, using the shared mounted instrument lifecycle. */
public final class TemperatureSensorBlockEntity extends SensorBlockEntity {
    public TemperatureSensorBlockEntity(BlockPos pos, BlockState state) {
        super(InstrumentContent.TEMPERATURE_SENSOR_ENTITY.get(), pos, state, DEFAULT_RANGE);
    }
    @Override public boolean wind() { return false; }
}
