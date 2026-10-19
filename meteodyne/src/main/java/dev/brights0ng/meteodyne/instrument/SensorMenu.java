// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;

/** A range configuration menu. Endpoint values and its live measurement use tenths, fitting vanilla menu sync. */
public final class SensorMenu extends AbstractContainerMenu {
    private static final int UNKNOWN = Short.MIN_VALUE;
    private final SensorBlockEntity sensor;
    private final ContainerData data;

    public SensorMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, (SensorBlockEntity) inventory.player.level().getBlockEntity(buffer.readBlockPos()), emptyData());
    }

    private static ContainerData emptyData() {
        SimpleContainerData data = new SimpleContainerData(3);
        data.set(2, UNKNOWN);
        return data;
    }

    public SensorMenu(int id, Inventory inventory, SensorBlockEntity sensor) {
        this(id, inventory, sensor, new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> (int) Math.round(sensor.range().zero() * 10);
                    case 1 -> (int) Math.round(sensor.range().full() * 10);
                    default -> Double.isFinite(sensor.reading())
                            ? (int) Math.clamp(Math.round(sensor.reading() * 10), Short.MIN_VALUE + 1, Short.MAX_VALUE) : UNKNOWN;
                };
            }
            @Override public void set(int index, int value) { }
            @Override public int getCount() { return 3; }
        });
    }

    private SensorMenu(int id, Inventory inventory, SensorBlockEntity sensor, ContainerData data) {
        super(InstrumentContent.SENSOR_MENU.get(), id);
        this.sensor = sensor;
        this.data = data;
        addDataSlots(data);
    }

    public int minimum() { return sensor == null ? SensorRangeControls.MIN : sensor.minimum(); }
    public int maximum() { return sensor == null ? SensorRangeControls.MAX : sensor.maximum(); }
    public boolean wind() { return sensor != null && sensor.wind(); }
    public String unit() { return sensor == null ? "°C" : sensor.unit(); }

    public int zeroTenths() { return data.get(0); }
    public int fullTenths() { return data.get(1); }
    public double reading() { return data.get(2) == UNKNOWN ? Double.NaN : data.get(2) / 10.0; }

    public void configure(Player player, int zero, int full) {
        if (stillValid(player) && player.mayBuild() && sensor != null) { sensor.configure(zero, full); }
    }

    @Override
    public boolean stillValid(Player player) {
        if (player.level().isClientSide) { return sensor != null && !sensor.isRemoved(); }
        return sensor != null && !sensor.isRemoved() && player.level() == sensor.getLevel()
                && InstrumentReadings.withinReach((ServerLevel) player.level(), player, sensor.getBlockPos());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
