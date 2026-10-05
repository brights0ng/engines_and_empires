package dev.brights0ng.enginesandempires.oregen.refining;

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
import java.util.function.BiConsumer;

import javax.imageio.ImageIO;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableSubProvider;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * The data generator's providers for ore refining: the recipes of {@link RefiningRecipes}, the chips' item models and
 * textures, and the iron golem's cut-down drop. Ore block loot tables are generated with the rich ones, in
 * {@code RichOreDataGen}, from the same {@code RichOres} table. Called from {@code RichOreDataGen.gatherData}.
 */
public final class RefiningDataGen {

    private static final String MODID = EnginesAndEmpiresMod.MODID;

    /** Writes {@link RefiningRecipes#files()}. */
    public static final class Recipes implements DataProvider {

        private final PackOutput output;

        public Recipes(PackOutput output) {
            this.output = output;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            Path root = output.getOutputFolder(PackOutput.Target.DATA_PACK);
            List<CompletableFuture<?>> writes = new ArrayList<>();
            for (Map.Entry<String, JsonObject> file : RefiningRecipes.files().entrySet()) {
                writes.add(DataProvider.saveStable(cache, file.getValue(), root.resolve(file.getKey())));
            }
            return CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        }

        @Override
        public String getName() {
            return "Ore refining recipes";
        }
    }

    /** The iron golem drops nuggets, cut like raw ore: 3-5 ingots become 6-10 nuggets. Its poppies are kept. */
    public static final class GolemLoot implements LootTableSubProvider {

        public GolemLoot(HolderLookup.Provider registries) {
        }

        @Override
        public void generate(BiConsumer<ResourceKey<LootTable>, LootTable.Builder> output) {
            int min = (int) Math.round(3 * 9 * Refining.OTHER_SOURCES);
            int max = (int) Math.round(5 * 9 * Refining.OTHER_SOURCES);
            output.accept(EntityType.IRON_GOLEM.getDefaultLootTable(), LootTable.lootTable()
                    .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                            .add(LootItem.lootTableItem(Items.POPPY)
                                    .apply(SetItemCountFunction.setCount(UniformGenerator.between(0, 2)))))
                    .withPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                            .add(LootItem.lootTableItem(Items.IRON_NUGGET)
                                    .apply(SetItemCountFunction.setCount(UniformGenerator.between(min, max))))));
        }
    }

    /** Tells the model generator the chip textures exist: they are written by this same run. */
    public static void trackTextures(ExistingFileHelper existing) {
        for (Refining.Chip chip : Refining.CHIPS) {
            existing.trackGenerated(ResourceLocation.fromNamespaceAndPath(MODID, "item/" + chip.name()), ModelProvider.TEXTURE);
        }
    }

    /** The chips' item models: flat items, like coal. */
    public static void registerModels(BlockStateProvider provider) {
        for (Refining.Chip chip : Refining.CHIPS) {
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
                for (Refining.Chip chip : Refining.CHIPS) {
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

    private RefiningDataGen() {
    }
}
