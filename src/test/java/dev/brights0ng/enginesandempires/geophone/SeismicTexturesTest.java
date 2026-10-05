package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SeismicTexturesTest {

    @Test
    void everyTextureIsA16By16ImageWithSomethingDrawnOnIt() {
        Map<String, BufferedImage> textures = SeismicTextures.all();
        // The sledgehammer, both geophone items, the strike plate, and the andesite and brass geophones' block parts.
        assertEquals(12, textures.size());
        for (Map.Entry<String, BufferedImage> entry : textures.entrySet()) {
            BufferedImage image = entry.getValue();
            assertEquals(16, image.getWidth(), entry.getKey());
            assertEquals(16, image.getHeight(), entry.getKey());
            int drawn = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        drawn++;
                    }
                }
            }
            assertTrue(drawn >= 20, entry.getKey() + " has only " + drawn + " visible pixels");
        }
    }

    @Test
    void blockTexturesAreFullyOpaqueAndItemTexturesHaveTransparentCorners() {
        for (Map.Entry<String, BufferedImage> entry : SeismicTextures.all().entrySet()) {
            boolean opaque = true;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    opaque &= (entry.getValue().getRGB(x, y) >>> 24) == 255;
                }
            }
            if (entry.getKey().startsWith("block/")) {
                assertTrue(opaque, entry.getKey() + " should have no see-through pixels");
            } else {
                assertEquals(0, entry.getValue().getRGB(0, 0) >>> 24, entry.getKey() + " should have a clear corner");
            }
        }
    }

    @Test
    void theDrawingIsDeterministic() {
        for (Map.Entry<String, BufferedImage> entry : SeismicTextures.all().entrySet()) {
            BufferedImage again = SeismicTextures.all().get(entry.getKey());
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(entry.getValue().getRGB(x, y), again.getRGB(x, y), entry.getKey());
                }
            }
        }
    }

    @Test
    void theLitCapIsBrighterThanTheUnlitOne() {
        Map<String, BufferedImage> textures = SeismicTextures.all();
        assertTrue(brightness(textures.get("block/andesite_geophone_cap_lit")) > 1.6 * brightness(textures.get("block/andesite_geophone_cap")));
    }

    private static double brightness(BufferedImage image) {
        double total = 0.0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int p = image.getRGB(x, y);
                total += ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
            }
        }
        return total;
    }
}
