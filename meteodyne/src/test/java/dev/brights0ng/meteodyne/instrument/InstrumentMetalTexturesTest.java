// Tests written by ChatGPT against reference pixel art created by Bright.
package dev.brights0ng.meteodyne.instrument;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class InstrumentMetalTexturesTest {
    @Test
    void generatedLogicalPanelsRecreateBrightsOriginalPixels() throws IOException {
        for (var panel : InstrumentMetalTextures.Panel.values()) {
            if (panel == InstrumentMetalTextures.Panel.BASE || panel == InstrumentMetalTextures.Panel.SUPPORT_END
                    || panel.name().startsWith("WIND_")) { continue; }
            String path = "/instrument-mount-reference/" + panel.path() + ".png";
            try (var stream = getClass().getResourceAsStream(path)) {
                assertNotNull(stream, path);
                var reference = ImageIO.read(stream);
                var generated = InstrumentMetalTextures.panel(panel);
                assertEquals(reference.getWidth(), panel.width(), path);
                assertEquals(reference.getHeight(), panel.height(), path);
                for (int y = 0; y < reference.getHeight(); y++) {
                    for (int x = 0; x < reference.getWidth(); x++) {
                        assertEquals(reference.getRGB(x, y), generated.getRGB(x, y), panel + " pixel " + x + "," + y);
                    }
                }
            }
        }
    }

    @Test
    void boltsKeepTheirInsetUntilTheyWouldTouchThenCollapseToOneCenteredRow() {
        assertEquals(List.of(2, 12), InstrumentMetalTextures.boltAnchors(16));
        assertEquals(List.of(2, 10), InstrumentMetalTextures.boltAnchors(14));
        assertEquals(List.of(2, 9), InstrumentMetalTextures.boltAnchors(13));
        assertEquals(List.of(2, 5), InstrumentMetalTextures.boltAnchors(9));
        assertEquals(List.of(3), InstrumentMetalTextures.boltAnchors(8));
        assertEquals(List.of(2), InstrumentMetalTextures.boltAnchors(6));
        assertEquals(List.of(), InstrumentMetalTextures.boltAnchors(4));
        assertEquals(List.of(), InstrumentMetalTextures.boltAnchors(2));
    }

    @Test
    void spritesRemainMipmapCompatibleAndTheirPaddingContainsNoExtraBolts() {
        for (var panel : InstrumentMetalTextures.Panel.values()) {
            var image = InstrumentMetalTextures.panel(panel);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(255, image.getRGB(x, y) >>> 24, panel + " is opaque");
                    if (x >= panel.width() || y >= panel.height()) {
                        assertEquals(InstrumentMetalTextures.EDGE, image.getRGB(x, y), panel + " padding");
                    }
                }
            }
        }
    }

    @Test
    void portraitFacesSelectTheSamePanelInsteadOfAnotherStretchedTile() {
        assertEquals(InstrumentMetalTextures.Panel.HEAD_BACK, InstrumentMetalTextures.Panel.forFace(6, 13));
        assertEquals(InstrumentMetalTextures.Panel.HEAD_BACK, InstrumentMetalTextures.Panel.forFace(13, 6));
        assertEquals(InstrumentMetalTextures.Panel.SUPPORT_SIDE, InstrumentMetalTextures.Panel.forFace(4, 8));
        assertEquals(InstrumentMetalTextures.Panel.BASE_SIDE, InstrumentMetalTextures.Panel.forFace(2, 16));
        assertThrows(IllegalArgumentException.class, () -> InstrumentMetalTextures.Panel.forFace(13, 13));
    }
}
