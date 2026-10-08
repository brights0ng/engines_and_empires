package dev.brights0ng.enginesandempires.weather.surface;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import com.google.common.hash.Hashing;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.Util;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Writes glaze's client assets: its texture ({@link GlazeTextures}, from vanilla ice), its block state, a model per
 * state (1-2 pixel glaze on 0-7 snow layers) and its item model. Its loot table, tags and the hail damage type are
 * plain files under {@code src/main/resources}.
 */
public final class SurfaceDataGen {

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeClient(),
                new Assets(event.getGenerator().getPackOutput(), event.getExistingFileHelper()));
    }

    /** The model for glaze {@code layers} pixels thick on {@code snow} snow layers. */
    static String modelName(int layers, int snow) {
        return snow == 0 ? "glaze_" + layers : "glaze_" + layers + "_on_snow_" + snow;
    }

    static String blockState() {
        StringBuilder sb = new StringBuilder("{\n  \"variants\": {\n");
        boolean first = true;
        for (int layers = 1; layers <= GlazeBlock.MAX; layers++) {
            for (int snow = 0; snow <= 7; snow++) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("    \"layers=").append(layers).append(",snow=").append(snow).append("\": { \"model\": \"")
                        .append(EnginesAndEmpiresMod.MODID).append(":block/").append(modelName(layers, snow))
                        .append("\" }");
            }
        }
        return sb.append("\n  }\n}\n").toString();
    }

    /**
     * A model: a snow slab {@code snow * 2} pixels tall (its top shows through the glaze), then the glaze sheet. The
     * glaze has no bottom face (on bare ground it would only be culled; on leaves it would fight their top face).
     */
    static String model(int layers, int snow) {
        int s = snow * 2;
        int top = s + layers;
        String glaze = EnginesAndEmpiresMod.MODID + ":block/glaze";
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"parent\": \"minecraft:block/block\",\n  \"render_type\": \"minecraft:translucent\",\n");
        sb.append("  \"textures\": { \"particle\": \"").append(glaze).append("\", \"glaze\": \"").append(glaze)
                .append("\", \"snow\": \"minecraft:block/snow\" },\n  \"elements\": [\n");
        if (s > 0) {
            sb.append("    { \"from\": [0, 0, 0], \"to\": [16, ").append(s).append(", 16], \"faces\": {\n");
            sb.append("      \"down\": { \"uv\": [0, 0, 16, 16], \"texture\": \"#snow\", \"cullface\": \"down\" },\n");
            sb.append("      \"up\": { \"uv\": [0, 0, 16, 16], \"texture\": \"#snow\" },\n");
            sides(sb, "#snow", 16 - s, 16);
            sb.append("    } },\n");
        }
        sb.append("    { \"from\": [0, ").append(s).append(", 0], \"to\": [16, ").append(top)
                .append(", 16], \"faces\": {\n");
        sb.append("      \"up\": { \"uv\": [0, 0, 16, 16], \"texture\": \"#glaze\"")
                .append(top == 16 ? ", \"cullface\": \"up\"" : "").append(" },\n");
        sides(sb, "#glaze", 16 - top, 16 - s);
        sb.append("    } }\n  ]\n}\n");
        return sb.toString();
    }

    private static void sides(StringBuilder sb, String texture, int v0, int v1) {
        String[] dirs = {"north", "south", "west", "east"};
        for (int i = 0; i < dirs.length; i++) {
            sb.append("      \"").append(dirs[i]).append("\": { \"uv\": [0, ").append(v0).append(", 16, ").append(v1)
                    .append("], \"texture\": \"").append(texture).append("\", \"cullface\": \"").append(dirs[i])
                    .append("\" }").append(i < dirs.length - 1 ? ",\n" : "\n");
        }
    }

    static String itemModel() {
        return "{\n  \"parent\": \"" + EnginesAndEmpiresMod.MODID + ":block/" + modelName(GlazeBlock.MAX, 0) + "\"\n}\n";
    }

    public static final class Assets implements DataProvider {

        private final PackOutput output;
        private final ExistingFileHelper existing;

        public Assets(PackOutput output, ExistingFileHelper existing) {
            this.output = output;
            this.existing = existing;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            return CompletableFuture.runAsync(() -> {
                Path root = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(EnginesAndEmpiresMod.MODID);
                try {
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(GlazeTextures.glaze(vanillaIce()), "png", png);
                    write(cache, root.resolve("textures/block/glaze.png"), png.toByteArray());
                    write(cache, root.resolve("blockstates/glaze.json"), blockState());
                    for (int layers = 1; layers <= GlazeBlock.MAX; layers++) {
                        for (int snow = 0; snow <= 7; snow++) {
                            write(cache, root.resolve("models/block/" + modelName(layers, snow) + ".json"),
                                    model(layers, snow));
                        }
                    }
                    write(cache, root.resolve("models/item/glaze.json"), itemModel());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, Util.backgroundExecutor());
        }

        private static void write(CachedOutput cache, Path path, String text) throws IOException {
            write(cache, path, text.getBytes(StandardCharsets.UTF_8));
        }

        private static void write(CachedOutput cache, Path path, byte[] data) throws IOException {
            cache.writeIfNeeded(path, data, Hashing.sha1().hashBytes(data));
        }

        private BufferedImage vanillaIce() {
            ResourceLocation id = ResourceLocation.withDefaultNamespace("block/ice");
            try (InputStream in = existing.getResource(id, PackType.CLIENT_RESOURCES, ".png", "textures").open()) {
                return ImageIO.read(in).getSubimage(0, 0, 16, 16);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read vanilla texture " + id, e);
            }
        }

        @Override
        public String getName() {
            return "Glaze assets";
        }
    }

    private SurfaceDataGen() {
    }
}
