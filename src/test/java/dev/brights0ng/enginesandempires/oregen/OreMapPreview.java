package dev.brights0ng.enginesandempires.oregen;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Not part of the mod. Run its main() from the IDE to see an ore map without launching Minecraft.
 *
 * <p>For each ore below it writes {@code run/oremap-<id>.png}: the noise field in grey, every deposit
 * as a red dot, and a scale bar of one S. It also prints how many deposits it found and how they are
 * spaced. Change the seed, the ores or their scales and re-run to tune.
 */
public final class OreMapPreview {

    private static final long SEED = 20260920L;
    private static final int IMAGE_SIZE = 1024;
    /** How many S the picture spans in each direction. */
    private static final int SPAN_IN_SCALES = 12;

    private static final List<OreLayer> LAYERS = List.of(
            new OreLayer("iron", 384),
            new OreLayer("gold", 1408));

    public static void main(String[] args) throws IOException {
        Path outDir = Path.of("run");
        Files.createDirectories(outDir);
        for (OreLayer layer : LAYERS) {
            render(layer, outDir.resolve("oremap-" + layer.id() + ".png"));
        }
    }

    private static void render(OreLayer layer, Path file) throws IOException {
        OreMap map = new OreMap(SEED, layer);
        int half = layer.scale() * SPAN_IN_SCALES / 2;
        double blocksPerPixel = (2.0 * half) / IMAGE_SIZE;

        BufferedImage image = new BufferedImage(IMAGE_SIZE, IMAGE_SIZE, BufferedImage.TYPE_INT_RGB);
        for (int px = 0; px < IMAGE_SIZE; px++) {
            for (int pz = 0; pz < IMAGE_SIZE; pz++) {
                double value = map.field(-half + px * blocksPerPixel, -half + pz * blocksPerPixel);
                int grey = (int) Math.round((value * 0.5 + 0.5) * 200.0) + 20;
                image.setRGB(px, pz, new Color(grey, grey, grey).getRGB());
            }
        }

        List<Deposit> deposits = map.depositsInBox(-half, -half, half, half);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(220, 40, 40));
        for (Deposit d : deposits) {
            int px = (int) Math.round((d.x() + half) / blocksPerPixel);
            int pz = (int) Math.round((d.z() + half) / blocksPerPixel);
            g.fillOval(px - 4, pz - 4, 9, 9);
        }
        // Scale bar: one S long.
        int bar = (int) Math.round(layer.scale() / blocksPerPixel);
        g.setColor(Color.YELLOW);
        g.fillRect(20, IMAGE_SIZE - 30, bar, 6);
        g.dispose();
        ImageIO.write(image, "png", file.toFile());

        // Nearest-neighbour spacing, as a multiple of S.
        double total = 0;
        double closest = Double.MAX_VALUE;
        for (Deposit a : deposits) {
            double best = Double.MAX_VALUE;
            for (Deposit b : deposits) {
                if (a != b) {
                    double dx = a.x() - b.x();
                    double dz = a.z() - b.z();
                    best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
                }
            }
            total += best;
            closest = Math.min(closest, best);
        }
        double area = (2.0 * half) * (2.0 * half);
        System.out.printf("%s (S=%d): %d deposits, %.2f per S x S, mean nearest neighbour %.2f S, closest pair %.2f S -> %s%n",
                layer.id(), layer.scale(), deposits.size(),
                deposits.size() / area * layer.scale() * layer.scale(),
                total / deposits.size() / layer.scale(),
                closest / layer.scale(), file);
    }

    private OreMapPreview() {
    }
}
