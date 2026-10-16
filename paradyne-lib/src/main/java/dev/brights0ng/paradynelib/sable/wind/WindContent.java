package dev.brights0ng.paradynelib.sable.wind;

import dev.brights0ng.paradynelib.ParadyneLib;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The wind push's registrations: its Sable force group, its physics-step listener and its debug command. Called by
 * {@code ParadyneLib} only when Sable is loaded.
 */
public final class WindContent {

    public static final DeferredRegister<ForceGroup> FORCE_GROUPS =
            DeferredRegister.create(ForceGroups.REGISTRY_KEY, ParadyneLib.MODID);

    /** Wind's own force group, so Simulated's contraption diagram draws it apart from drag and lift. */
    public static final DeferredHolder<ForceGroup, ForceGroup> WIND = FORCE_GROUPS.register("wind",
            () -> new ForceGroup(Component.translatable("force_group.paradyne_lib.wind"),
                    Component.translatable("force_group.paradyne_lib.wind.description"), 0xB8E0F6, true));

    public static void register(IEventBus modEventBus) {
        FORCE_GROUPS.register(modEventBus);
        NeoForge.EVENT_BUS.addListener(WindPhysics::onPrePhysicsTick);
        NeoForge.EVENT_BUS.addListener(WindCommand::onRegisterCommands);
    }

    private WindContent() {
    }
}
