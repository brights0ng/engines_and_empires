package dev.brights0ng.enginesandempires.food.icechest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Writes the ice chest's textures (made by {@link IceChestTextures} from vanilla's barrel, iron block and white wool). Its
 * block states, models, loot table, recipe and tags are plain files under {@code src/main/resources}.
 */
public final class IceChestDataGen {

    public static void gatherData(GatherDataEvent event) {
        event.getGenerator().addProvider(event.includeClient(),
                new Textures(event.getGenerator().getPackOutput(), event.getExistingFileHelper()));
    }

    public static final class Textures implements DataProvider {

        private final PackOutput output;
        private final ExistingFileHelper existing;

        public Textures(PackOutput output, ExistingFileHelper existing) {
            this.output = output;
            this.existing = existing;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            return CompletableFuture.runAsync(() -> {
                Path root = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(EnginesAndEmpiresMod.MODID)
                        .resolve("textures");
                for (Map.Entry<String, BufferedImage> texture : IceChestTextures.all(this::vanilla).entrySet()) {
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

        /** A vanilla block texture (the first frame, should it be animated). */
        private BufferedImage vanilla(String name) {
            ResourceLocation id = ResourceLocation.withDefaultNamespace("block/" + name);
            try (InputStream in = existing.getResource(id, PackType.CLIENT_RESOURCES, ".png", "textures").open()) {
                BufferedImage image = ImageIO.read(in);
                return image.getSubimage(0, 0, 16, 16);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read vanilla texture " + id, e);
            }
        }

        @Override
        public String getName() {
            return "Ice chest textures";
        }
    }

    private IceChestDataGen() {
    }
}
