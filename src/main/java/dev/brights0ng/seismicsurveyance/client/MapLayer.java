package dev.brights0ng.seismicsurveyance.client;

import java.util.List;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * Something drawn on the survey's maps besides the readings (split seam S8): the smart logger's screen and the portable
 * record display draw every registered layer ({@link MapLayers}) that shows in the level: tinted chunk columns under
 * the grid, markers over the readings, and rows in the legend. Seismic Surveyance has none of its own; the pack
 * registers Frontier's tiers. Client side only.
 */
public interface MapLayer {

    /** Which sides of a column get an outline (the bits {@link Column#draw} is given). */
    int NORTH = 1;
    int SOUTH = 2;
    int WEST = 4;
    int EAST = 8;

    /** Whether the layer has anything to draw on maps in {@code level}. */
    boolean shows(Level level);

    /**
     * A map around ({@code centreX}, {@code centreZ}), reaching {@code reachBlocks} from its middle, is in view of the
     * player: ask for what it needs, if that is due. {@code key} tells maps apart.
     */
    void want(Object key, double centreX, double centreZ, double reachBlocks);

    /** Something to draw each tinted column with: its chunk, its fill and outline colours (with alpha), and which sides get the outline. */
    interface Column {
        void draw(int cx, int cz, int fill, int edge, int edges);
    }

    /** Calls {@code column} for every tinted chunk column from chunk (minX, minZ) to (maxX, maxZ). */
    void columns(int minX, int minZ, int maxX, int maxZ, Column column);

    /**
     * A diamond on the map over block column ({@code x}, {@code z}).
     *
     * @param large   drawn a size bigger
     * @param colour  its fill, with alpha
     * @param outline the ring around it, with alpha
     */
    record Marker(int x, int z, boolean large, int colour, int outline) {
    }

    /** The markers to draw now. */
    List<Marker> markers();

    /**
     * A row of the legend.
     *
     * @param colour the swatch's colour, solid
     * @param marker whether the swatch is a marker's shape rather than a square
     */
    record LegendRow(Component name, int colour, boolean marker) {
    }

    /** The layer's rows in the legend, top to bottom (none: nothing in the legend). */
    List<LegendRow> legend();
}
