package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ThumperTexturesTest {

    private static final Map<String, BufferedImage> ALL = ThumperTextures.all();

    /** Only the lamps and the fuel gauge's dial are the thumpers' own: everything else uses Create's textures. */
    @Test
    void onlyTheLampAndGaugeTexturesExist() {
        assertEquals(3, ALL.size());
        assertTrue(ALL.containsKey("block/mechanical_thumper_lamp"));
        assertTrue(ALL.containsKey("block/mechanical_thumper_lamp_lit"));
        assertTrue(ALL.containsKey("block/combustive_thumper_gauge"));
    }

    /** The red "empty" band is on the dial's left, where the needle points when the tank is empty. */
    @Test
    void theGaugeIsRedOnlyAtTheEmptyEnd() {
        BufferedImage gauge = ALL.get("block/combustive_thumper_gauge");
        assertTrue(isRed(gauge.getRGB(3, 5)), "the empty end should be red");
        assertFalse(isRed(gauge.getRGB(12, 5)), "the full end should not be red");
    }

    private static boolean isRed(int argb) {
        return channel(argb, 16) > 150 && channel(argb, 8) < 80;
    }

    @Test
    void everyTextureIsSixteenBySixteenAndOpaque() {
        for (Map.Entry<String, BufferedImage> entry : ALL.entrySet()) {
            BufferedImage image = entry.getValue();
            assertEquals(16, image.getWidth(), entry.getKey());
            assertEquals(16, image.getHeight(), entry.getKey());
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(255, image.getRGB(x, y) >>> 24, entry.getKey() + " at " + x + "," + y);
                }
            }
        }
    }

    /** The lit lamp is the andesite geophone's amber, pixel for pixel, so the two read as the same kind of light. */
    @Test
    void theLitLampIsTheAndesiteGeophonesAmber() {
        BufferedImage lit = ALL.get("block/mechanical_thumper_lamp_lit");
        BufferedImage geophone = SeismicTextures.cap(true);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                assertEquals(geophone.getRGB(x, y), lit.getRGB(x, y), "at " + x + "," + y);
            }
        }
        int middle = lit.getRGB(8, 8);
        assertTrue(channel(middle, 16) > channel(middle, 0), "the lamp should be warm, not grey");
    }

    @Test
    void theLitLampIsBrighterThanTheDarkOne() {
        assertTrue(brightness(ALL.get("block/mechanical_thumper_lamp_lit")) > 2.0 * brightness(ALL.get("block/mechanical_thumper_lamp")));
    }

    @Test
    void theDrawingIsDeterministic() {
        Map<String, BufferedImage> again = ThumperTextures.all();
        for (Map.Entry<String, BufferedImage> entry : ALL.entrySet()) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(entry.getValue().getRGB(x, y), again.get(entry.getKey()).getRGB(x, y), entry.getKey());
                }
            }
        }
    }

    private static int channel(int argb, int shift) {
        return (argb >> shift) & 0xFF;
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
