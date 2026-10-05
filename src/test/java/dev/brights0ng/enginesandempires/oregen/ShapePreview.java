package dev.brights0ng.enginesandempires.oregen;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Not a test: a picture-maker for eyeballing deposit shapes. Run its main method to write two PNGs, one
 * of typical shallow deposits and one of the largest deep ones found, with a row for every ore. Each
 * row shows a top-down view of the whole deposit, then three slices through its middle.
 *
 * <p>Grey is host rock, the ore's colour is ore, white is rich ore, and black is a hollow cavity.
 */
public final class ShapePreview {

    private static final int PANEL = 220;
    private static final int LABEL = 190;
    private static final Map<String, Color> COLOURS = Map.ofEntries(
            Map.entry("coal", new Color(40, 40, 40)),
            Map.entry("iron", new Color(214, 150, 110)),
            Map.entry("copper", new Color(220, 120, 50)),
            Map.entry("zinc", new Color(140, 170, 190)),
            Map.entry("redstone", new Color(220, 30, 30)),
            Map.entry("lapis", new Color(40, 70, 210)),
            Map.entry("nether_quartz", new Color(235, 225, 205)),
            Map.entry("nether_gold", new Color(250, 170, 40)),
            Map.entry("crystal", new Color(170, 90, 220)),
            Map.entry("gold", new Color(240, 200, 30)),
            Map.entry("emerald", new Color(30, 200, 90)),
            Map.entry("diamond", new Color(60, 220, 230)));

    public static void main(String[] args) throws IOException {
        File dir = new File(args.length > 0 ? args[0] : "preview");
        dir.mkdirs();
        long seed = 20260920L;

        List<DepositBody> typical = new ArrayList<>();
        List<DepositBody> largest = new ArrayList<>();
        for (OreType type : OreTypes.ALL) {
            OreMap map = new OreMap(seed, type.layer());
            List<Deposit> deposits = map.depositsNear(0, 0, (int) Math.min(30000, type.layer().scale() * 14));
            if (deposits.size() > 400) {
                deposits = deposits.subList(0, 400);
            }
            DepositBody nearMedian = null;
            DepositBody biggest = null;
            for (Deposit deposit : deposits) {
                DepositBody body = DepositBody.generate(deposit, type);
                if (body.sizeMultiplier() == 1.0
                        && (nearMedian == null || Math.abs(body.oreCount() - type.size().median())
                        < Math.abs(nearMedian.oreCount() - type.size().median()))) {
                    nearMedian = body;
                }
                if (biggest == null || body.oreCount() > biggest.oreCount()) {
                    biggest = body;
                }
            }
            typical.add(nearMedian != null ? nearMedian : biggest);
            largest.add(biggest);
        }
        render(typical, new File(dir, "shapes-typical.png"));
        render(largest, new File(dir, "shapes-largest.png"));
        System.out.println("wrote " + dir.getAbsolutePath());
    }

    private static void render(List<DepositBody> bodies, File file) throws IOException {
        BufferedImage image = new BufferedImage(LABEL + 4 * PANEL, bodies.size() * PANEL, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(24, 24, 28));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());

        for (int row = 0; row < bodies.size(); row++) {
            DepositBody body = bodies.get(row);
            int top = row * PANEL;
            int extent = Math.max(body.reachX(), Math.max(body.reachY(), body.reachZ()));
            int scale = Math.max(1, Math.min(10, (PANEL - 8) / (2 * extent + 1)));
            Color ore = COLOURS.get(body.type().id());

            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
            g.drawString(body.type().id() + "  (" + body.type().shape().name() + ")", 8, top + 22);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            String[] lines = {
                    "ore " + body.oreCount() + "  rich " + body.richCount(),
                    "host " + body.hostCount() + "  void " + body.voidCount(),
                    "y " + body.centerY() + "  x" + String.format("%.1f", body.sizeMultiplier()),
                    "reach " + body.reachX() + " / " + body.reachY() + " / " + body.reachZ(),
                    "scale " + scale + " px/block"};
            for (int i = 0; i < lines.length; i++) {
                g.drawString(lines[i], 8, top + 44 + i * 16);
            }

            // Where the ore is centred, so the slices pass through the middle of it.
            long[] sum = new long[3];
            int[] n = new int[1];
            body.forEach((dx, dy, dz, kind) -> {
                if (kind == DepositBody.ORE || kind == DepositBody.RICH) {
                    sum[0] += dx;
                    sum[1] += dy;
                    sum[2] += dz;
                    n[0]++;
                }
            });
            int cx = (int) Math.round(sum[0] / (double) Math.max(1, n[0]));
            int cy = (int) Math.round(sum[1] / (double) Math.max(1, n[0]));
            int cz = (int) Math.round(sum[2] / (double) Math.max(1, n[0]));

            for (int panel = 0; panel < 4; panel++) {
                int left = LABEL + panel * PANEL;
                g.setColor(new Color(34, 34, 40));
                g.fillRect(left + 1, top + 1, PANEL - 2, PANEL - 2);
                int mid = PANEL / 2;
                for (int u = -extent; u <= extent; u++) {
                    for (int v = -extent; v <= extent; v++) {
                        byte kind;
                        switch (panel) {
                            case 0 -> kind = projectDown(body, u, v, extent);        // top-down view: x across, z down
                            case 1 -> kind = body.at(u, cy, v);                       // slice at fixed y
                            case 2 -> kind = body.at(cx, -v, u);                      // slice at fixed x: z across, y up
                            default -> kind = body.at(u, -v, cz);                     // slice at fixed z: x across, y up
                        }
                        Color c = colour(kind, ore);
                        if (c != null) {
                            g.setColor(c);
                            g.fillRect(left + mid + u * scale - scale / 2, top + mid + v * scale - scale / 2, scale, scale);
                        }
                    }
                }
                g.setColor(new Color(70, 70, 80));
                g.drawRect(left, top, PANEL - 1, PANEL - 1);
            }
        }
        g.dispose();
        ImageIO.write(image, "png", file);
    }

    /** The most significant block seen looking straight down through the deposit at this column. */
    private static byte projectDown(DepositBody body, int dx, int dz, int extent) {
        byte best = DepositBody.EMPTY;
        for (int dy = extent; dy >= -extent; dy--) {
            byte kind = body.at(dx, dy, dz);
            if (kind == DepositBody.RICH) {
                return kind;
            }
            if (kind == DepositBody.ORE) {
                best = kind;
            } else if (best == DepositBody.EMPTY && kind == DepositBody.HOST) {
                best = kind;
            }
        }
        return best;
    }

    private static Color colour(byte kind, Color ore) {
        return switch (kind) {
            case DepositBody.HOST -> new Color(95, 95, 100);
            case DepositBody.ORE -> ore;
            case DepositBody.RICH -> Color.WHITE;
            case DepositBody.VOID -> Color.BLACK;
            default -> null;
        };
    }

    private ShapePreview() {
    }
}
