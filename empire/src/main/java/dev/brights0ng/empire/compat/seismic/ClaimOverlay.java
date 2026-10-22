// Written by Gemini.
package dev.brights0ng.empire.compat.seismic;

import dev.brights0ng.seismicsurveyance.LoggerDisplay;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.empire.map.ClaimMapCache;
import dev.brights0ng.empire.map.ClaimMapPayloads;
import dev.brights0ng.empire.rules.ClaimGrid;
import dev.brights0ng.seismicsurveyance.client.MapLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The empire claim overlay drawn on the smart logger and the portable record display (Phase 6):
 * our empire's staked chunks receive a faint amber fill, other empires' staked chunks receive a faint
 * crimson fill, with crisp outlines where land meets unstaked chunks or other borders.
 * Active main bonfires are marked with a flame diamond.
 */
public final class ClaimOverlay implements MapLayer {

    public static final ClaimOverlay INSTANCE = new ClaimOverlay();

    /** Amber for our empire; Crimson for other empires. */
    public static final int COLOUR_OUR_EMPIRE = 0xF5A623;
    public static final int COLOUR_OTHER_EMPIRE = 0xDC3545;

    private static final int FILL_ALPHA = 0x20;
    private static final int EDGE_ALPHA = 0xD0;

    /** Main bonfire flame marker and soot outline. */
    public static final int MARKER_BONFIRE = 0xFFFFA500;
    public static final int MARKER_OUTLINE = 0xFF261204;

    public static int fill(int state) {
        int rgb = (state == ClaimGrid.OUR_EMPIRE) ? COLOUR_OUR_EMPIRE : COLOUR_OTHER_EMPIRE;
        return (FILL_ALPHA << 24) | rgb;
    }

    public static int edge(int state) {
        int rgb = (state == ClaimGrid.OUR_EMPIRE) ? COLOUR_OUR_EMPIRE : COLOUR_OTHER_EMPIRE;
        return (EDGE_ALPHA << 24) | rgb;
    }

    @Override
    public LoggerDisplay.Tab tab() {
        return LoggerDisplay.Tab.EMPIRE;
    }

    @Override
    public boolean shows(Level level) {
        return level != null && level.dimension() == Level.OVERWORLD;
    }

    @Override
    public void columns(int minX, int minZ, int maxX, int maxZ, Column column) {
        maxX = Math.min(maxX, minX + 256);
        maxZ = Math.min(maxZ, minZ + 256);
        for (int cz = minZ; cz <= maxZ; cz++) {
            for (int cx = minX; cx <= maxX; cx++) {
                int state = ClaimMapCache.claim(cx, cz);
                if (state == ClaimGrid.UNCLAIMED) {
                    continue;
                }
                int edges = ClaimGrid.edges(state,
                        ClaimMapCache.claim(cx, cz - 1),
                        ClaimMapCache.claim(cx, cz + 1),
                        ClaimMapCache.claim(cx - 1, cz),
                        ClaimMapCache.claim(cx + 1, cz));
                column.draw(cx, cz, fill(state), edge(state), sides(edges));
            }
        }
    }

    static int sides(int edges) {
        return ((edges & ClaimGrid.NORTH) != 0 ? NORTH : 0)
                | ((edges & ClaimGrid.SOUTH) != 0 ? SOUTH : 0)
                | ((edges & ClaimGrid.WEST) != 0 ? WEST : 0)
                | ((edges & ClaimGrid.EAST) != 0 ? EAST : 0);
    }

    @Override
    public List<Marker> markers() {
        List<ClaimMapPayloads.BonfireMarker> found = ClaimMapCache.bonfires();
        List<Marker> markers = new ArrayList<>(found.size());
        for (ClaimMapPayloads.BonfireMarker b : found) {
            markers.add(new Marker(b.x(), b.z(), b.ourEmpire(), MARKER_BONFIRE, MARKER_OUTLINE));
        }
        return markers;
    }

    @Override
    public List<LegendRow> legend() {
        return List.of(
                new LegendRow(Component.translatable("gui.empire.claim_map.our_empire"), 0xFF000000 | COLOUR_OUR_EMPIRE, false),
                new LegendRow(Component.translatable("gui.empire.claim_map.other_empire"), 0xFF000000 | COLOUR_OTHER_EMPIRE, false),
                new LegendRow(Component.translatable("gui.empire.claim_map.bonfire"), MARKER_BONFIRE, true)
        );
    }

    @Override
    public void want(Object key, double centreX, double centreZ, double reachBlocks) {
        int cx = (int) Math.floor(centreX) >> 4;
        int cz = (int) Math.floor(centreZ) >> 4;
        int radius = Math.min(ClaimMapPayloads.MAX_RADIUS, (int) Math.ceil(reachBlocks / 16) + 1);
        if (ClaimMapCache.shouldAsk(key, cx, cz, radius, System.currentTimeMillis())) {
            PacketDistributor.sendToServer(new ClaimMapPayloads.Request(cx, cz, radius));
        }
    }

    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClaimMapCache.clear();
    }

    private ClaimOverlay() {
    }
}
