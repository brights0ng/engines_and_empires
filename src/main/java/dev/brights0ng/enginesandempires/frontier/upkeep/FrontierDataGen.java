package dev.brights0ng.enginesandempires.frontier.upkeep;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import com.google.common.hash.Hashing;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.Util;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Writes Frontier's textures (drawn by {@link FrontierTextures}). Its block states, models and loot table are plain files
 * under {@code src/main/resources}, since they are short and never change.
 */
public final class FrontierDataGen {

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeClient(), new Textures(event.getGenerator().getPackOutput()));
    }

    public static final class Textures implements DataProvider {

        private final PackOutput output;

        public Textures(PackOutput output) {
            this.output = output;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            return CompletableFuture.runAsync(() -> {
                Path root = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(EnginesAndEmpiresMod.MODID)
                        .resolve("textures");
                for (Map.Entry<String, BufferedImage> texture : FrontierTextures.all().entrySet()) {
                    try {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        ImageIO.write(texture.getValue(), "png", bytes);
                        byte[] data = bytes.toByteArray();
                        cache.writeIfNeeded(root.resolve(texture.getKey() + ".png"), data, Hashing.sha1().hashBytes(data));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }, Util.backgroundExecutor());
        }

        @Override
        public String getName() {
            return "Frontier textures";
        }
    }

    private FrontierDataGen() {
    }
}
