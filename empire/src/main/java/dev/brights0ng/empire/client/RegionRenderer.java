// Written by Gemini.
package dev.brights0ng.empire.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import dev.brights0ng.empire.ledger.AdministrativeLedgerItem;
import dev.brights0ng.empire.region.RegionClientCache;
import dev.brights0ng.empire.region.RegionNetwork;
import dev.brights0ng.empire.region.RegionPayloads;
import dev.brights0ng.empire.rules.RegionType;
import dev.brights0ng.empire.rules.RoomLabel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Draws the player's empire's nearby 3D regions while the player holds the {@link AdministrativeLedgerItem}:
 * chalk ribbons along the geometric edges of each region and shimmering translucent veils across its faces.
 * Shares {@link VeilMeshRenderer} and {@link EmpireRenderTypes#BORDER_VEIL} with {@link TalcBorderRenderer}.
 * Also listens for client-only {@link PlayerInteractEvent.LeftClickEmpty} to exit region selection when punching air.
 */
public final class RegionRenderer {

    private static List<VeilMesh> meshes = List.of();
    private static ClientLevel meshLevel;
    private static int meshRevision = -1;

    /** Hooks up rendering, region-sync requests, left-click air deselection and logout cleanup. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(RegionRenderer::onRenderStage);
        NeoForge.EVENT_BUS.addListener(RegionRenderer::onClientTick);
        NeoForge.EVENT_BUS.addListener(RegionRenderer::onLeftClickEmpty);
        NeoForge.EVENT_BUS.addListener(RegionRenderer::onLoggingOut);
    }

    private static void onLeftClickEmpty(PlayerInteractEvent.LeftClickEmpty event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !mc.level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        if (isHoldingLedger(player) && RegionClientCache.selectedRegionId() != null) {
            RegionClientCache.clearSelectionLocally();
            PacketDistributor.sendToServer(RegionPayloads.Deselect.INSTANCE);
        }
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.getConnection() == null
                || !mc.level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        if (!isHoldingLedger(player)) {
            RegionClientCache.clearSelectionLocally();
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
            meshes = buildMeshes(RegionClientCache.regions(), RegionClientCache.selectedRegionId());
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

    private static List<VeilMesh> buildMeshes(List<RegionPayloads.RegionSnapshot> snapshots,
                                              @Nullable UUID selectedRegionId) {
        if (snapshots.isEmpty()) {
            return List.of();
        }
        List<VeilMesh> built = new ArrayList<>(snapshots.size());
        for (RegionPayloads.RegionSnapshot snap : snapshots) {
            boolean selected = selectedRegionId != null && selectedRegionId.equals(snap.id());
            if (snap.type() == RegionType.FARM && snap.valid()) {
                if (snap.blocks().length > 0) {
                    int shedStyle = RegionGeometry.styleFor(RegionType.FARM, RoomLabel.SHED, true, selected);
                    VeilMesh shedMesh = RegionGeometry.build(snap.blocks(), shedStyle, snap.originX(), snap.originZ());
                    if (shedMesh.quadCount() > 0) {
                        built.add(shedMesh);
                    }
                }
                if (snap.secondaryBlocks().length > 0) {
                    int fieldStyle = RegionGeometry.styleFor(RegionType.FARM, RoomLabel.FIELD, true, selected);
                    VeilMesh fieldMesh = RegionGeometry.build(
                            snap.secondaryBlocks(), fieldStyle, snap.originX(), snap.originZ());
                    if (fieldMesh.quadCount() > 0) {
                        built.add(fieldMesh);
                    }
                }
            } else if (snap.blocks().length > 0) {
                int style = RegionGeometry.styleFor(snap.type(), null, snap.valid(), selected);
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

    /** Chalk RGB in out[0..2], veil RGB in out[3..5]. Each region/room type and selection state has its own palette entry. */
    private static void colors(int style, int[] out) {
        switch (style) {
            case RegionGeometry.STYLE_SEPARATOR -> {
                // Room separator blocks: burnished gold/yellow matching Empire protected boundaries
                set(out, 255, 200, 55, 255, 215, 90);
                return;
            }
            case RegionGeometry.STYLE_INVALID -> {
                // Invalid region: crimson warning
                set(out, 220, 53, 69, 255, 82, 82);
                return;
            }
            case RegionGeometry.STYLE_INVALID_SELECTED -> {
                // Selected invalid region: bright coral-crimson
                set(out, 255, 140, 140, 255, 110, 110);
                return;
            }
            case RegionGeometry.STYLE_HOME_SELECTED -> {
                // Selected HOME: luminous sky blue
                set(out, 185, 230, 255, 140, 210, 255);
                return;
            }
            case RegionGeometry.STYLE_FARM_SHED -> {
                // Farm SHED: warm amber/ochre
                set(out, 225, 160, 65, 215, 150, 60);
                return;
            }
            case RegionGeometry.STYLE_FARM_SHED_SELECTED -> {
                // Selected Farm SHED: bright golden-amber
                set(out, 255, 220, 130, 245, 190, 90);
                return;
            }
            case RegionGeometry.STYLE_FARM_FIELD -> {
                // Farm FIELD: fresh meadow green
                set(out, 85, 200, 95, 95, 210, 105);
                return;
            }
            case RegionGeometry.STYLE_FARM_FIELD_SELECTED -> {
                // Selected Farm FIELD: bright vivid green
                set(out, 175, 255, 175, 140, 245, 140);
                return;
            }
            default -> {
            }
        }
        RegionType type = RegionGeometry.typeForStyle(style);
        if (type == null) {
            set(out, 220, 53, 69, 255, 82, 82);
            return;
        }
        switch (type) {
            case HOME -> set(out, 90, 175, 255, 110, 190, 255);
            case FARM -> set(out, 85, 200, 95, 95, 210, 105);
        }
    }

    private static void set(int[] out, int... values) {
        System.arraycopy(values, 0, out, 0, values.length);
    }

    private RegionRenderer() {
    }
}
