package dev.brights0ng.enginesandempires.geophone.client;

import java.util.List;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.frontier.map.TierGrid;
import dev.brights0ng.enginesandempires.frontier.map.TierMapCache;
import dev.brights0ng.enginesandempires.frontier.map.TierMapPayloads;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The tier overlay the smart logger and the portable record display draw under their readings (Frontier phase 7): each
 * chunk column better than Frontier gets a very faint fill in its tier's colour, and an outline where it meets a lower
 * tier. Frontier itself is left clear. Incursions under way show as a diamond. A small legend explains the colours.
 *
 * <p>This holds what both maps share: the colours, walking the visible columns, and asking the server for them.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID, value = Dist.CLIENT)
public final class TierOverlay {

    /** The tiers' colours, as {@code /eae frontier map} shows them: yellow Uninhabited, green Settled, blue Civilized. */
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
        return Component.translatable("gui.engines_and_empires.tier_map." + LEGEND_KEYS[i]);
    }

    /** Only the Overworld has tiers. */
    public static boolean shows(Level level) {
        return level != null && level.dimension() == Level.OVERWORLD;
    }

    /** Something to draw each tinted column with: its chunk, tier and which sides get an outline ({@link TierGrid} bits). */
    public interface Column {
        void draw(int cx, int cz, int tier, int edges);
    }

    /** Calls {@code column} for every tinted column from chunk (minX, minZ) to (maxX, maxZ), at most 256 across. */
    public static void visit(int minX, int minZ, int maxX, int maxZ, Column column) {
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
                column.draw(cx, cz, tier, edges);
            }
        }
    }

    public static List<TierMapPayloads.Marker> markers() {
        return TierMapCache.markers();
    }

    /**
     * Asks the server for the columns around a map's middle, if it is due (see
     * {@link dev.brights0ng.enginesandempires.frontier.map.TierRequests}). {@code key} tells maps apart.
     */
    public static void want(Object key, double centreX, double centreZ, double reachBlocks) {
        int cx = (int) Math.floor(centreX) >> 4;
        int cz = (int) Math.floor(centreZ) >> 4;
        int radius = Math.min(TierMapPayloads.MAX_RADIUS, (int) Math.ceil(reachBlocks / 16) + 1);
        if (TierMapCache.shouldAsk(key, cx, cz, radius, System.currentTimeMillis())) {
            PacketDistributor.sendToServer(new TierMapPayloads.Request(cx, cz, radius));
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TierMapCache.clear();
    }

    private TierOverlay() {
    }
}
