// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class TemperatureSensorBlock extends SensorBlock {
    public static final MapCodec<TemperatureSensorBlock> CODEC = simpleCodec(TemperatureSensorBlock::new);
    public TemperatureSensorBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() { return CODEC; }
    @Override protected BlockEntityType<? extends SensorBlockEntity> entityType() { return InstrumentContent.TEMPERATURE_SENSOR_ENTITY.get(); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new TemperatureSensorBlockEntity(pos, state); }
}
