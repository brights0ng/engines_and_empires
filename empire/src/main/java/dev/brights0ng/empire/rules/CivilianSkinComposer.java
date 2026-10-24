// Written by Claude.
package dev.brights0ng.empire.rules;

import java.util.List;
import java.util.Map;

/**
 * Builds a civilian's skin from grayscale templates. Each layer is one template, as shade IDs ({@code -1} where the
 * template is transparent); each layer's lookup table turns its shade IDs into opaque colours. Layers come in
 * composition order, and a later layer's opaque pixels overwrite what lies beneath. No tinting, blending or filtering.
 */
public final class CivilianSkinComposer {

    /** {@code argb}: the composed pixels (0 where no layer was opaque). {@code misses}: opaque shades with no LUT entry. */
    public record Result(int[] argb, int misses) {
    }

    public static Result compose(List<int[]> layers, List<Map<Integer, Integer>> luts, int size) {
        if (layers.size() != luts.size()) {
            throw new IllegalArgumentException("one LUT per layer: " + layers.size() + " layers, " + luts.size() + " LUTs");
        }
        int[] out = new int[size];
        int misses = 0;
        for (int l = 0; l < layers.size(); l++) {
            int[] layer = layers.get(l);
            Map<Integer, Integer> lut = luts.get(l);
            for (int i = 0; i < size; i++) {
                int shade = layer[i];
                if (shade < 0) {
                    continue;
                }
                Integer argb = lut.get(shade);
                if (argb == null) {
                    misses++;
                    continue;
                }
                out[i] = argb;
            }
        }
        return new Result(out, misses);
    }

    private CivilianSkinComposer() {
    }
}
