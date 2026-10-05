package dev.brights0ng.enginesandempires.geophone;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import javax.imageio.ImageIO;

import com.google.common.hash.Hashing;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import net.minecraft.Util;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.CachedOutput;
import net.minecraft.data.DataProvider;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.SimpleCookingRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.client.model.generators.BlockModelBuilder;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Generates the seismic survey's models, textures and recipes. It is called from the mod's data generator, which
 * owns the providers for block states, loot and tags: only one of each can exist, so the pieces that belong to
 * those are contributed here as plain functions, and the ones that are new (recipes, textures) are providers here.
 */
public final class SeismicDataGen {

    private static final String MODID = EnginesAndEmpiresMod.MODID;

    /**
     * Tells the model generator that the textures exist. They are written by this same run, so it cannot find them
     * on disk to check.
     */
    public static void trackTextures(ExistingFileHelper existing) {
        for (String path : allTextures().keySet()) {
            existing.trackGenerated(ResourceLocation.fromNamespaceAndPath(MODID, path), ModelProvider.TEXTURE);
        }
        ThumperDataGen.trackCreateTextures(existing);
        CombustiveDataGen.trackCreateTextures(existing);
        LoggerDataGen.trackCreateTextures(existing);
        PrdDataGen.trackCreateTextures(existing);
    }

    /** Every texture this package draws: the sledgehammer, plate, geophone, wind-up reader, logbook and thumper. */
    static Map<String, BufferedImage> allTextures() {
        Map<String, BufferedImage> all = new LinkedHashMap<>(SeismicTextures.all());
        all.putAll(ReaderTextures.all());
        all.putAll(LogbookTextures.all());
        all.putAll(ThumperTextures.all());
        all.putAll(LoggerTextures.all());
        all.putAll(PrdTextures.all());
        return all;
    }

