// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public final class WindSensorBlock extends SensorBlock {
    public static final MapCodec<WindSensorBlock> CODEC = simpleCodec(WindSensorBlock::new);
    public WindSensorBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends FaceAttachedHorizontalDirectionalBlock> codec() { return CODEC; }
    @Override protected BlockEntityType<? extends SensorBlockEntity> entityType() { return InstrumentContent.WIND_SENSOR_ENTITY.get(); }
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,
            net.minecraft.world.level.BlockGetter level, BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        return WindSensorShape.shape(state.getValue(FACE), state.getValue(FACING));
    }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new WindSensorBlockEntity(pos, state); }
}
