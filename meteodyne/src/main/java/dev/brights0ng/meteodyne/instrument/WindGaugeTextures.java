// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Procedural compass dial and four-cup anemometer. No numeric speed overlay. */
public final class WindGaugeTextures {
    public static final int DIRECTIONS = 32, ROTOR_FRAMES = 8;
    public static BufferedImage item(int direction, int rotor, boolean known) {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setColor(new Color(0x515755)); g.fillOval(2, 1, 28, 28);
        g.setColor(new Color(0xAD8848)); g.fillOval(3, 2, 26, 26);
        g.setColor(new Color(0xDDD6B7)); g.fillOval(5, 4, 22, 22);
        ticks(g, 16, 15, 9);
        if (known && direction >= 0) { arrow(g, 16, 15, direction * 360.0 / DIRECTIONS, 7); }
        if (!known) { g.setColor(Color.GRAY); g.fillRect(12, 14, 8, 2); }
        // The cup rotor is below the direction dial; four visible bowls move around its hub.
        g.setColor(new Color(0x515755)); g.fillRect(5, 21, 22, 11);
        cups(g, 16, 26, rotor * 90.0 / ROTOR_FRAMES, 7);
        g.dispose();
        return image;
    }
    public static BufferedImage face() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(image);
        g.setColor(new Color(0x515755)); g.fillRect(0, 0, 6, 6);
        g.setColor(new Color(0xDDD6B7)); g.fillRect(1, 1, 4, 4);
        g.setColor(new Color(0x63695F)); g.fillRect(2, 0, 2, 1); g.fillRect(2, 5, 2, 1);
        g.fillRect(0, 2, 1, 2); g.fillRect(5, 2, 1, 2);
        g.dispose(); return image;
    }
    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setStroke(new BasicStroke(1)); return g;
    }
    private static void ticks(Graphics2D g, int x, int y, int radius) {
        g.setColor(new Color(0x63695F));
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            g.drawLine(x + (int) Math.round(Math.sin(a) * (radius - 2)), y - (int) Math.round(Math.cos(a) * (radius - 2)),
                    x + (int) Math.round(Math.sin(a) * radius), y - (int) Math.round(Math.cos(a) * radius));
        }
        g.setColor(new Color(0xBD3B35)); g.fillRect(x - 1, y - radius, 3, 2);
    }
    private static void arrow(Graphics2D g, int x, int y, double degrees, int radius) {
        var previous = g.getTransform(); g.rotate(Math.toRadians(degrees), x, y);
        g.setColor(new Color(0xBD3B35));
        g.fillPolygon(new int[]{x, x - 3, x - 1, x - 1, x + 1, x + 1, x + 3},
                new int[]{y - radius, y - 2, y - 3, y + 5, y + 5, y - 3, y - 2}, 7);
        g.setColor(new Color(0xD4B570)); g.fillRect(x, y, 1, 1); g.setTransform(previous);
    }
    private static void cups(Graphics2D g, int x, int y, double degrees, int radius) {
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(degrees + i * 90);
            int cx = x + (int) Math.round(Math.sin(a) * radius), cy = y - (int) Math.round(Math.cos(a) * radius * 0.45);
            g.setColor(new Color(0xAD8848)); g.drawLine(x, y, cx, cy);
            g.setColor(new Color(0xD4B570)); g.fillOval(cx - 2, cy - 2, 5, 4);
            g.setColor(new Color(0x363D3C)); g.fillOval(cx - 1, cy - 1, 3, 2);
        }
        g.setColor(new Color(0xD4B570)); g.fillRect(x - 1, y - 1, 3, 3);
    }
    private WindGaugeTextures() { }
}
