package dev.brights0ng.sparseores.chips;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;

import dev.brights0ng.sparseores.SparseOres;
import net.minecraft.Util;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * The data generator's providers for the chips (moved from the core's refining in split seam S15): the files of
 * {@link ChipRecipes} (the 2x2 recipes and the coal chip's burn time), and the chips' textures, drawn by
 * {@link ChipTextures}. Their item models go into Sparse Ores' one block state provider ({@link #registerModels},
 * {@code SparseOresDataGen}).
 */
public final class ChipDataGen {

    private static final String MODID = SparseOres.MODID;

    /** Registered on the mod event bus by the mod class. */
    public static void gatherData(GatherDataEvent event) {
        PackOutput output = event.getGenerator().getPackOutput();
        ExistingFileHelper existing = event.getExistingFileHelper();
        trackTextures(existing);
        event.getGenerator().addProvider(event.includeServer(), new Files(output));
        event.getGenerator().addProvider(event.includeClient(), new Textures(output, existing));
    }

    /** Writes {@link ChipRecipes#files()}. */
    public static final class Files implements DataProvider {

        private final PackOutput output;

        public Files(PackOutput output) {
            this.output = output;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path root = output.getOutputFolder(PackOutput.Target.DATA_PACK);
            List<CompletableFuture<?>> writes = new ArrayList<>();
            for (Map.Entry<String, JsonObject> file : ChipRecipes.files().entrySet()) {
                writes.add(DataProvider.saveStable(cache, file.getValue(), root.resolve(file.getKey())));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Chip recipes and fuel";
        }
    }

    /** Tells the model generator the chip textures exist: they are written by this same run. */
    public static void trackTextures(ExistingFileHelper existing) {
        for (Chips.Chip chip : Chips.ALL) {
            existing.trackGenerated(ResourceLocation.fromNamespaceAndPath(MODID, "item/" + chip.name()), ModelProvider.TEXTURE);
        }
    }

    /** The chips' item models: flat items, like coal. */
    public static void registerModels(BlockStateProvider provider) {
        for (Chips.Chip chip : Chips.ALL) {
            provider.itemModels().withExistingParent(chip.name(), "item/generated")
                    .texture("layer0", provider.modLoc("item/" + chip.name()));
        }
    }

    /** Writes the chip textures drawn by {@link ChipTextures}, from the vanilla items' own textures. */
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
                Path root = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(MODID).resolve("textures/item");
                for (Chips.Chip chip : Chips.ALL) {
                    try {
                        BufferedImage image = ChipTextures.chip(vanillaItem(chip.item()));
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        ImageIO.write(image, "png", bytes);
                        byte[] data = bytes.toByteArray();
                        cache.writeIfNeeded(root.resolve(chip.name() + ".png"), data, Hashing.sha1().hashBytes(data));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            }, Util.backgroundExecutor());
        }

        /** A vanilla item's texture, such as {@code minecraft:coal}'s, from the game's own resources. */
        private BufferedImage vanillaItem(String itemId) throws IOException {
            ResourceLocation item = ResourceLocation.parse(itemId);
            ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(item.getNamespace(), "textures/item/" + item.getPath() + ".png");
            try (InputStream in = existing.getResource(texture, PackType.CLIENT_RESOURCES).open()) {
                return ImageIO.read(in);
            }
        }

        @Override
        public String getName() {
            return "Chip textures";
        }
    }

    private ChipDataGen() {
    }
}
