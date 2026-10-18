package dev.brights0ng.frontier.seismic.client;

import java.util.ArrayList;
import java.util.List;

import dev.brights0ng.frontier.map.TierGrid;
import dev.brights0ng.frontier.map.TierMapCache;
import dev.brights0ng.frontier.map.TierMapPayloads;
import dev.brights0ng.seismicsurveyance.client.MapLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The tier overlay the smart logger and the portable record display draw under their readings (Frontier phase 7): each
 * chunk column better than Frontier gets a very faint fill in its tier's colour, and an outline where it meets a lower
 * tier. Frontier itself is left clear. Incursions under way show as a diamond. A small legend explains the colours.
 *
 * <p>A layer on Seismic Surveyance's maps (split seam S8): this holds what both maps draw of it (the colours, the
 * visible columns, the markers and legend) and asks the server for the columns. Frontier registers it itself when
 * Seismic Surveyance is loaded ({@link FrontierSeismicClient}); until split step 4b the core did, so Frontier and Seismic
 * without the core drew no tier map. Not an {@code @EventBusSubscriber}: FML would load it, and with it Seismic's
 * {@code MapLayer}, whether Seismic is there or not, so its one listener is added by {@link FrontierSeismicClient}.
 */
public final class TierOverlay implements MapLayer {

    public static final TierOverlay INSTANCE = new TierOverlay();

    /** The tiers' colours, as {@code /frontier map} shows them: yellow Uninhabited, green Settled, blue Civilized. */
    private static final int[] COLOURS = {0x000000, 0xF2D91A, 0x33D933, 0x3399FF};
    private static final int FILL_ALPHA = 0x1A;
    private static final int EDGE_ALPHA = 0xC8;
    /** An incursion's marker: a bright sculk-violet diamond. */
    public static final int MARKER = 0xFFD04BE0;
    public static final int MARKER_OUTLINE = 0xFF1A0C1E;

    /** The rows of the legend, top to bottom: {colour, name}. The last is the incursion marker. */
    public static final String[] LEGEND_KEYS = {"uninhabited", "settled", "civilized", "incursion"};

    /** The faint fill of a column of this tier, with alpha. */
    public static int fill(int tier) {
        return (FILL_ALPHA << 24) | COLOURS[tier];
    }

    /** The outline of a column of this tier, with alpha. */
    public static int edge(int tier) {
        return (EDGE_ALPHA << 24) | COLOURS[tier];
    }

    /** The legend row {@code i}'s swatch colour, solid. */
    public static int legendColour(int i) {
        return i < 3 ? 0xFF000000 | COLOURS[i + 1] : MARKER;
    }

    public static Component legendName(int i) {
        return Component.translatable("gui.frontier.tier_map." + LEGEND_KEYS[i]);
    }

    /** Only the Overworld has tiers. */
    @Override
    public boolean shows(Level level) {
        return level != null && level.dimension() == Level.OVERWORLD;
    }

    /** Calls {@code column} for every tinted column from chunk (minX, minZ) to (maxX, maxZ), at most 256 across. */
    @Override
    public void columns(int minX, int minZ, int maxX, int maxZ, Column column) {
        maxX = Math.min(maxX, minX + 256);
        maxZ = Math.min(maxZ, minZ + 256);
        for (int cz = minZ; cz <= maxZ; cz++) {
            for (int cx = minX; cx <= maxX; cx++) {
                int tier = TierMapCache.tier(cx, cz);
                if (tier <= 0) {
                    continue;
                }
                int edges = TierGrid.edges(tier, TierMapCache.tier(cx, cz - 1), TierMapCache.tier(cx, cz + 1),
                        TierMapCache.tier(cx - 1, cz), TierMapCache.tier(cx + 1, cz));
                column.draw(cx, cz, fill(tier), edge(tier), sides(edges));
            }
        }
    }

    /** {@link TierGrid}'s outline bits as the map's. */
    static int sides(int edges) {
        return ((edges & TierGrid.NORTH) != 0 ? NORTH : 0) | ((edges & TierGrid.SOUTH) != 0 ? SOUTH : 0)
                | ((edges & TierGrid.WEST) != 0 ? WEST : 0) | ((edges & TierGrid.EAST) != 0 ? EAST : 0);
    }

    /** Incursions under way: kind 1 (greater) a size bigger. */
    @Override
    public List<Marker> markers() {
        List<TierMapPayloads.Marker> found = TierMapCache.markers();
        List<Marker> markers = new ArrayList<>(found.size());
        for (TierMapPayloads.Marker marker : found) {
            markers.add(new Marker(marker.x(), marker.z(), marker.kind() == 1, MARKER, MARKER_OUTLINE));
        }
        return markers;
    }

    @Override
    public List<LegendRow> legend() {
        List<LegendRow> rows = new ArrayList<>(LEGEND_KEYS.length);
        for (int i = 0; i < LEGEND_KEYS.length; i++) {
            rows.add(new LegendRow(legendName(i), legendColour(i), i == LEGEND_KEYS.length - 1));
        }
        return rows;
    }

    /**
     * Asks the server for the columns around a map's middle, if it is due (see
     * {@link dev.brights0ng.frontier.map.TierRequests}). {@code key} tells maps apart.
     */
    @Override
    public void want(Object key, double centreX, double centreZ, double reachBlocks) {
        int cx = (int) Math.floor(centreX) >> 4;
        int cz = (int) Math.floor(centreZ) >> 4;
        int radius = Math.min(TierMapPayloads.MAX_RADIUS, (int) Math.ceil(reachBlocks / 16) + 1);
        if (TierMapCache.shouldAsk(key, cx, cz, radius, System.currentTimeMillis())) {
            PacketDistributor.sendToServer(new TierMapPayloads.Request(cx, cz, radius));
        }
    }

    /** Forgets the columns when the player leaves the world. */
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TierMapCache.clear();
    }

    private TierOverlay() {
    }
}
