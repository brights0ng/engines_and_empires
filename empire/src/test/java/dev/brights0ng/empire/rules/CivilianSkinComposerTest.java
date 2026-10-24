// Written by Claude.
package dev.brights0ng.empire.rules;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CivilianSkinComposerTest {

    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;

    @Test
    void laterLayerOverwritesEarlier() {
        int[] base = {10, 10, 10};
        int[] top = {20, 20, 20};
        CivilianSkinComposer.Result r = CivilianSkinComposer.compose(List.of(base, top),
                List.of(Map.of(10, RED), Map.of(20, GREEN)), 3);
        assertArrayEquals(new int[]{GREEN, GREEN, GREEN}, r.argb());
        assertEquals(0, r.misses());
    }

    @Test
    void transparentPixelsKeepWhatIsUnderneath() {
        int[] base = {10, 10, -1};
        int[] top = {-1, 20, -1};
        CivilianSkinComposer.Result r = CivilianSkinComposer.compose(List.of(base, top),
                List.of(Map.of(10, RED), Map.of(20, GREEN)), 3);
        assertArrayEquals(new int[]{RED, GREEN, 0}, r.argb());
        assertEquals(0, r.misses());
    }

    @Test
    void missingShadeIsCountedAndSkipped() {
        int[] base = {10, 10};
        int[] top = {99, 20};
        CivilianSkinComposer.Result r = CivilianSkinComposer.compose(List.of(base, top),
                List.of(Map.of(10, RED), Map.of(20, GREEN)), 2);
        assertArrayEquals(new int[]{RED, GREEN}, r.argb());
        assertEquals(1, r.misses());
    }

    @Test
    void coloursAreSubstitutedExactly() {
        int[] layer = {75, 117, 214, 0};
        Map<Integer, Integer> lut = Map.of(75, 0xFFAD7D5F, 117, 0xFF607B74, 214, 0xFFE4D5B7, 0, BLUE);
        CivilianSkinComposer.Result r = CivilianSkinComposer.compose(List.of(layer), List.of(lut), 4);
        assertArrayEquals(new int[]{0xFFAD7D5F, 0xFF607B74, 0xFFE4D5B7, BLUE}, r.argb());
    }
}
