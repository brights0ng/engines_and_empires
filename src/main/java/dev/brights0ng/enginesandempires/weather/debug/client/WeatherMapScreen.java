package dev.brights0ng.enginesandempires.weather.debug.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.weather.debug.WeatherMapPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The weather debug map ({@code claude/weather-backbone-plan.md}; ops only, Esc closes it). A top-down view of the
 * Overworld's weather at sea level around the player, north up.
 *
 * <ul>
 *   <li>Layers: temperature now, annual mean, humidity, surface (always-frozen hatched), pressure (isobars every
 *       4 hPa and surface wind arrows), air masses (warmer or colder than normal), moisture (relative humidity, with
 *       recent orographic rain dotted in).</li>
 *   <li>Overlays (toggle): storm tracks (dotted), fronts in weather-map symbols (cold: blue triangles, warm: red
 *       half-circles, occluded: purple, both; on the side the front moves toward), lows (L) and highs (H; "H blk" for a
 *       blocking high) with their pressure.</li>
 *   <li>Clouds (toggle, phase 4a): the clouds this client knows of, as their domes' footprints: heap clouds white
 *       (cumulonimbus yellow), low layer clouds grey, middle pale blue, high faint white; fainter while forming or
 *       dissolving. Raining clouds (phase 4b) are solid blue, deeper the harder they rain; virga (rain that doesn't
 *       reach the ground) violet. Drawn from the synced clouds, so only near the player.</li>
 *   <li>Drag to pan, scroll to zoom (about the cursor), "Centre on me" to return to the player.</li>
 *   <li>Hovering shows the values under the cursor and the nearest system.</li>
 * </ul>
 *
 * <p>Performance (perf report 2026-10-05): the overlays (tracks, fronts, wind arrows) are drawn once into a texture
 * of their own, {@value #OVER} pixels across the answer's square, whenever an answer, the layer or the toggle changes,
 * then shown with one blit a frame (they were thousands of one-pixel fills a frame). Only the lows' and highs' labels
 * are text drawn live. The map refreshes itself only while it shows something that changes (temperature now,
 * pressure, air masses, moisture, or the systems).
 */
public class WeatherMapScreen extends Screen {

    static final int SIZE = 96;
    /** The overlay texture's pixels per side. */
    static final int OVER = 384;
    private static final int PANEL = 150;
    private static final int MARGIN = 8;
    private static final int HEADER = 14;
    private static final int REFRESH_TICKS = 100;
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "weather_map");
    private static final ResourceLocation OVERLAY =
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "weather_map_overlay");
    private static final ResourceLocation CLOUDS =
            ResourceLocation.fromNamespaceAndPath(EnginesAndEmpiresMod.MODID, "weather_map_clouds");
    /** Ticks between redraws of the clouds layer. */
    private static final int CLOUD_REFRESH = 10;
    private static final String[] STAGES =
            {"wave", "deepening", "mature", "occluding", "filling", "building", "holding", "fading"};

    enum Layer {
        NOW("Temperature now"), MEAN("Annual mean"), HUMIDITY("Biome humidity"), SURFACE("Surface"),
        PRESSURE("Pressure"), AIR_MASS("Air masses"), MOISTURE("Moisture");

        final String label;

        Layer(String label) {
            this.label = label;
        }
    }

    private double centreX;
    private double centreZ;
    private int scale = 512;
    private Layer layer = Layer.PRESSURE;
    private boolean overlays = true;
    private boolean showClouds = true;
    private DynamicTexture cloudTexture;
    private int cloudsIn;
    private double cloudsX;
    private double cloudsZ;
    private int cloudsScale;
    private WeatherMapPayloads.Answer answer;
    private DynamicTexture texture;
    private DynamicTexture overlay;
    private boolean dirty;
    private boolean overlayDirty;
    private int requestIn = 0;
    private int sinceRefresh;
    private boolean dragging;
    private double dragMouseX;
    private double dragMouseY;
    private double dragCentreX;
    private double dragCentreZ;

    private int mapX;
    private int mapY;
    private int mapSize;

    public WeatherMapScreen() {
        super(Component.literal("Weather map"));
    }

    @Override
    protected void init() {
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, false);
            minecraft.getTextureManager().register(TEXTURE, texture);
            overlay = new DynamicTexture(OVER, OVER, false);
            minecraft.getTextureManager().register(OVERLAY, overlay);
            cloudTexture = new DynamicTexture(OVER, OVER, false);
            minecraft.getTextureManager().register(CLOUDS, cloudTexture);
            if (minecraft.player != null) {
                centreX = minecraft.player.getX();
                centreZ = minecraft.player.getZ();
            }
            requestIn = 1;
        }
        mapSize = Math.max(64, Math.min(width - PANEL - 3 * MARGIN, height - 2 * MARGIN - HEADER));
        mapX = MARGIN;
        mapY = MARGIN + HEADER;
        int px = mapX + mapSize + MARGIN;
        int y = mapY;
        for (Layer l : Layer.values()) {
            addRenderableWidget(Button.builder(Component.literal(l.label), b -> {
                layer = l;
                dirty = true;
                overlayDirty = true;
            }).bounds(px, y, PANEL, 16).build());
            y += 18;
        }
        addRenderableWidget(Button.builder(overlayLabel(), b -> {
            overlays = !overlays;
            overlayDirty = true;
            b.setMessage(overlayLabel());
        }).bounds(px, y + 2, PANEL, 16).build());
        addRenderableWidget(Button.builder(Component.literal("Centre on me"), b -> recentre())
                .bounds(px, y + 20, PANEL, 16).build());
        addRenderableWidget(Button.builder(cloudsLabel(), b -> {
            showClouds = !showClouds;
            cloudsIn = 0;
            b.setMessage(cloudsLabel());
        }).bounds(px, y + 38, PANEL, 16).build());
    }

    private Component cloudsLabel() {
        return Component.literal("Clouds: " + (showClouds ? "shown" : "hidden"));
    }

    private Component overlayLabel() {
        return Component.literal("Systems: " + (overlays ? "shown" : "hidden"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (texture != null) {
            minecraft.getTextureManager().release(TEXTURE);
            texture = null;
        }
        if (overlay != null) {
            minecraft.getTextureManager().release(OVERLAY);
            overlay = null;
        }
        if (cloudTexture != null) {
            minecraft.getTextureManager().release(CLOUDS);
            cloudTexture = null;
        }
        super.removed();
    }

    void accept(WeatherMapPayloads.Answer answer) {
        if (answer.size() != SIZE) {
            return;
        }
        this.answer = answer;
        dirty = true;
        overlayDirty = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (requestIn > 0 && --requestIn == 0) {
            request();
        }
        if (++sinceRefresh >= REFRESH_TICKS && !dragging && changesOverTime()) {
            request();
        }
        if (--cloudsIn <= 0) {
            cloudsIn = CLOUD_REFRESH;
            if (showClouds) {
                bakeClouds();
            }
        }
    }

    /** Whether what is shown changes by itself (the static climate layers don't, so they aren't refreshed). */
    private boolean changesOverTime() {
        return overlays || layer == Layer.NOW || layer == Layer.PRESSURE || layer == Layer.AIR_MASS
                || layer == Layer.MOISTURE;
    }

    private void request() {
        sinceRefresh = 0;
        PacketDistributor.sendToServer(new WeatherMapPayloads.Request(
                (int) Math.round(centreX), (int) Math.round(centreZ), scale, SIZE));
    }

    private void recentre() {
        if (minecraft.player != null) {
            centreX = minecraft.player.getX();
            centreZ = minecraft.player.getZ();
            requestIn = 2;
        }
    }

    // ---- coordinates -------------------------------------------------------------------------------------------

    private double pixelsPerBlock() {
        return mapSize / ((double) SIZE * scale);
    }

    private double sx(double wx) {
        return mapX + mapSize / 2.0 + (wx - centreX) * pixelsPerBlock();
    }

    private double sy(double wz) {
        return mapY + mapSize / 2.0 + (wz - centreZ) * pixelsPerBlock();
    }

    // ---- drawing -----------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (dirty) {
            paint();
        }
        if (overlayDirty) {
            bakeOverlay();
        }
        g.drawString(font, answer == null ? "Asking the server..." : answer.info(), MARGIN, MARGIN, 0xFFE0E0E0);
        g.fill(mapX - 1, mapY - 1, mapX + mapSize + 1, mapY + mapSize + 1, 0xFF505050);
        g.fill(mapX, mapY, mapX + mapSize, mapY + mapSize, 0xFF101418);
        g.enableScissor(mapX, mapY, mapX + mapSize, mapY + mapSize);
        if (answer != null) {
            double span = (double) answer.size() * answer.scale();
            int left = (int) Math.round(sx(answer.x() - span / 2));
            int top = (int) Math.round(sy(answer.z() - span / 2));
            int w = (int) Math.round(span * pixelsPerBlock());
            g.blit(TEXTURE, left, top, w, w, 0f, 0f, SIZE, SIZE, SIZE, SIZE);
            if (overlays || layer == Layer.PRESSURE) {
                RenderSystem.enableBlend();
                g.blit(OVERLAY, left, top, w, w, 0f, 0f, OVER, OVER, OVER, OVER);
                RenderSystem.disableBlend();
            }
            if (overlays) {
                drawSystems(g);
            }
        }
        if (showClouds && cloudsScale > 0) {
            double span = (double) SIZE * cloudsScale;
            int left = (int) Math.round(sx(cloudsX - span / 2));
            int top = (int) Math.round(sy(cloudsZ - span / 2));
            int w = (int) Math.round(span * pixelsPerBlock());
            RenderSystem.enableBlend();
            g.blit(CLOUDS, left, top, w, w, 0f, 0f, OVER, OVER, OVER, OVER);
            RenderSystem.disableBlend();
        }
        drawPlayer(g);
        g.disableScissor();
        g.drawString(font, "N", mapX + mapSize - 10, mapY + 4, 0xFFFFFFFF);
        drawPanel(g);
        drawHover(g, mouseX, mouseY);
    }

    private void drawPlayer(GuiGraphics g) {
        if (minecraft.player == null) {
            return;
        }
        int px = (int) Math.round(sx(minecraft.player.getX()));
        int pz = (int) Math.round(sy(minecraft.player.getZ()));
        g.fill(px - 2, pz - 2, px + 3, pz + 3, 0xFF000000);
        g.fill(px - 1, pz - 1, px + 2, pz + 2, 0xFFFFFFFF);
    }

    // ---- overlays, drawn once into their own texture ----------------------------------------------------------

    /** Redraws the clouds layer: every synced cloud's domes over the square the map shows now. */
    private void bakeClouds() {
        if (cloudTexture == null || minecraft.level == null) {
            return;
        }
        NativeImage image = cloudTexture.getPixels();
        if (image == null) {
            return;
        }
        int[] argb = new int[OVER * OVER];
        double span = (double) SIZE * scale;
        double perPixel = span / OVER;
        double x0 = centreX - span / 2;
        double z0 = centreZ - span / 2;
        double now = minecraft.level.getGameTime();
        for (var c : dev.brights0ng.enginesandempires.weather.cloud.sim.CloudSyncClient.shapes(now)) {
            var type = dev.brights0ng.enginesandempires.weather.cloud.CloudType.of(c.typeId());
            if (type == null) {
                continue;
            }
            int rgb;
            double alpha;
            if (type.heap()) {
                rgb = type.thunder ? 0xFFE070 : 0xFFFFFF;
                alpha = 0.55;
            } else {
                rgb = switch (type.deck) {
                    case LOW -> type == dev.brights0ng.enginesandempires.weather.cloud.CloudType.NIMBOSTRATUS
                            ? 0x707A88 : 0xB4B4B4;
                    case MID -> 0x9FB8FF;
                    case HIGH -> 0xFFFFFF;
                };
                alpha = type.deck == dev.brights0ng.enginesandempires.weather.cloud.CloudType.Deck.HIGH ? 0.18 : 0.3;
            }
            alpha *= Math.max(0.2, c.growth() * (1 - c.decay()));
            if (c.precipitation() > 0.02f && type.rain.rains()) {
                double s = type.rain.peak() * c.precipitation();
                boolean virga = !Float.isInfinite(c.rainBottom());
                rgb = virga ? 0xB08CE0 : lerpRgb(0x7FB2FF, 0x1238C8, Math.min(1, s / 0.8));
                alpha = Math.max(alpha, 0.55);
            }
            double cx = (c.xAt(now) - x0) / perPixel;
            double cz = (c.zAt(now) - z0) / perPixel;
            double r = Math.max(0.8, c.radius() / perPixel);
            int i0 = (int) Math.max(0, Math.floor(cx - r)), i1 = (int) Math.min(OVER - 1, Math.ceil(cx + r));
            int k0 = (int) Math.max(0, Math.floor(cz - r)), k1 = (int) Math.min(OVER - 1, Math.ceil(cz + r));
            for (int k = k0; k <= k1; k++) {
                for (int i = i0; i <= i1; i++) {
                    double dx = i + 0.5 - cx, dz = k + 0.5 - cz;
                    if (dx * dx + dz * dz > r * r) {
                        continue;
                    }
                    argb[k * OVER + i] = over(argb[k * OVER + i], rgb, alpha);
                }
            }
        }
        for (int k = 0; k < OVER; k++) {
            for (int i = 0; i < OVER; i++) {
                int p = argb[k * OVER + i];
                int a = (p >>> 24) & 255;
                image.setPixelRGBA(i, k, (a << 24) | ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF));
            }
        }
        cloudTexture.upload();
        cloudsX = centreX;
        cloudsZ = centreZ;
        cloudsScale = scale;
    }

    /** Between colours {@code a} and {@code b}. */
    private static int lerpRgb(int a, int b, double f) {
        return lerp(a, b, f);
    }

    /** {@code rgb} at {@code alpha} laid over the ARGB pixel {@code below}. */
    private static int over(int below, int rgb, double alpha) {
        double ab = ((below >>> 24) & 255) / 255.0;
        double out = alpha + ab * (1 - alpha);
        if (out <= 0) {
            return 0;
        }
        int r = (int) Math.round((((rgb >> 16) & 255) * alpha + ((below >> 16) & 255) * ab * (1 - alpha)) / out);
        int gr = (int) Math.round((((rgb >> 8) & 255) * alpha + ((below >> 8) & 255) * ab * (1 - alpha)) / out);
        int bl = (int) Math.round(((rgb & 255) * alpha + (below & 255) * ab * (1 - alpha)) / out);
        return ((int) Math.round(out * 255) << 24) | (r << 16) | (gr << 8) | bl;
    }

    /** Redraws the overlay texture for the current answer, layer and toggle. */
    private void bakeOverlay() {
        overlayDirty = false;
        if (overlay == null || overlay.getPixels() == null || answer == null) {
            return;
        }
        NativeImage image = overlay.getPixels();
        image.fillRect(0, 0, OVER, OVER, 0);
        double span = (double) answer.size() * answer.scale();
        double left = answer.x() - span / 2;
        double top = answer.z() - span / 2;
        double k = OVER / span;
        if (layer == Layer.PRESSURE) {
            bakeArrows(image, span, left, top, k);
        }
        if (overlays) {
            for (float[] j : answer.jets()) {
                for (int i = 1; i < j.length / 2; i += 2) {
                    line(image, (j[2 * i - 2] - left) * k, (j[2 * i - 1] - top) * k, (j[2 * i] - left) * k,
                            (j[2 * i + 1] - top) * k, 0xC080E0FF, 1);
                }
            }
            for (WeatherMapPayloads.FrontLine f : answer.fronts()) {
                bakeFront(image, f, left, top, k);
            }
        }
        overlay.upload();
    }

    private void bakeArrows(NativeImage image, double span, double left, double top, double k) {
        int n = answer.arrows();
        for (int row = 0; row < n; row++) {
            for (int i = 0; i < n; i++) {
                float vx = answer.windX()[row * n + i];
                float vz = answer.windZ()[row * n + i];
                double speed = Math.hypot(vx, vz);
                if (speed < 0.3) {
                    continue;
                }
                double cx = ((i + 0.5) * span / n) * k;
                double cy = ((row + 0.5) * span / n) * k;
                double len = Math.min(26, 1.6 * speed + 4);
                double ux = vx / speed;
                double uz = vz / speed;
                double x0 = cx - ux * len / 2;
                double y0 = cy - uz * len / 2;
                double x1 = x0 + ux * len;
                double y1 = y0 + uz * len;
                line(image, x0, y0, x1, y1, 0xC0FFFFFF, 1);
                line(image, x1, y1, x1 - ux * 5 + uz * 3.5, y1 - uz * 5 - ux * 3.5, 0xC0FFFFFF, 1);
                line(image, x1, y1, x1 - ux * 5 - uz * 3.5, y1 - uz * 5 + ux * 3.5, 0xC0FFFFFF, 1);
            }
        }
    }

    private static void bakeFront(NativeImage image, WeatherMapPayloads.FrontLine f, double left, double top,
                                  double k) {
        int colour = switch (f.type()) {
            case 0 -> 0xFF3070FF;
            case 1 -> 0xFFE03535;
            default -> 0xFFB050E0;
        };
        float[] p = f.points();
        double carry = 0;
        int symbol = 0;
        for (int i = 1; i < p.length / 2; i++) {
            double x0 = (p[2 * i - 2] - left) * k;
            double y0 = (p[2 * i - 1] - top) * k;
            double x1 = (p[2 * i] - left) * k;
            double y1 = (p[2 * i + 1] - top) * k;
            line(image, x0, y0, x1, y1, colour, 2);
            double len = Math.hypot(x1 - x0, y1 - y0);
            if (len < 1e-6) {
                continue;
            }
            double dx = (x1 - x0) / len;
            double dy = (y1 - y0) / len;
            // The side the front moves toward: left of its run in a northern-style zone, right in a mirrored one.
            double nx = f.hemisphere() > 0 ? dy : -dy;
            double ny = f.hemisphere() > 0 ? -dx : dx;
            double at = 8 - carry;
            while (at < len) {
                double cx = x0 + dx * at;
                double cy = y0 + dy * at;
                boolean triangle = f.type() == 0 || (f.type() == 2 && symbol % 2 == 0);
                if (triangle) {
                    for (int t = -4; t <= 4; t++) {
                        line(image, cx + dx * t, cy + dy * t, cx + nx * 6, cy + ny * 6, colour, 1);
                    }
                } else {
                    for (int oy = -5; oy <= 5; oy++) {
                        for (int ox = -5; ox <= 5; ox++) {
                            if (ox * ox + oy * oy <= 22 && ox * nx + oy * ny >= 0) {
                                plot(image, (int) Math.round(cx + ox), (int) Math.round(cy + oy), colour);
                            }
                        }
                    }
                }
                symbol++;
                at += 16;
            }
            carry = len - (at - 16);
        }
    }

    /** A line of {@code thickness}-pixel dots in the overlay image. */
    private static void line(NativeImage image, double x0, double y0, double x1, double y1, int argb, int thickness) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        int n = Math.max(1, (int) Math.ceil(len));
        for (int i = 0; i <= n; i++) {
            int x = (int) Math.round(x0 + (x1 - x0) * i / n);
            int y = (int) Math.round(y0 + (y1 - y0) * i / n);
            for (int ty = 0; ty < thickness; ty++) {
                for (int tx = 0; tx < thickness; tx++) {
                    plot(image, x + tx, y + ty, argb);
                }
            }
        }
    }

    private static void plot(NativeImage image, int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= OVER || y >= OVER) {
            return;
        }
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        image.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
    }

    private void drawSystems(GuiGraphics g) {
        for (WeatherMapPayloads.Marker m : answer.systems()) {
            int x = (int) Math.round(sx(m.x()));
            int y = (int) Math.round(sy(m.z()));
            String letter = m.high() ? (m.blocking() ? "H blk" : "H") : "L";
            int colour = m.high() ? 0xFF5A8CFF : 0xFFFF5050;
            g.drawString(font, letter, x - font.width(letter) / 2, y - 8, colour);
            String p = String.format(Locale.ROOT, "%.0f", 1013 + (m.high() ? m.strength() : -m.strength()));
            g.drawString(font, p, x - font.width(p) / 2, y + 2, 0xFFE0E0E0);
        }
    }

    private void drawPanel(GuiGraphics g) {
        int px = mapX + mapSize + MARGIN;
        int y = mapY + Layer.values().length * 18 + 44;
        g.drawString(font, layer.label, px, y, 0xFFFFFFFF);
        y += 12;
        switch (layer) {
            case NOW, MEAN -> {
                gradient(g, px, y, -30, 45, WeatherMapScreen::temperatureColour);
                y += 12;
                g.drawString(font, "-30", px, y, 0xFFC0C0C0);
                g.drawString(font, "0", px + (int) (PANEL * 30 / 75.0) - 2, y, 0xFFC0C0C0);
                g.drawString(font, "45 C", px + PANEL - 22, y, 0xFFC0C0C0);
            }
            case HUMIDITY -> {
                gradient(g, px, y, 0, 1, WeatherMapScreen::humidityColour);
                y += 12;
                g.drawString(font, "dry", px, y, 0xFFC0C0C0);
                g.drawString(font, "humid", px + PANEL - 28, y, 0xFFC0C0C0);
            }
            case SURFACE -> {
                String[] names = {"land", "forest", "water", "ice", "always frozen"};
                for (int i = 0; i < names.length; i++) {
                    g.fill(px, y + 1, px + 8, y + 9, i < 4 ? surfaceColour(i) : 0xFFFFFFFF);
                    if (i == 4) {
                        g.fill(px + 2, y + 3, px + 6, y + 7, 0xFF6080C0);
                    }
                    g.drawString(font, names[i], px + 12, y + 1, 0xFFC0C0C0);
                    y += 11;
                }
                y -= 12;
            }
            case PRESSURE -> {
                gradient(g, px, y, 975, 1045, WeatherMapScreen::pressureColour);
                y += 12;
                g.drawString(font, "975", px, y, 0xFFC0C0C0);
                g.drawString(font, "1013", px + (int) (PANEL * 38 / 70.0) - 10, y, 0xFFC0C0C0);
                g.drawString(font, "1045", px + PANEL - 22, y, 0xFFC0C0C0);
                y += 11;
                g.drawString(font, "isobars every 4 hPa", px, y, 0xFF909090);
            }
            case AIR_MASS -> {
                gradient(g, px, y, -15, 15, WeatherMapScreen::anomalyColour);
                y += 12;
                g.drawString(font, "-15", px, y, 0xFFC0C0C0);
                g.drawString(font, "normal", px + PANEL / 2 - 15, y, 0xFFC0C0C0);
                g.drawString(font, "+15 C", px + PANEL - 28, y, 0xFFC0C0C0);
            }
            case MOISTURE -> {
                gradient(g, px, y, 0, 1, WeatherMapScreen::moistureColour);
                y += 12;
                g.drawString(font, "dry", px, y, 0xFFC0C0C0);
                g.drawString(font, "saturated", px + PANEL - 46, y, 0xFFC0C0C0);
                y += 11;
                g.drawString(font, "white dots: rain wrung out", px, y, 0xFF909090);
            }
        }
        y += 20;
        int blocks = niceLength(scale * SIZE / 4);
        int len = (int) Math.round(blocks * pixelsPerBlock());
        g.fill(px, y, px + len, y + 2, 0xFFFFFFFF);
        g.drawString(font, blocks >= 1000 ? (blocks / 1000) + " km" : blocks + " blocks", px, y + 5, 0xFFC0C0C0);
        y += 18;
        g.drawString(font, scale + " blocks per pixel", px, y, 0xFF909090);
        g.drawString(font, "Drag: pan  Scroll: zoom", px, y + 11, 0xFF909090);
    }

    private void drawHover(GuiGraphics g, int mouseX, int mouseY) {
        if (answer == null || !overMap(mouseX, mouseY)) {
            return;
        }
        double ppb = pixelsPerBlock();
        double wx = centreX + (mouseX - (mapX + mapSize / 2.0)) / ppb;
        double wz = centreZ + (mouseY - (mapY + mapSize / 2.0)) / ppb;
        double span = (double) answer.size() * answer.scale();
        int i = (int) Math.floor((wx - (answer.x() - span / 2)) / answer.scale());
        int k = (int) Math.floor((wz - (answer.z() - span / 2)) / answer.scale());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(String.format(Locale.ROOT, "x %d, z %d", (int) wx, (int) wz)));
        if (i >= 0 && k >= 0 && i < answer.size() && k < answer.size()) {
            int idx = k * answer.size() + i;
            int surface = answer.surface()[idx];
            String[] names = {"land", "forest", "water", "ice"};
            lines.add(Component.literal(String.format(Locale.ROOT, "Now %.1f C, annual mean %.1f C", answer.now()[idx],
                    answer.mean()[idx])));
            lines.add(Component.literal(String.format(Locale.ROOT, "Humidity %.0f%%, %s%s", 100 * answer.humidity()[idx],
                    names[surface & 3], (surface & 4) != 0 ? ", always frozen" : "")));
            lines.add(Component.literal(String.format(Locale.ROOT, "Pressure %.1f hPa", answer.pressure()[idx])));
            lines.add(Component.literal(String.format(Locale.ROOT, "Air mass %+.1f C from normal",
                    answer.anomaly()[idx])));
            lines.add(Component.literal(String.format(Locale.ROOT, "Moisture %.1f mm (%.0f%% relative), rain %.1f mm",
                    answer.moisture()[idx], 100 * answer.relative()[idx], answer.rain()[idx])));
        }
        WeatherMapPayloads.Marker nearest = null;
        double best = Double.POSITIVE_INFINITY;
        for (WeatherMapPayloads.Marker m : answer.systems()) {
            double d = Math.hypot(m.x() - wx, m.z() - wz);
            if (d < m.radius() && d < best) {
                best = d;
                nearest = m;
            }
        }
        if (nearest != null) {
            String stage = nearest.stage() >= 0 && nearest.stage() < STAGES.length ? STAGES[nearest.stage()] : "?";
            lines.add(Component.literal(String.format(Locale.ROOT, "%s%s, %s, %s%.0f hPa, day %.1f of %.1f",
                    nearest.high() ? "High" : "Low", nearest.blocking() ? " (blocking)" : "", stage,
                    nearest.high() ? "+" : "-", nearest.strength(), nearest.life() * nearest.lifetime() / 24000.0,
                    nearest.lifetime() / 24000.0)));
            if (nearest.hemisphere() < 0) {
                lines.add(Component.literal("Mirrored zone: turns the other way"));
            }
        }
        g.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    private static void gradient(GuiGraphics g, int x, int y, double from, double to,
                                 java.util.function.DoubleToIntFunction colour) {
        for (int i = 0; i < PANEL; i++) {
            double v = from + (to - from) * i / (PANEL - 1.0);
            g.fill(x + i, y, x + i + 1, y + 8, 0xFF000000 | colour.applyAsInt(v));
        }
    }

    /** Fills the texture from the answer in the current layer. */
    private void paint() {
        dirty = false;
        if (texture == null || answer == null || texture.getPixels() == null) {
            return;
        }
        NativeImage image = texture.getPixels();
        int n = answer.size();
        for (int k = 0; k < n; k++) {
            for (int i = 0; i < n; i++) {
                int idx = k * n + i;
                int rgb = switch (layer) {
                    case NOW -> temperatureColour(answer.now()[idx]);
                    case MEAN -> temperatureColour(answer.mean()[idx]);
                    case HUMIDITY -> humidityColour(answer.humidity()[idx]);
                    case SURFACE -> {
                        int s = answer.surface()[idx];
                        int c = surfaceColour(s & 3);
                        yield (s & 4) != 0 && ((i + k) & 1) == 0 ? 0xFFFFFF : c;
                    }
                    case PRESSURE -> isobar(idx, i, k, n) ? 0x303030 : pressureColour(answer.pressure()[idx]);
                    case AIR_MASS -> anomalyColour(answer.anomaly()[idx]);
                    case MOISTURE -> answer.rain()[idx] > 0.5 && ((i * 7 + k * 3) % 5 == 0)
                            ? 0xFFFFFF : moistureColour(answer.relative()[idx]);
                };
                image.setPixelRGBA(i, k, abgr(rgb));
            }
        }
        texture.upload();
    }

    private boolean isobar(int idx, int i, int k, int n) {
        int band = (int) Math.floor(answer.pressure()[idx] / 4);
        return (i + 1 < n && (int) Math.floor(answer.pressure()[idx + 1] / 4) != band)
                || (k + 1 < n && (int) Math.floor(answer.pressure()[idx + n] / 4) != band);
    }

    // ---- colours -----------------------------------------------------------------------------------------------

    private static final double[] T_STOPS = {-30, -15, 0, 10, 20, 30, 40};
    private static final int[] T_COLOURS = {0x3B0A6B, 0x2B5CD6, 0xBFE9FF, 0x7AC25A, 0xF2D94E, 0xE8873A, 0xB81D1D};

    static int temperatureColour(double t) {
        return ramp(t, T_STOPS, T_COLOURS);
    }

    private static final double[] H_STOPS = {0, 0.5, 1};
    private static final int[] H_COLOURS = {0xD9B77A, 0x8FCF7A, 0x1F6F8B};

    static int humidityColour(double h) {
        return ramp(h, H_STOPS, H_COLOURS);
    }

    private static final double[] P_STOPS = {975, 995, 1013, 1028, 1045};
    private static final int[] P_COLOURS = {0x2A2F8F, 0x7FA6E0, 0xE4E4E4, 0xF0C08A, 0xB0502A};

    static int pressureColour(double p) {
        return ramp(p, P_STOPS, P_COLOURS);
    }

    private static final double[] A_STOPS = {-15, -7, 0, 7, 15};
    private static final int[] A_COLOURS = {0x1E3C9A, 0x7FA6E0, 0xE8E8E8, 0xF0A070, 0xA82020};

    static int anomalyColour(double a) {
        return ramp(a, A_STOPS, A_COLOURS);
    }

    private static final double[] M_STOPS = {0, 0.3, 0.6, 0.85, 1};
    private static final int[] M_COLOURS = {0xC8A060, 0xD8D08A, 0x7CC07A, 0x3A8FB0, 0x1D4E8F};

    static int moistureColour(double rh) {
        return ramp(rh, M_STOPS, M_COLOURS);
    }

    static int surfaceColour(int ordinal) {
        return switch (ordinal) {
            case 1 -> 0x3F7D3A;
            case 2 -> 0x2F5FA8;
            case 3 -> 0xD8ECFF;
            default -> 0x9B8A5A;
        } | 0xFF000000;
    }

    private static int ramp(double v, double[] stops, int[] colours) {
        if (!Double.isFinite(v) || v <= stops[0]) {
            return colours[0];
        }
        for (int i = 1; i < stops.length; i++) {
            if (v <= stops[i]) {
                double f = (v - stops[i - 1]) / (stops[i] - stops[i - 1]);
                return lerp(colours[i - 1], colours[i], f);
            }
        }
        return colours[colours.length - 1];
    }

    private static int lerp(int a, int b, double f) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
        int gr = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
        return (r << 16) | (gr << 8) | bl;
    }

    /** NativeImage stores pixels as ABGR. */
    private static int abgr(int rgb) {
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    private static int niceLength(int blocks) {
        int[] steps = {1, 2, 5};
        int best = 100;
        for (int p = 100; p <= 1_000_000; p *= 10) {
            for (int s : steps) {
                if (s * p <= blocks) {
                    best = s * p;
                }
            }
        }
        return best;
    }

    // ---- input -------------------------------------------------------------------------------------------------

    private boolean overMap(double mx, double my) {
        return mx >= mapX && my >= mapY && mx < mapX + mapSize && my < mapY + mapSize;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overMap(mouseX, mouseY)) {
            dragging = true;
            dragMouseX = mouseX;
            dragMouseY = mouseY;
            dragCentreX = centreX;
            dragCentreZ = centreZ;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            double ppb = pixelsPerBlock();
            centreX = dragCentreX - (mouseX - dragMouseX) / ppb;
            centreZ = dragCentreZ - (mouseY - dragMouseY) / ppb;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            requestIn = 2;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!overMap(mouseX, mouseY) || scrollY == 0) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        int next = scrollY > 0 ? scale / 2 : scale * 2;
        next = Math.max(WeatherMapPayloads.MIN_SCALE, Math.min(WeatherMapPayloads.MAX_SCALE, next));
        if (next != scale) {
            double before = pixelsPerBlock();
            double wx = centreX + (mouseX - (mapX + mapSize / 2.0)) / before;
            double wz = centreZ + (mouseY - (mapY + mapSize / 2.0)) / before;
            scale = next;
            double after = pixelsPerBlock();
            centreX = wx - (mouseX - (mapX + mapSize / 2.0)) / after;
            centreZ = wz - (mouseY - (mapY + mapSize / 2.0)) / after;
            requestIn = 6;
        }
        return true;
    }
}
