// Edited by Gemini: added tab-filtered showing() and hasLayer() for MapLayers.
package dev.brights0ng.seismicsurveyance.client;

import dev.brights0ng.seismicsurveyance.logic.LoggerDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.world.level.Level;

/** The layers the survey's maps draw (split seam S8): another mod registers its {@link MapLayer} at client setup. */
public final class MapLayers {

    private static final List<MapLayer> LAYERS = new CopyOnWriteArrayList<>();

    /** Adds a layer, drawn after (over) the ones registered before it. Call at client setup. */
    public static void register(MapLayer layer) {
        LAYERS.add(Objects.requireNonNull(layer));
    }

    /** The layers with something to draw in {@code level}, in the order they were registered. */
    public static List<MapLayer> showing(Level level) {
        List<MapLayer> showing = new ArrayList<>(LAYERS.size());
        for (MapLayer layer : LAYERS) {
            if (layer.shows(level)) {
                showing.add(layer);
            }
        }
        return showing;
    }

    /** The layers for a specific tab with something to draw in {@code level}. */
    public static List<MapLayer> showing(Level level, LoggerDisplay.Tab tab) {
        List<MapLayer> showing = new ArrayList<>(LAYERS.size());
        for (MapLayer layer : LAYERS) {
            if (layer.tab() == tab && layer.shows(level)) {
                showing.add(layer);
            }
        }
        return showing;
    }

    /** Whether any registered layer for {@code tab} shows in {@code level}. */
    public static boolean hasLayer(Level level, LoggerDisplay.Tab tab) {
        for (MapLayer layer : LAYERS) {
            if (layer.tab() == tab && layer.shows(level)) {
                return true;
            }
        }
        return false;
    }

    /** Every legend row of {@code layers}, in order. */
    public static List<MapLayer.LegendRow> legend(List<MapLayer> layers) {
        List<MapLayer.LegendRow> rows = new ArrayList<>();
        for (MapLayer layer : layers) {
            rows.addAll(layer.legend());
        }
        return rows;
    }

    private MapLayers() {
    }
}
