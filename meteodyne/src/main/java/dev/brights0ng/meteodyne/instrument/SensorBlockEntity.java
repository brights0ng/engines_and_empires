// Written by ChatGPT.
// Updated by ChatGPT: register loaded sensor demand without changing its reading path.
package dev.brights0ng.meteodyne.instrument;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The authoritative reading and redstone range, plus a separately eased client display. */
public abstract class SensorBlockEntity extends BlockEntity implements MenuProvider {
    public static final SensorRange DEFAULT_RANGE = new SensorRange(0, 30);
    public static final int MIN_TENTHS = SensorRangeControls.MIN;
    public static final int MAX_TENTHS = SensorRangeControls.MAX;
    private SensorRange range = DEFAULT_RANGE;
    protected double reading = Double.NaN;
    private double sentReading = Double.NaN;
    private boolean sampled;
    private double visual = Double.NaN;
    private double previousVisual = Double.NaN;

    protected SensorBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state, SensorRange defaults) {
        super(type, pos, state);
        range = defaults;
    }

    public abstract boolean wind();
    @Override public void onLoad() {
        super.onLoad();
        if(level instanceof ServerLevel server)
            dev.brights0ng.meteodyne.heat.world.HeatWorld.sensorDemandLoaded(server,worldPosition,getBlockState());
    }
    public SensorRange defaultRange() { return wind() ? new SensorRange(0, 30) : DEFAULT_RANGE; }
    public int minimum() { return wind() ? 0 : MIN_TENTHS; }
    public int maximum() { return wind() ? 1000 : MAX_TENTHS; }
    public String unit() { return wind() ? "m/s" : "°C"; }
    protected double sample(ServerLevel server) { return InstrumentReadings.temperature(server, samplePoint()); }
    protected void readExtra(CompoundTag tag) { }
    protected void writeExtra(CompoundTag tag) { }

    public SensorRange range() { return range; }
    public double reading() { return reading; }

    /** Sample the air in front of the face, rather than the heat grid's solid sensor-housing cell. */
    public Vec3 samplePoint() {
        return worldPosition.relative(getBlockState().getValue(TemperatureSensorBlock.FACING)).getCenter();
    }

    public static boolean validEndpoints(int zero, int full) {
        return SensorRangeControls.valid(zero, full);
    }

    public boolean acceptsEndpoints(int zero, int full) {
        return zero >= minimum() && zero <= maximum() && full >= minimum() && full <= maximum() && zero != full;
    }

    public boolean configure(int zero, int full) {
        if (!(level instanceof ServerLevel) || !acceptsEndpoints(zero, full)) { return false; }
        range = new SensorRange(zero / 10.0, full / 10.0);
        updateOutput();
        setChanged();
        sync();
        return true;
    }

    public void tick() {
        if (level == null) { return; }
        if (level.isClientSide) {
            previousVisual = visual;
            if (!Double.isFinite(reading) || !Double.isFinite(visual)) {
                visual = reading;
                previousVisual = reading;
            } else {
                visual += (reading - visual) * 0.2;
            }
        } else if (!sampled || Math.floorMod(level.getGameTime() + worldPosition.asLong(), ThermometerItem.INTERVAL) == 0) {
            refresh();
        }
    }

    public void refresh() {
        if (!(level instanceof ServerLevel server)) { return; }
        double next = sample(server);
        boolean changed = !sampled || Double.isFinite(next) != Double.isFinite(sentReading)
                || (Double.isFinite(next) && Math.abs(next - sentReading) >= 0.01);
        sampled = true;
        reading = next;
        updateOutput();
        if (changed || wind()) { sync(); }
    }

    private void updateOutput() {
        int strength = range.signal(reading);
        BlockState state = getBlockState();
        if (level != null && strength != state.getValue(TemperatureSensorBlock.POWER)) {
            level.setBlock(worldPosition, state.setValue(TemperatureSensorBlock.POWER, strength), 3);
            level.updateNeighborsAt(worldPosition.below(), state.getBlock());
        }
    }

    private void sync() {
        if (level != null) {
            sentReading = reading;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    public double visualReading(float partialTick) {
        return Double.isFinite(previousVisual) && Double.isFinite(visual)
                ? previousVisual + (visual - previousVisual) * partialTick : reading;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("ZeroTenths", (int) Math.round(range.zero() * 10));
        tag.putInt("FullTenths", (int) Math.round(range.full() * 10));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        int zero = tag.getInt("ZeroTenths"), full = tag.getInt("FullTenths");
        range = acceptsEndpoints(zero, full) ? new SensorRange(zero / 10.0, full / 10.0) : defaultRange();
        reading = tag.contains("Reading") ? tag.getDouble("Reading") : Double.NaN;
        readExtra(tag);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        saveAdditional(tag, registries);
        if (Double.isFinite(reading)) { tag.putDouble("Reading", reading); }
        writeExtra(tag);
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }

    @Override
    public Component getDisplayName() { return Component.translatable(wind() ? "block.meteodyne.wind_sensor" : "block.meteodyne.temperature_sensor"); }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) { return new SensorMenu(id, inventory, this); }
}
