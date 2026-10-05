package dev.brights0ng.enginesandempires.weather.cloud.client;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Sets up the voxel cloud renderer on the client: its config, events and {@code /eae clouds} commands. */
public final class CloudClient {

    public static void init(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, CloudConfig.SPEC, CloudConfig.FILE_NAME);
        modBus.addListener(CloudClient::onConfigLoad);
        modBus.addListener(CloudClient::onConfigReload);
        modBus.addListener(CloudRenderer::registerShaders);
        NeoForge.EVENT_BUS.addListener(CloudClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(CloudRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener(CloudClient::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(CloudClient::onRegisterCommands);
        dev.brights0ng.enginesandempires.weather.fog.client.FogEffects.init();
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        CloudTracker.tick();
        // The shared rain query reads the clouds where they are drawn.
        dev.brights0ng.enginesandempires.weather.rain.LocalWeather.setClient(CloudTracker.clouds(), level.getGameTime(),
                TRACKED, level.dimension().location().toString());
        dev.brights0ng.enginesandempires.weather.rain.client.ClientSky.tick(level);
        dev.brights0ng.enginesandempires.weather.rain.client.LocalRainRenderer.tick(level);
        if (!CloudRenderer.active(level)) {
            CloudMeshes.clear();
            return;
        }
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        CloudMeshes.tick(level.getGameTime(), camera);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            CloudMeshes.clear();
            dev.brights0ng.enginesandempires.weather.rain.LocalWeather.clearClient();
            dev.brights0ng.enginesandempires.weather.rain.ClientWeather.clear();
            dev.brights0ng.enginesandempires.weather.rain.client.ClientSky.clear();
            dev.brights0ng.enginesandempires.weather.rain.client.LocalRainRenderer.clear();
        }
    }

    /** Cloud positions as the renderer draws them (smoothed), for the rain query. */
    private static final dev.brights0ng.enginesandempires.weather.rain.RainModel.Positions TRACKED =
            new dev.brights0ng.enginesandempires.weather.rain.RainModel.Positions() {
                @Override
                public double x(CloudShape c, double t) {
                    return CloudTracker.x(c, t);
                }

                @Override
                public double z(CloudShape c, double t) {
                    return CloudTracker.z(c, t);
                }

                @Override
                public double vx(CloudShape c) {
                    return CloudTracker.vx(c);
                }

                @Override
                public double vz(CloudShape c) {
                    return CloudTracker.vz(c);
                }
            };

    private static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == CloudConfig.SPEC) {
            CloudConfig.apply();
        }
    }

    /** The client config file is watched: edits apply live, and changed shapes rebuild every storm. */
    private static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == CloudConfig.SPEC) {
            CloudConfig.apply();
        }
    }

    private static void onRegisterCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("eae").then(Commands.literal("clouds")
                .then(Commands.literal("outlines").executes(ctx -> {
                    boolean on = !CloudConfig.outlines();
                    CloudConfig.setOutlines(on);
                    ctx.getSource().sendSuccess(() -> Component.literal("Cloud outlines " + (on ? "on" : "off")), false);
                    return 1;
                }))
                .then(Commands.literal("info").executes(ctx -> {
                    long[] s = CloudMeshes.stats();
                    String text = String.format(
                            "Clouds: %d clusters in %d formations (source: %s), %d drawn in %d sections, %d quads. Rebuilding: %d sections to go (running %.1f s), last full rebuild %.1f s, slowest section %d ms, mesher load %d%% of a core (budget %.0f%%). Voxel %d, section %d, draw %d, renderer %s.",
                            CloudTracker.clouds().size(), CloudTracker.formations().size(),
                            dev.brights0ng.enginesandempires.weather.cloud.CloudSources.describe(),
                            s[0], s[1], s[2], s[3], s[7] / 1000.0, s[6] / 1000.0, s[4], s[5],
                            CloudTuning.rebuildBudget * 100,
                            CloudConfig.voxelSize(), CloudTuning.sectionSize, CloudConfig.drawDistance(),
                            CloudRenderer.active(Minecraft.getInstance().level) ? "on" : "off");
                    ctx.getSource().sendSuccess(() -> Component.literal(text), false);
                    for (String diag : CloudMeshes.diagnostics()) {
                        ctx.getSource().sendSuccess(() -> Component.literal(diag), false);
                    }
                    for (CloudFormation f : CloudTracker.formations()) {
                        CloudShape c = f.anchor();
                        String line = String.format("  %s %s x%d at %.0f %.0f, reach %.0f, y %.0f-%.0f, cover %.2f, v %.3f %.3f b/t, formed %.0f%% eroded %.0f%% anvil %.0f%%",
                                c.typeId(), f.stormTier(), f.members().size(), c.cx(), c.cz(), f.reach(), c.baseY(),
                                c.topY(), c.effectiveCoverage(), c.vx(), c.vz(), 100 * c.growth(), 100 * c.decay(),
                                100 * c.anvilDecay());
                        ctx.getSource().sendSuccess(() -> Component.literal(line), false);
                    }
                    return 1;
                }))));
    }

    private CloudClient() {
    }
}