    /** The block states and models (and item models) of everything in {@link SeismicContent}. */
    public static void registerModels(BlockStateProvider provider) {
        // The strike plate: a flat slab, two pixels thick.
        ResourceLocation plateTexture = provider.modLoc("block/strike_plate");
        BlockModelBuilder plate = provider.models().getBuilder("strike_plate")
                .texture("particle", plateTexture)
                .texture("plate", plateTexture);
        plate.element().from(1, 0, 1).to(15, 2, 15).allFaces((direction, face) -> face.texture("#plate")).end();
        provider.simpleBlock(SeismicContent.STRIKE_PLATE.get(), plate);
        provider.itemModels().withExistingParent("strike_plate", provider.modLoc("block/strike_plate"));

        // The geophone is an entity, drawn by GeophoneRenderer from three small models: the body, and the sensor cap dark
        // and glowing. They belong to no block, so there is no blockstate: the renderer asks for them by name. Each is drawn
        // standing upright with y = 0 at the face the geophone is staked into, and the renderer tips it to point out of that
        // face. The body reaches below y = 0: that is the spike, which is driven into the block, and the crossguard sits
        // right at the face to stop it.
        ResourceLocation rodTexture = provider.modLoc("block/andesite_geophone");
        ResourceLocation guardTexture = provider.modLoc("block/andesite_geophone_guard");
        BlockModelBuilder body = provider.models().getBuilder("andesite_geophone_body")
                .texture("particle", rodTexture)
                .texture("rod", rodTexture)
                .texture("guard", guardTexture);
        box(body, "#rod", 7, -8, 7, 9, 0, 9);     // the spike, below the face
        box(body, "#guard", 5, 0, 5, 11, 1, 11);  // the crossguard, which stops it against the face
        box(body, "#rod", 7, 1, 7, 9, 8, 9);      // the rod above it
        for (String cap : List.of("andesite_geophone_cap", "andesite_geophone_cap_lit")) {
            ResourceLocation capTexture = provider.modLoc("block/" + cap);
            BlockModelBuilder model = provider.models().getBuilder(cap)
                    .texture("particle", capTexture)
                    .texture("cap", capTexture);
            box(model, "#cap", 6, 8, 6, 10, 12, 10); // the sensor, on top of the rod
        }
        provider.itemModels().withExistingParent("andesite_geophone", "item/generated")
                .texture("layer0", provider.modLoc("item/andesite_geophone"));

        // The brass geophone: the same shape as andesite's, in its own warmer palette, with a neutral (colourless) glow
        // model instead of a baked-amber one, since GeophoneRenderer tints it per ore.
        ResourceLocation brassRodTexture = provider.modLoc("block/brass_geophone");
        ResourceLocation brassGuardTexture = provider.modLoc("block/brass_geophone_guard");
        BlockModelBuilder brassBody = provider.models().getBuilder("brass_geophone_body")
                .texture("particle", brassRodTexture)
                .texture("rod", brassRodTexture)
                .texture("guard", brassGuardTexture);
        box(brassBody, "#rod", 7, -8, 7, 9, 0, 9);
        box(brassBody, "#guard", 5, 0, 5, 11, 1, 11);
        box(brassBody, "#rod", 7, 1, 7, 9, 8, 9);

        ResourceLocation brassCapTexture = provider.modLoc("block/brass_geophone_cap");
        BlockModelBuilder brassCap = provider.models().getBuilder("brass_geophone_cap")
                .texture("particle", brassCapTexture)
                .texture("cap", brassCapTexture);
        box(brassCap, "#cap", 6, 8, 6, 10, 12, 10);

        // The glowing cap's texture is deliberately colourless (see SeismicTextures#brassCapLit): a plain grey-to-white
        // gradient that GeophoneRenderer recolours per ore at render time. That only works if these faces are marked as
        // tinted (a "tintindex"): a face's own colour is otherwise fixed at load time, and any red/green/blue GeophoneRenderer
        // passes to draw it is silently ignored, which is exactly what made every brass geophone glow the same untinted white
        // regardless of what ore lit it up.
        ResourceLocation brassCapLitTexture = provider.modLoc("block/brass_geophone_cap_lit");
        BlockModelBuilder brassCapLit = provider.models().getBuilder("brass_geophone_cap_lit")
                .texture("particle", brassCapLitTexture)
                .texture("cap", brassCapLitTexture);
        tintedBox(brassCapLit, "#cap", 6, 8, 6, 10, 12, 10);
        provider.itemModels().withExistingParent("brass_geophone", "item/generated")
                .texture("layer0", provider.modLoc("item/brass_geophone"));

        provider.itemModels().withExistingParent("sledgehammer", "item/handheld")
                .texture("layer0", provider.modLoc("item/sledgehammer"));

        provider.itemModels().withExistingParent("logbook", "item/generated")
                .texture("layer0", provider.modLoc("item/logbook"));

        // The portable record display: a 3D model, drawn by its own item renderer (see PrdDataGen).
        PrdDataGen.registerModels(provider);

        ReaderDataGen.registerModels(provider);
        ThumperDataGen.registerModels(provider);
        LoggerDataGen.registerModels(provider);
    }

    /**
     * Adds one box to a model, every face showing the middle of the texture.
     *
     * <p>The middle is used because the default texture coordinates follow the box's position in the block. For a box below
     * the face, such as the spike, that lands off the edge of the texture, and picks up whatever happens to be next to it
     * on the block atlas.
     */
    static void box(BlockModelBuilder model, String texture, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        box(model, texture, fromX, fromY, fromZ, toX, toY, toZ, false);
    }

    /**
     * The same box as {@link #box}, but with its faces marked as tinted (tintindex 0), so a renderer can recolour the
     * texture at render time by passing red/green/blue into {@code ModelBlockRenderer.renderModel}. A face's colour is
     * otherwise fixed once baked: any tint a renderer passes for an untinted face is silently ignored.
     */
    static void tintedBox(BlockModelBuilder model, String texture, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        box(model, texture, fromX, fromY, fromZ, toX, toY, toZ, true);
    }

