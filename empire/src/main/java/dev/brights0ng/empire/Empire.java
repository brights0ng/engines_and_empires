// Written by Gemini; gods, config, Simple Spoilage compat, levitite drops, divine protection, the gods' hunger and civilian chunk loading registered by Claude.
package dev.brights0ng.empire;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.brights0ng.empire.bonfire.BonfireEvents;
import dev.brights0ng.empire.civilian.CivilianChunkLoading;
import dev.brights0ng.empire.compat.frontier.FrontierCompat;
import dev.brights0ng.empire.compat.simplespoilage.SimpleSpoilageCompat;
import dev.brights0ng.empire.compat.sparseores.SparseOresCompat;
import dev.brights0ng.empire.god.GodHunger;
import dev.brights0ng.empire.god.Gods;
import dev.brights0ng.empire.loot.LevititeDrops;
import dev.brights0ng.empire.protect.DivineProtection;
import dev.brights0ng.empire.talc.TalcInteractions;
import dev.brights0ng.paradynelib.gametest.GameTestStructures;
import net.minecraft.data.DataGenerator;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.event.GatherDataEvent;

@Mod(Empire.MODID)
public final class Empire {

    public static final String MODID = "empire";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Empire(IEventBus modEventBus, ModContainer modContainer) {
        init(modEventBus, modContainer);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void init(IEventBus modEventBus, ModContainer modContainer) {
        EmpireContent.register(modEventBus);
        FrontierCompat.registerIfLoaded();
        SparseOresCompat.registerIfLoaded();
        SimpleSpoilageCompat.registerIfLoaded();
        modContainer.registerConfig(ModConfig.Type.SERVER, EmpireConfig.SPEC);
        NeoForge.EVENT_BUS.register(BonfireEvents.class);
        NeoForge.EVENT_BUS.register(TalcInteractions.class);
        NeoForge.EVENT_BUS.register(DivineProtection.class);
        NeoForge.EVENT_BUS.register(GodHunger.class);
        NeoForge.EVENT_BUS.addListener(Gods::onAddReloadListeners);
        NeoForge.EVENT_BUS.addListener(LevititeDrops::onBlockDrops);
        modEventBus.addListener(Empire::gatherData);
        modEventBus.addListener(CivilianChunkLoading::registerTicketControllers);
    }

    private static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        generator.addProvider(event.includeServer(), new GameTestStructures(generator.getPackOutput(), MODID));
    }
}
