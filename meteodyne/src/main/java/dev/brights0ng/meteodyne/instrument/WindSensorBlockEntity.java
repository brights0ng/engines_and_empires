// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** A world-referenced downwind dial and cup anemometer; redstone measures speed in m/s. */
public final class WindSensorBlockEntity extends SensorBlockEntity {
    private double angle = Double.NaN, visualAngle = Double.NaN, previousAngle = Double.NaN;
    private double rotor, previousRotor;
    public WindSensorBlockEntity(BlockPos pos, BlockState state) {
        super(InstrumentContent.WIND_SENSOR_ENTITY.get(), pos, state, new SensorRange(0, 30));
    }
    @Override public boolean wind() { return true; }
    @Override public Vec3 samplePoint() { return worldPosition.getCenter(); }
    @Override protected double sample(ServerLevel server) {
        WindReading wind = InstrumentReadings.wind(server, samplePoint(), worldPosition);
        angle = Double.NaN;
        if (wind == null) { return Double.NaN; }
        if (!wind.calm()) {
            // A ship's facing rotates with its pose, so derive the dial's world heading from two plot points.
            Vec3 centre = InstrumentReadings.worldPoint(server, worldPosition.getCenter());
            Vec3 front = InstrumentReadings.worldPoint(server,
                    worldPosition.relative(getBlockState().getValue(SensorBlock.FACING)).getCenter());
            if (centre != null && front != null) {
                Vec3 delta = front.subtract(centre);
                double facing = Math.toDegrees(Math.atan2(-delta.x, delta.z));
                angle = wind.relativeAngle(facing);
            }
        }
        return wind.speed();
    }
    @Override public void tick() {
        super.tick();
        if (level != null && level.isClientSide) {
            previousAngle = visualAngle;
            if (!Double.isFinite(angle) || !Double.isFinite(visualAngle)) { previousAngle = visualAngle = angle; }
            else { visualAngle += WindReading.wrap(angle - visualAngle) * 0.25; }
            previousRotor = rotor;
            if (Double.isFinite(reading())) { rotor += WindReading.rotorStep(visualReading(1)); }
            if (rotor >= 360) { rotor -= 360; previousRotor -= 360; }
        }
    }
    public double angle(float partial) {
        return Double.isFinite(previousAngle) && Double.isFinite(visualAngle)
                ? previousAngle + (visualAngle - previousAngle) * partial : angle;
    }
    public double rotor(float partial) { return previousRotor + (rotor - previousRotor) * partial; }
    @Override protected void writeExtra(CompoundTag tag) { if (Double.isFinite(angle)) { tag.putDouble("WindAngle", angle); } }
    @Override protected void readExtra(CompoundTag tag) { angle = tag.contains("WindAngle") ? tag.getDouble("WindAngle") : Double.NaN; }
}
