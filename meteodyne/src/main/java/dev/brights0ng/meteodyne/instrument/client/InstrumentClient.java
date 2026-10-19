// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument.client;

import java.util.Map;
import java.util.WeakHashMap;
import dev.brights0ng.meteodyne.Meteodyne;
import dev.brights0ng.meteodyne.compat.simulated.client.SimulatedSensorScreen;
import dev.brights0ng.meteodyne.instrument.InstrumentContent;
import dev.brights0ng.meteodyne.instrument.InstrumentReading;
import dev.brights0ng.meteodyne.instrument.ThermometerItem;
import dev.brights0ng.meteodyne.instrument.WindReading;
import dev.brights0ng.meteodyne.instrument.WindGaugeTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/** Client registrations and per-viewer display easing, surviving synchronized stack replacements. */
@EventBusSubscriber(modid = Meteodyne.MODID, value = Dist.CLIENT)
public final class InstrumentClient {
    public static final int FRAMES = ThermometerItem.STEPS;
    private static final Map<Entity, Display> DISPLAYS = new WeakHashMap<>();
    private static final Map<Entity, WindDisplay> WIND_DISPLAYS = new WeakHashMap<>();
    private static final class WindDisplay {
        double angle, rotor, speed;
        long tick;
        String dimension;
        WindDisplay(double angle, double speed, long tick, String dimension) {
            this.angle = angle; this.speed = speed; this.tick = tick; this.dimension = dimension;
        }
    }
    private static final class Display {
        double value;
        long tick;
        String dimension;
        Display(double value, long tick, String dimension) { this.value = value; this.tick = tick; this.dimension = dimension; }
    }

    @SubscribeEvent
    static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(InstrumentContent.WIND_GAUGE.get(), Meteodyne.id("wind"),
                (stack, level, entity, seed) -> windFrame(stack, level, entity)));
        event.enqueueWork(() -> ItemProperties.register(InstrumentContent.THERMOMETER.get(), Meteodyne.id("temperature"),
                (stack, level, entity, seed) -> frame(stack, level, entity)));
    }

    private static float frame(ItemStack stack, ClientLevel suppliedLevel, LivingEntity holder) {
        ClientLevel level = suppliedLevel != null ? suppliedLevel : Minecraft.getInstance().level;
        Entity viewer = holder != null ? holder : stack.getEntityRepresentation();
        if (viewer == null) { viewer = Minecraft.getInstance().player; }
        InstrumentReading reading = stack.get(InstrumentContent.READING.get());
        if (level == null || reading == null || !reading.usable(level.dimension().location().toString(), level.getGameTime())) {
            return 0;
        }
        if (viewer == null) { return 1 + (int) Math.round(ThermometerItem.DIAL.fraction(reading.value()) * FRAMES); }
        long tick = level.getGameTime();
        String dimension = level.dimension().location().toString();
        Display display = DISPLAYS.get(viewer);
        if (display == null || !dimension.equals(display.dimension) || tick < display.tick || tick - display.tick > 60) {
            display = new Display(reading.value(), tick, dimension);
            DISPLAYS.put(viewer, display);
        }
        if (tick != display.tick) {
            long dt = Math.clamp(tick - display.tick, 1, 60);
            display.value += (reading.value() - display.value) * (1 - Math.pow(0.8, dt));
            display.tick = tick;
        }
        return 1 + (int) Math.round(ThermometerItem.DIAL.fraction(display.value) * FRAMES);
    }

    private static float windFrame(ItemStack stack, ClientLevel suppliedLevel, LivingEntity holder) {
        ClientLevel level = suppliedLevel != null ? suppliedLevel : Minecraft.getInstance().level;
        Entity viewer = holder != null ? holder : stack.getEntityRepresentation();
        if (viewer == null) { viewer = Minecraft.getInstance().player; }
        WindReading reading = stack.get(InstrumentContent.WIND_READING.get());
        if (level == null || reading == null || !reading.usable(level.dimension().location().toString(), level.getGameTime())) { return 0; }
        if (reading.calm()) { return 1; }
        double heading = viewer == null ? 180 : viewer.getYRot();
        double target = reading.relativeAngle(heading);
        long tick = level.getGameTime();
        String dimension = level.dimension().location().toString();
        WindDisplay display = viewer == null ? null : WIND_DISPLAYS.get(viewer);
        if (display == null || !dimension.equals(display.dimension) || tick < display.tick || tick - display.tick > 60) {
            display = new WindDisplay(target, reading.speed(), tick, dimension);
            if (viewer != null) { WIND_DISPLAYS.put(viewer, display); }
        }
        if (tick != display.tick) {
            long dt = Math.clamp(tick - display.tick, 1, 60);
            double ease = 1 - Math.pow(0.75, dt);
            display.angle += WindReading.wrap(target - display.angle) * ease;
            display.speed += (reading.speed() - display.speed) * ease;
            display.rotor = (display.rotor + WindReading.rotorStep(display.speed) * dt) % 90;
            display.tick = tick;
        }
        int direction = Math.floorMod((int)Math.round(display.angle / 360 * WindGaugeTextures.DIRECTIONS), WindGaugeTextures.DIRECTIONS);
        int rotor = (int)(display.rotor / 90 * WindGaugeTextures.ROTOR_FRAMES) % WindGaugeTextures.ROTOR_FRAMES;
        return 2 + direction * WindGaugeTextures.ROTOR_FRAMES + rotor;
    }

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(InstrumentContent.TEMPERATURE_SENSOR_ENTITY.get(), TemperatureSensorRenderer::new);
        event.registerBlockEntityRenderer(InstrumentContent.WIND_SENSOR_ENTITY.get(), WindSensorRenderer::new);
    }

    @SubscribeEvent
    static void screens(RegisterMenuScreensEvent event) {
        if (ModList.get().isLoaded("simulated")) {
            event.register(InstrumentContent.SENSOR_MENU.get(), SimulatedSensorScreen::new);
        } else {
            event.register(InstrumentContent.SENSOR_MENU.get(), SensorScreen::new);
        }
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) { DISPLAYS.clear(); WIND_DISPLAYS.clear(); }

    private InstrumentClient() { }
}
