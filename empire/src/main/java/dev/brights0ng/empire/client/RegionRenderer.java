// Written by Gemini.
package dev.brights0ng.empire.client;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.empire.ledger.AdministrativeLedgerItem;
import dev.brights0ng.empire.region.RegionClientCache;
import dev.brights0ng.empire.region.RegionNetwork;
import dev.brights0ng.empire.region.RegionPayloads;
import dev.brights0ng.empire.rules.RegionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws the player's empire's nearby 3D regions while the player holds the {@link AdministrativeLedgerItem}:
 * chalk ribbons along the geometric edges of each region and shimmering translucent veils across its faces.
 * Shares {@link VeilMeshRenderer} and {@link EmpireRenderTypes#BORDER_VEIL} with {@link TalcBorderRenderer}.
 */
public final class RegionRenderer {

    private static List<VeilMesh> meshes = List.of();
    private static ClientLevel meshLevel;
    private static int meshRevision = -1;

    /** Hooks up rendering, region-sync requests and logout cleanup. Called once from {@link dev.brights0ng.empire.EmpireClient}. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(RegionRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener(RegionRenderer::onClientTick);
        NeoForge.EVENT_BUS.addListener(RegionRenderer::onLoggingOut);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.getConnection() == null
                || !mc.level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        if (!isHoldingLedger(player)) {
            return;
        }
        int pcx = player.blockPosition().getX() >> 4;
        int pcz = player.blockPosition().getZ() >> 4;
        if (RegionClientCache.shouldAsk(pcx, pcz, RegionNetwork.RENDER_RADIUS, System.currentTimeMillis())) {
            PacketDistributor.sendToServer(new RegionPayloads.Request(pcx, pcz, RegionNetwork.RENDER_RADIUS));
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RegionClientCache.clear();
        clearMeshes();
    }

    private static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || !level.dimension().equals(Level.OVERWORLD)) {
            clearMeshes();
            return;
        }

        if (!isHoldingLedger(player)) {
            clearMeshes();
            return;
        }

        int revision = RegionClientCache.revision();
        if (meshLevel != level || meshRevision != revision) {
            meshes = buildMeshes(RegionClientCache.regions());
            meshLevel = level;
            meshRevision = revision;
        }
        if (meshes.isEmpty()) {
            return;
        }

        float time = VeilMeshRenderer.animationTime(level.getGameTime(), event);
        VeilMeshRenderer.draw(event, mc, meshes, time, RegionRenderer::colors);
    }

    private static boolean isHoldingLedger(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        return main.getItem() instanceof AdministrativeLedgerItem || off.getItem() instanceof AdministrativeLedgerItem;
    }

    private static List<VeilMesh> buildMeshes(List<RegionPayloads.RegionSnapshot> snapshots) {
        if (snapshots.isEmpty()) {
            return List.of();
        }
        List<VeilMesh> built = new ArrayList<>(snapshots.size());
        for (RegionPayloads.RegionSnapshot snap : snapshots) {
            if (snap.blocks().length > 0) {
                int style = RegionGeometry.styleFor(snap.type(), snap.valid());
                VeilMesh mesh = RegionGeometry.build(snap.blocks(), style, snap.originX(), snap.originZ());
                if (mesh.quadCount() > 0) {
                    built.add(mesh);
                }
            }
            if (snap.separators().length > 0) {
                VeilMesh sepMesh = RegionGeometry.build(
                        snap.separators(), RegionGeometry.STYLE_SEPARATOR, snap.originX(), snap.originZ());
                if (sepMesh.quadCount() > 0) {
                    built.add(sepMesh);
                }
            }
        }
        return built;
    }

    private static void clearMeshes() {
        meshes = List.of();
        meshLevel = null;
        meshRevision = -1;
    }

    /** Chalk RGB in out[0..2], veil RGB in out[3..5]. Each {@link RegionType} has its own palette entry. */
    private static void colors(int style, int[] out) {
        if (style == RegionGeometry.STYLE_SEPARATOR) {
            // Room separator blocks: burnished gold/yellow matching Empire protected boundaries
            set(out, 255, 200, 55, 255, 215, 90);
            return;
        }
        RegionType type = RegionGeometry.typeForStyle(style);
        if (type == null) {
            // Invalid region: crimson warning
            set(out, 220, 53, 69, 255, 82, 82);
            return;
        }
        switch (type) {
            case HOME -> set(out, 90, 175, 255, 110, 190, 255);
        }
    }

    private static void set(int[] out, int... values) {
        System.arraycopy(values, 0, out, 0, values.length);
    }

    private RegionRenderer() {
    }
}
