package dev.brights0ng.enginesandempires.weather.cloud.client;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Renders a cloud's mesh to a PNG the way the game would (its vertex colours, a z-buffer, an orthographic camera, a
 * sky-blue background), for checking shapes and shading by eye without the game. Test-only.
 */
final class CloudSnapshot {

    private final List<float[]> v = new ArrayList<>();
    private final List<Integer> c = new ArrayList<>();
    /** Each vertex's normal (unit). */
    private final List<float[]> nrm = new ArrayList<>();
    /** Vertices already coloured (by {@link #sink(double[], double[])}). */
    private final List<Boolean> done = new ArrayList<>();
    /** The light's colours, as the shader's SkyColor and SunColor (white: daytime). */
    double[] sky = {1, 1, 1};
    double[] sun = {1, 1, 1};
    /** The light's strength, as the shader's LightStrength. */
    double strength = 1;
    /** Toward the light, as the shader's LightDir (default: CloudField's mid-morning sun). */
    double[] light = unit(0.42, 0.78, 0.46);

    static double[] unit(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / l, y / l, z / l};
    }

    /** As the shader (CloudShading): the vertex's sky and sun parts in their colours. */
    private int shade(int argb, float[] n) {
        // (The lighting-parts pictures switch the sun off: the sky's light alone.)
        double st = CloudVoxelizer.debugNoSun ? 0 : strength;
        double[] parts = CloudShading.parts(argb, n[0], n[1], n[2], light[0], light[1], light[2], st,
                CloudTuning.shadowSide);
        double s = parts[0], d = parts[1];
        int r = (int) Math.round(Math.min(1, s * sky[0] + d * sun[0]) * 255);
        int g = (int) Math.round(Math.min(1, s * sky[1] + d * sun[1]) * 255);
        int b = (int) Math.round(Math.min(1, s * sky[2] + d * sun[2]) * 255);
        return (r << 16) | (g << 8) | b;
    }

    private int colourOf(int i) {
        return done.get(i) ? c.get(i) : shade(c.get(i), nrm.get(i));
    }

    CloudVoxelizer.VertexSink sink() {
        return sink(0, 0);
    }

    /** A sink adding its vertices moved by (ox, 0, oz) (several clouds side by side in one picture). */
    CloudVoxelizer.VertexSink sink(float ox, float oz) {
        return new CloudVoxelizer.VertexSink() {
            @Override
            public void vertex(float x, float y, float z, int argb) {
                vertex(x, y, z, argb, 0, 1, 0);
            }

            @Override
            public void vertex(float x, float y, float z, int argb, float nx, float ny, float nz) {
                v.add(new float[]{x + ox, y, z + oz});
                c.add(argb);
                nrm.add(new float[]{nx, ny, nz});
                done.add(false);
            }
        };
    }

    /**
     * A sink colouring its vertices with these light colours and this light (for several times of day in one
     * picture), moved by (ox, 0, oz).
     */
    CloudVoxelizer.VertexSink sink(double[] skyColour, double[] sunColour, CloudLight l, float ox, float oz) {
        return new CloudVoxelizer.VertexSink() {
            @Override
            public void vertex(float x, float y, float z, int argb) {
                vertex(x, y, z, argb, 0, 1, 0);
            }

            @Override
            public void vertex(float x, float y, float z, int argb, float nx, float ny, float nz) {
                double[] keepSky = sky, keepSun = sun, keepLight = light;
                double keepStrength = strength;
                sky = skyColour;
                sun = sunColour;
                strength = l.strength();
                light = new double[]{l.x(), l.y(), l.z()};
                float[] n = {nx, ny, nz};
                int rgb = shade(argb, n);
                sky = keepSky;
                sun = keepSun;
                strength = keepStrength;
                light = keepLight;
                v.add(new float[]{x + ox, y, z + oz});
                c.add(rgb);
                nrm.add(n);
                done.add(true);
            }
        };
    }

    /**
     * Writes {@code file}: {@code width} pixels wide, looking along a direction turned {@code yaw} degrees about the
     * vertical and tilted {@code pitch} degrees (negative looks up at the cloud).
     */
    void write(File file, int width, double yaw, double pitch) throws IOException {
        double cy = Math.cos(Math.toRadians(yaw)), sy = Math.sin(Math.toRadians(yaw));
        double cp = Math.cos(Math.toRadians(pitch)), sp = Math.sin(Math.toRadians(pitch));
        int n = v.size();
        double[][] p = new double[n][3];
        double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE, minV = Double.MAX_VALUE, maxV = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            float[] q = v.get(i);
            // Turn about y, then tilt about the new x.
            double x1 = q[0] * cy - q[2] * sy;
            double z1 = q[0] * sy + q[2] * cy;
            double y2 = q[1] * cp - z1 * sp;
            double z2 = q[1] * sp + z1 * cp;
            p[i][0] = x1;
            p[i][1] = y2;
            p[i][2] = z2;
            minU = Math.min(minU, x1);
            maxU = Math.max(maxU, x1);
            minV = Math.min(minV, y2);
            maxV = Math.max(maxV, y2);
        }
        double pad = 0.05 * (maxU - minU);
        double scale = (width - 1) / (maxU - minU + 2 * pad);
        int height = Math.max(16, (int) Math.ceil((maxV - minV + 2 * pad) * scale) + 1);
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        double[] depth = new double[width * height];
        java.util.Arrays.fill(depth, Double.MAX_VALUE);
        for (int y = 0; y < height; y++) {
            int sky = sky(y, height);
            for (int x = 0; x < width; x++) {
                img.setRGB(x, y, sky);
            }
        }
        for (int q = 0; q + 3 < n; q += 4) {
            tri(p, q, q + 1, q + 2, img, depth, minU - pad, maxV + pad, scale);
            tri(p, q, q + 2, q + 3, img, depth, minU - pad, maxV + pad, scale);
        }
        drawSprites(img, depth, cy, sy, cp, sp, minU - pad, maxV + pad, scale);
        file.getParentFile().mkdirs();
        ImageIO.write(img, "png", file);
    }

    /** A wisp sprite, as {@link CloudWispRenderer} draws it: centre, half size, shape, colour and opacity. */
    private record Sprite(double x, double y, double z, double size, double stretch, double turn, boolean shred,
                          int rgb, double alpha) {
    }

    private final List<Sprite> sprites = new ArrayList<>();

    /** Adds a wisp sprite at (x, y, z), coloured {@code rgb}, at {@code alpha}. */
    void sprite(double x, double y, double z, double size, double stretch, double turn, boolean shred, int rgb,
                double alpha) {
        sprites.add(new Sprite(x, y, z, size, stretch, turn, shred, rgb, alpha));
    }

    /** The sprites, far to near, blended over the picture and hidden behind nearer cloud. */
    private void drawSprites(BufferedImage img, double[] depth, double cy, double sy, double cp, double sp, double u0,
                             double v0, double scale) {
        record P(Sprite s, double x, double y, double z) {
        }
        List<P> ps = new ArrayList<>();
        for (Sprite s : sprites) {
            double x1 = s.x * cy - s.z * sy;
            double z1 = s.x * sy + s.z * cy;
            ps.add(new P(s, x1, s.y * cp - z1 * sp, s.y * sp + z1 * cp));
        }
        ps.sort((a, b) -> Double.compare(b.z, a.z));
        int w = img.getWidth(), h = img.getHeight();
        for (P p : ps) {
            Sprite s = p.s;
            // Screen axes of the sprite (pixels): haze turned in the view, shreds upright.
            double c = s.shred ? 1 : Math.cos(s.turn), sn = s.shred ? 0 : Math.sin(s.turn);
            double ax = c * s.size * scale, ay = sn * s.size * scale;
            double bx = -sn * s.size * s.stretch * scale, by = c * s.size * s.stretch * scale;
            double px = (p.x - u0) * scale, py = (v0 - p.y) * scale;
            double reach = Math.abs(ax) + Math.abs(bx) + Math.abs(ay) + Math.abs(by);
            double det = ax * by - ay * bx;
            for (int y = (int) Math.max(0, py - reach); y <= Math.min(h - 1, py + reach); y++) {
                for (int x = (int) Math.max(0, px - reach); x <= Math.min(w - 1, px + reach); x++) {
                    if (p.z >= depth[y * w + x]) {
                        continue;
                    }
                    // Sprite-local coordinates, -1..1 (screen y points down).
                    double dx = x + 0.5 - px, dy = -(y + 0.5 - py);
                    double a = (dx * by - dy * bx) / det, b = (ax * dy - ay * dx) / det;
                    if (Math.abs(a) > 1 || Math.abs(b) > 1) {
                        continue;
                    }
                    double u = (a + 1) / 2, v = (1 - b) / 2;
                    double alpha = (s.shred ? WispTexture.shred(u, v) : WispTexture.haze(u, v)) * s.alpha;
                    if (alpha <= 0) {
                        continue;
                    }
                    int dst = img.getRGB(x, y);
                    int r = (int) Math.round(((s.rgb >> 16) & 255) * alpha + ((dst >> 16) & 255) * (1 - alpha));
                    int g = (int) Math.round(((s.rgb >> 8) & 255) * alpha + ((dst >> 8) & 255) * (1 - alpha));
                    int bl = (int) Math.round((s.rgb & 255) * alpha + (dst & 255) * (1 - alpha));
                    img.setRGB(x, y, (r << 16) | (g << 8) | bl);
                }
            }
        }
    }

    private static int sky(int y, int h) {
        double t = (double) y / h;
        int r = (int) (110 + 60 * t), g = (int) (170 + 40 * t), b = 255;
        return (r << 16) | (g << 8) | b;
    }

    private void tri(double[][] p, int a, int b, int d, BufferedImage img, double[] depth, double u0, double v0,
                     double scale) {
        int w = img.getWidth(), h = img.getHeight();
        double ax = (p[a][0] - u0) * scale, ay = (v0 - p[a][1]) * scale;
        double bx = (p[b][0] - u0) * scale, by = (v0 - p[b][1]) * scale;
        double dx = (p[d][0] - u0) * scale, dy = (v0 - p[d][1]) * scale;
        double area = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax);
        if (Math.abs(area) < 1e-12) {
            return;
        }
        int x0 = (int) Math.max(0, Math.floor(Math.min(ax, Math.min(bx, dx))));
        int x1 = (int) Math.min(w - 1, Math.ceil(Math.max(ax, Math.max(bx, dx))));
        int y0 = (int) Math.max(0, Math.floor(Math.min(ay, Math.min(by, dy))));
        int y1 = (int) Math.min(h - 1, Math.ceil(Math.max(ay, Math.max(by, dy))));
        int ca = colourOf(a), cb = colourOf(b), cd = colourOf(d);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double px = x + 0.5, py = y + 0.5;
                double wa = ((bx - px) * (dy - py) - (by - py) * (dx - px)) / area;
                double wb = ((dx - px) * (ay - py) - (dy - py) * (ax - px)) / area;
                double wd = 1 - wa - wb;
                if (wa < -1e-9 || wb < -1e-9 || wd < -1e-9) {
                    continue;
                }
                double z = wa * p[a][2] + wb * p[b][2] + wd * p[d][2];
                int idx = y * w + x;
                if (z >= depth[idx]) {
                    continue;
                }
                depth[idx] = z;
                int r = (int) (wa * ((ca >> 16) & 255) + wb * ((cb >> 16) & 255) + wd * ((cd >> 16) & 255));
                int g = (int) (wa * ((ca >> 8) & 255) + wb * ((cb >> 8) & 255) + wd * ((cd >> 8) & 255));
                int bl = (int) (wa * (ca & 255) + wb * (cb & 255) + wd * (cd & 255));
                img.setRGB(x, y, (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, bl));
            }
        }
    }
}
