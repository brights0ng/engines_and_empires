// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Reproducible pixel-art assets. A fixed freezing notch and warm/cold bands complement the liquid height. */
public final class ThermometerTextures {
    public static final int STEPS = ThermometerItem.STEPS;

    public static BufferedImage face() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        fill(g, 11, 1, 10, 30, 0xFF373B3B);
        fill(g, 12, 2, 8, 28, 0xFFAD8848);
        fill(g, 12, 2, 1, 27, 0xFFD4B570);
        fill(g, 13, 4, 6, 25, 0xFFE7DFC8);
        fill(g, 14, 5, 2, 20, 0xFF8D9999);
        fill(g, 13, 24, 4, 4, 0xFF8D9999);
        for (int y = SensorShape.TUBE_TOP; y < SensorShape.TUBE_BOTTOM; y++) {
            int color = y < 13 ? 0xFFC89162 : y < 18 ? 0xFFB7B69A : 0xFF819BA3;
            fill(g, 18, y, 1, 1, color);
        }
        for (int y = 6; y <= 24; y += 3) { fill(g, 17, y, 2, 1, 0xFF766E58); }
        int freeze = SensorShape.TUBE_BOTTOM - (int) Math.round(ThermometerItem.DIAL.fraction(0)
                * (SensorShape.TUBE_BOTTOM - SensorShape.TUBE_TOP));
        fill(g, 16, freeze, 4, 1, 0xFF334751);
        fill(g, 15, 2, 2, 1, 0xFF60563E);
        g.dispose();
        return image;
    }

    public static BufferedImage item(double fraction) {
        BufferedImage image = face();
        Graphics2D g = image.createGraphics();
        if (Double.isFinite(fraction)) {
            int height = (int) Math.round(Math.clamp(fraction, 0, 1) * (SensorShape.TUBE_BOTTOM - SensorShape.TUBE_TOP));
            fill(g, SensorShape.TUBE_LEFT, SensorShape.TUBE_BOTTOM - height,
                    SensorShape.TUBE_RIGHT - SensorShape.TUBE_LEFT, height, 0xFFBD3B35);
            fill(g, SensorShape.BULB_LEFT, SensorShape.BULB_TOP,
                    SensorShape.BULB_RIGHT - SensorShape.BULB_LEFT, SensorShape.BULB_BOTTOM - SensorShape.BULB_TOP, 0xFFBD3B35);
            fill(g, SensorShape.BULB_LEFT, SensorShape.BULB_TOP, 1, 2, 0xFFDD6752);
        } else {
            fill(g, 12, 15, 8, 2, 0xFF62696D);
        }
        g.dispose();
        return image;
    }

    public static BufferedImage metal() {
        return InstrumentMetalTextures.panel(InstrumentMetalTextures.Panel.BASE);
    }

    public static BufferedImage white() {
        // Keep even this uniform renderer texture compatible with the block atlas's normal mipmap sizes.
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) { image.setRGB(x, y, 0xFFFFFFFF); }
        }
        return image;
    }

    private static void fill(Graphics2D g, int x, int y, int width, int height, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x, y, width, height);
    }

    private ThermometerTextures() { }
}