    private static void box(BlockModelBuilder model, String texture, int fromX, int fromY, int fromZ, int toX, int toY, int toZ,
                            boolean tinted) {
        int sizeX = toX - fromX;
        int sizeY = toY - fromY;
        int sizeZ = toZ - fromZ;
        model.element().from(fromX, fromY, fromZ).to(toX, toY, toZ).allFaces((direction, face) -> {
            int across;
            int up;
            if (direction.getAxis().isVertical()) {        // top and bottom
                across = sizeX;
                up = sizeZ;
            } else if (direction.getStepX() != 0) {        // east and west
                across = sizeZ;
                up = sizeY;
            } else {                                       // north and south
                across = sizeX;
                up = sizeY;
            }
            face.texture(texture).uvs(8 - across / 2.0F, 8 - up / 2.0F, 8 + across / 2.0F, 8 + up / 2.0F);
            if (tinted) {
                face.tintindex(0);
            }
        }).end();
    }

    /**
     * The recipes. These are placeholders to make everything obtainable in survival while the pack is built: they use
     * Create's andesite alloy, and are meant to be replaced once the pack's progression is designed.
     */
    public static final class Recipes extends RecipeProvider {

        public Recipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
            super(output, registries);
        }

        @Override
        protected void buildRecipes(RecipeOutput output) {
            Item alloy = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("create:andesite_alloy"))
                    .orElseThrow(() -> new IllegalStateException("Create's andesite alloy is missing: is Create loaded in the data run?"));
            Item brassIngot = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("create:brass_ingot"))
                    .orElseThrow(() -> new IllegalStateException("Create's brass ingot is missing: is Create loaded in the data run?"));
            Item cogwheel = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("create:cogwheel"))
                    .orElseThrow(() -> new IllegalStateException("Create's cogwheel is missing: is Create loaded in the data run?"));

            ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, SeismicContent.SLEDGEHAMMER.get())
                    .pattern("IAI")
                    .pattern(" S ")
                    .pattern(" S ")
                    .define('I', Items.IRON_INGOT)
                    .define('A', alloy)
                    .define('S', Items.STICK)
                    .unlockedBy("has_andesite_alloy", has(alloy))
                    .save(output);

            ShapedRecipeBuilder.shaped(RecipeCategory.BUILDING_BLOCKS, SeismicContent.STRIKE_PLATE.get())
                    .pattern("IAI")
                    .define('I', Items.IRON_INGOT)
                    .define('A', alloy)
                    .unlockedBy("has_andesite_alloy", has(alloy))
                    .save(output);

            ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, SeismicContent.ANDESITE_GEOPHONE.get())
                    .pattern(" A ")
                    .pattern(" I ")
                    .pattern(" I ")
                    .define('I', Items.IRON_INGOT)
                    .define('A', alloy)
                    .unlockedBy("has_andesite_alloy", has(alloy))
                    .save(output);

            // Placeholder: the same shape as the andesite geophone, in brass instead of iron.
            ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, SeismicContent.BRASS_GEOPHONE.get())
                    .pattern(" A ")
                    .pattern(" B ")
                    .pattern(" B ")
                    .define('B', brassIngot)
                    .define('A', alloy)
                    .unlockedBy("has_andesite_alloy", has(alloy))
                    .save(output);

            // Placeholder: a compass at the heart of a brass-and-iron clockwork case.
            ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, SeismicContent.WINDUP_READER.get())
                    .pattern(" A ")
                    .pattern("ICI")
                    .pattern(" I ")
                    .define('I', Items.IRON_INGOT)
                    .define('A', alloy)
                    .define('C', Items.COMPASS)
                    .unlockedBy("has_andesite_alloy", has(alloy))
                    .save(output);

            // Placeholder: a book bound with iron, and a little andesite alloy for the board on its cover.
            ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, SeismicContent.LOGBOOK.get())
                    .pattern(" A ")
                    .pattern("IBI")
                    .define('I', Items.IRON_INGOT)
                    .define('A', alloy)
                    .define('B', Items.BOOK)
                    .unlockedBy("has_andesite_alloy", has(alloy))
                    .save(output);

            // Bright's (2026-09-26), in Create's usual column layout: andesite casing over a cogwheel over an iron block.
            Item andesiteCasing = createItem("andesite_casing");
            ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, SeismicContent.MECHANICAL_THUMPER.get())
                    .pattern("A")
                    .pattern("C")
                    .pattern("I")
                    .define('A', andesiteCasing)
                    .define('C', cogwheel)
                    .define('I', Items.IRON_BLOCK)
                    .unlockedBy("has_andesite_casing", has(andesiteCasing))
                    .save(output);

            // Bright's (2026-09-26): a pipe-fed copper machine. A fluid tank over an iron block between iron bars, over a
            // copper casing between copper sheets.
            Item fluidTank = createItem("fluid_tank");
            Item copperSheet = createItem("copper_sheet");
            Item copperCasing = createItem("copper_casing");
            ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, SeismicContent.COMBUSTIVE_THUMPER.get())
                    .pattern(" F ")
                    .pattern("BIB")
                    .pattern("CQC")
                    .define('F', fluidTank)
                    .define('B', Items.IRON_BARS)
                    .define('I', Items.IRON_BLOCK)
                    .define('C', copperSheet)
                    .define('Q', copperCasing)
                    .unlockedBy("has_copper_casing", has(copperCasing))
                    .save(output);

            // A choked thumper, cooked (in a furnace, or by Create's bulk blasting), burns the sculk off and works again.
            SimpleCookingRecipeBuilder.smelting(Ingredient.of(SeismicContent.CHOKED_MECHANICAL_THUMPER.get()),
                            RecipeCategory.REDSTONE, SeismicContent.MECHANICAL_THUMPER.get(), 0.35F, 200)
                    .unlockedBy("has_choked_mechanical_thumper", has(SeismicContent.CHOKED_MECHANICAL_THUMPER.get()))
                    .save(output, ResourceLocation.fromNamespaceAndPath(MODID, "mechanical_thumper_from_choked"));
            SimpleCookingRecipeBuilder.smelting(Ingredient.of(SeismicContent.CHOKED_COMBUSTIVE_THUMPER.get()),
                            RecipeCategory.REDSTONE, SeismicContent.COMBUSTIVE_THUMPER.get(), 0.35F, 200)
                    .unlockedBy("has_choked_combustive_thumper", has(SeismicContent.CHOKED_COMBUSTIVE_THUMPER.get()))
                    .save(output, ResourceLocation.fromNamespaceAndPath(MODID, "combustive_thumper_from_choked"));

            // Placeholder: brass casing around a cogwheel, a clock-face of glass and a compass, like a big wind-up reader.
            Item brassCasing = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse("create:brass_casing"))
                    .orElseThrow(() -> new IllegalStateException("Create's brass casing is missing: is Create loaded in the data run?"));
            ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, SeismicContent.SMART_LOGGER.get())
                    .pattern("GGG")
                    .pattern("BRB")
                    .pattern("CBC")
                    .define('G', Items.GLASS_PANE)
                    .define('B', brassCasing)
                    .define('R', SeismicContent.WINDUP_READER.get())
                    .define('C', cogwheel)
                    .unlockedBy("has_windup_reader", has(SeismicContent.WINDUP_READER.get()))
                    .save(output);

            // Placeholder: a small brass slate, glass over a compass, like a smart logger made to carry.
            ShapedRecipeBuilder.shaped(RecipeCategory.TOOLS, SeismicContent.PORTABLE_RECORD_DISPLAY.get())
                    .pattern(" G ")
                    .pattern("BCB")
                    .pattern(" R ")
                    .define('G', Items.GLASS_PANE)
                    .define('B', brassIngot)
                    .define('C', Items.COMPASS)
                    .define('R', Items.REDSTONE)
                    .unlockedBy("has_smart_logger", has(SeismicContent.SMART_LOGGER.get()))
                    .save(output);
        }

        private static Item createItem(String name) {
            return BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("create", name))
                    .orElseThrow(() -> new IllegalStateException("Create's " + name + " is missing: is Create loaded in the data run?"));
        }
    }

    /** Writes the textures drawn by {@link SeismicTextures} as PNG files. */
    public static final class Textures implements DataProvider {

        private final PackOutput output;

        public Textures(PackOutput output) {
            this.output = output;
        }

        @Override
        public CompletableFuture<?> run(CachedOutput cache) {
            return CompletableFuture.runAsync(() -> {
                Path root = output.getOutputFolder(PackOutput.Target.RESOURCE_PACK).resolve(MODID).resolve("textures");
                for (Map.Entry<String, BufferedImage> texture : allTextures().entrySet()) {
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
            return "Seismic textures";
        }
    }

    private SeismicDataGen() {
    }
}
