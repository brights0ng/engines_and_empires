package dev.brights0ng.enginesandempires.weather.wind;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The wind push's registrations: its Sable force group, and its physics-step listener.
 */
public final class WindContent {

    public static final DeferredRegister<ForceGroup> FORCE_GROUPS =
            DeferredRegister.create(ForceGroups.REGISTRY_KEY, EnginesAndEmpiresMod.MODID);

    /** Wind's own force group, so Simulated's contraption diagram draws it apart from drag and lift. */
    public static final DeferredHolder<ForceGroup, ForceGroup> WIND = FORCE_GROUPS.register("wind",
            () -> new ForceGroup(Component.translatable("force_group.engines_and_empires.wind"),
                    Component.translatable("force_group.engines_and_empires.wind.description"), 0xB8E0F6, true));

    public static void register(IEventBus modEventBus) {
        FORCE_GROUPS.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(WindPhysics::onPrePhysicsTick);
    }

    private WindContent() {
    }
}
