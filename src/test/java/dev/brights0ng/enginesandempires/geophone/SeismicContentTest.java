package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/**
 * Checks that the data generator has produced everything the sledgehammer, strike plate and geophone need. If one of
 * these fails, run the {@code Data} run configuration.
 */
class SeismicContentTest {

    private static final Path GENERATED = Path.of("src/generated/resources");
    private static final Path MAIN = Path.of("src/main/resources");
    private static final String ASSETS = "assets/engines_and_empires/";
    private static final String DATA = "data/engines_and_empires/";

    /** The strike plate is the only block. The geophone is an entity, so it has no block state or block loot. */
    private static final List<String> BLOCKS = List.of("strike_plate");
    private static final List<String> ITEMS = List.of("sledgehammer", "strike_plate", "andesite_geophone", "brass_geophone", "windup_reader", "logbook");
    /** The three pieces each geophone tier is drawn from. */
    private static final List<String> GEOPHONE_PARTS = List.of(
            "andesite_geophone_body", "andesite_geophone_cap", "andesite_geophone_cap_lit",
            "brass_geophone_body", "brass_geophone_cap", "brass_geophone_cap_lit");

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Missing " + path + ": has the Data run been done?", e);
        }
    }

    private static String generated(String relative) {
        return read(GENERATED.resolve(relative));
    }

    @Test
    void everyTextureExistsAndIsSixteenBySixteen() throws IOException {
        for (String texture : SeismicTextures.all().keySet()) {
            Path path = GENERATED.resolve(ASSETS + "textures/" + texture + ".png");
            assertTrue(Files.exists(path), path + " is missing");
            BufferedImage image = ImageIO.read(path.toFile());
            assertNotNull(image, path + " is not a readable image");
            assertEquals(16, image.getWidth(), texture);
            assertEquals(16, image.getHeight(), texture);
        }
    }

    @Test
    void theStrikePlateHasABlockstateAndModel() {
        for (String block : BLOCKS) {
            assertTrue(generated(ASSETS + "blockstates/" + block + ".json").contains("engines_and_empires:block/" + block), block);
            assertTrue(generated(ASSETS + "models/block/" + block + ".json").contains("\"elements\""), block);
        }
    }

    @Test
    void everyItemHasAModel() {
        for (String item : ITEMS) {
            assertTrue(Files.exists(GENERATED.resolve(ASSETS + "models/item/" + item + ".json")), item);
        }
    }

    /** The renderer asks for these by name; if one is missing the geophone is drawn as nothing at all. */
    @Test
    void everyPieceOfTheGeophoneHasAModel() {
        for (String part : GEOPHONE_PARTS) {
            assertTrue(generated(ASSETS + "models/block/" + part + ".json").contains("\"elements\""), part);
        }
    }

    /** It is an entity now: there must be no leftover block state or block loot for a geophone block that no longer exists. */
    @Test
    void thereIsNoLeftoverGeophoneBlock() {
        assertFalse(Files.exists(GENERATED.resolve(ASSETS + "blockstates/andesite_geophone.json")), "stale blockstate");
        assertFalse(Files.exists(GENERATED.resolve(DATA + "loot_table/blocks/andesite_geophone.json")), "stale loot table");
        assertFalse(Files.exists(GENERATED.resolve(ASSETS + "models/block/andesite_geophone.json")), "stale model");
        assertFalse(Files.exists(GENERATED.resolve(ASSETS + "models/block/andesite_geophone_lit.json")), "stale model");
    }

    /**
     * The parts are drawn standing upright with the face of the block at y = 0: a spike below it, the crossguard right on it,
     * then the rod, then the cap. Stacked, the part above the face is exactly as tall as the hitbox.
     */
    @Test
    void thePartsStackIntoAnUprightGeophone() {
        String body = generated(ASSETS + "models/block/andesite_geophone_body.json");
        String cap = generated(ASSETS + "models/block/andesite_geophone_cap.json");
        assertTrue(hasBox(body, 7, -8, 7, 9, 0, 9), "the spike is eight pixels long, below the face");
        assertTrue(hasBox(body, 5, 0, 5, 11, 1, 11), "the crossguard is a plate right on the face, wider than the spike");
        assertTrue(hasBox(body, 7, 1, 7, 9, 8, 9), "the rod stands on the crossguard");
        assertTrue(hasBox(cap, 6, 8, 6, 10, 12, 10), "the cap sits on top of the rod");
        assertEquals(12, (int) Math.round(GeophoneOrientation.VISIBLE_LENGTH * 16), "the cap's top is where the hitbox ends");
        assertEquals(8, (int) Math.round(GeophoneOrientation.SPIKE_LENGTH * 16), "the spike is what slides in");
    }

    /** Whether a model has a box with exactly these corners. */
    private static boolean hasBox(String model, int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
        String from = "\"from\":\\s*\\[\\s*" + fromX + ",\\s*" + fromY + ",\\s*" + fromZ + "\\s*]";
        String to = "\"to\":\\s*\\[\\s*" + toX + ",\\s*" + toY + ",\\s*" + toZ + "\\s*]";
        // The generator writes an element's "from" and "to" one straight after the other.
        return Pattern.compile(from + ",\\s*" + to).matcher(model).find();
    }

    /**
     * A face whose texture coordinates fall off the 16 x 16 texture picks up whatever is next to it on the block atlas. The
     * spike is below the face, where the coordinates the game works out by itself would do exactly that, so the parts state
     * their own. Every one they state must be inside the texture.
     */
    @Test
    void everyFaceOfTheGeophoneUsesTextureCoordinatesInsideTheTexture() {
        Pattern uv = Pattern.compile("\"uv\":\\s*\\[([^\\]]*)]");
        for (String part : GEOPHONE_PARTS) {
            String model = generated(ASSETS + "models/block/" + part + ".json");
            Matcher coordinates = uv.matcher(model);
            int stated = 0;
            while (coordinates.find()) {
                stated++;
                for (String number : coordinates.group(1).split(",")) {
                    double value = Double.parseDouble(number.trim());
                    assertTrue(value >= 0.0 && value <= 16.0, part + " has a texture coordinate of " + value);
                }
            }
            assertTrue(stated > 0, part + " states no texture coordinates");
        }
    }

    /**
     * The four sides of every box state their texture coordinates: their heights are where a box below the face would go
     * wrong. The top and bottom may leave them out, when they are the ones the game would work out anyway, which come from
     * the box's x and z, so those must be inside the block.
     */
    @Test
    void everyBoxStatesItsSidesAndKeepsItsTopAndBottomInsideTheBlock() {
        Pattern corner = Pattern.compile("\"(from|to)\":\\s*\\[\\s*(-?\\d+),\\s*(-?\\d+),\\s*(-?\\d+)\\s*]");
        for (String part : GEOPHONE_PARTS) {
            String model = generated(ASSETS + "models/block/" + part + ".json");
            int boxes = countMatches(model, "\"from\":");
            assertTrue(boxes > 0, part);
            assertTrue(countMatches(model, "\"uv\":") >= boxes * 4, part + ": every side of every box must state its uv");
            Matcher matcher = corner.matcher(model);
            while (matcher.find()) {
                int x = Integer.parseInt(matcher.group(2));
                int z = Integer.parseInt(matcher.group(4));
                assertTrue(x >= 0 && x <= 16 && z >= 0 && z <= 16, part + " has a box corner outside the block at x=" + x + ", z=" + z);
            }
        }
    }

    private static int countMatches(String text, String what) {
        int count = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length())) {
            count++;
        }
        return count;
    }

    /**
     * The brass geophone's glow is a colourless texture that {@code GeophoneRenderer} tints per ore at render time; a face
     * only picks up a tint the renderer passes it if the face is marked "tinted" in the model (a "tintindex"). Its dark cap
     * and andesite's own caps, which are never tinted at render time, must NOT be marked tinted either, since a tinted
     * face's colour would then depend on whatever tint the renderer happens to pass for it (1,1,1 today, but a latent bug
     * waiting to bite the next time that code changes).
     */
    @Test
    void onlyTheBrassGeophonesGlowingCapIsMarkedTinted() {
        assertTrue(generated(ASSETS + "models/block/brass_geophone_cap_lit.json").contains("\"tintindex\": 0"),
                "without this, GeophoneRenderer's per-ore colour is silently ignored and the cap always glows its own baked colour");
        for (String untinted : List.of("brass_geophone_cap", "andesite_geophone_cap", "andesite_geophone_cap_lit", "brass_geophone_body",
                "andesite_geophone_body")) {
            assertFalse(generated(ASSETS + "models/block/" + untinted + ".json").contains("tintindex"), untinted);
        }
    }

    @Test
    void everyTextureAModelNamesExists() {
        Pattern reference = Pattern.compile("\"engines_and_empires:((?:block|item)/[a-z_]+)\"");
        for (String model : List.of("block/strike_plate", "block/andesite_geophone_body", "block/andesite_geophone_cap",
                "block/andesite_geophone_cap_lit", "block/brass_geophone_body", "block/brass_geophone_cap",
                "block/brass_geophone_cap_lit", "item/andesite_geophone", "item/brass_geophone", "item/sledgehammer")) {
            Matcher matcher = reference.matcher(generated(ASSETS + "models/" + model + ".json"));
            int found = 0;
            while (matcher.find()) {
                String texture = matcher.group(1);
                assertTrue(Files.exists(GENERATED.resolve(ASSETS + "textures/" + texture + ".png"))
                                || Files.exists(MAIN.resolve(ASSETS + "textures/" + texture + ".png")),
                        model + " uses the texture " + texture + ", which does not exist");
                found++;
            }
            assertTrue(found > 0, model + " uses no textures");
        }
    }

    @Test
    void theStrikePlateDropsItself() {
        assertTrue(generated(DATA + "loot_table/blocks/strike_plate.json").contains("engines_and_empires:strike_plate"));
    }

    @Test
    void theStrikePlateIsMineableWithAPickaxe() {
        assertTrue(generated("data/minecraft/tags/block/mineable/pickaxe.json").contains("engines_and_empires:strike_plate"));
    }

    @Test
    void everyItemHasARecipeThatMakesIt() {
        for (String item : ITEMS) {
            String recipe = generated(DATA + "recipe/" + item + ".json");
            assertTrue(recipe.contains("\"id\": \"engines_and_empires:" + item + "\""), item);
        }
    }

    @Test
    void everyItemBlockAndEntityIsNamed() {
        String lang = read(MAIN.resolve(ASSETS + "lang/en_us.json"));
        assertTrue(lang.contains("\"item.engines_and_empires.sledgehammer\""));
        assertTrue(lang.contains("\"block.engines_and_empires.strike_plate\""));
        assertTrue(lang.contains("\"item.engines_and_empires.andesite_geophone\""));
        assertTrue(lang.contains("\"item.engines_and_empires.brass_geophone\""));
        assertTrue(lang.contains("\"entity.engines_and_empires.geophone\""));
        assertFalse(lang.contains("\"block.engines_and_empires.andesite_geophone\""), "the geophone is no longer a block");
    }

    /**
     * What the hammer can strike is a tag, so a datapack can change it. It has to cover natural stone in every dimension,
     * and must not include the soft ground and foliage that made a strike feel wrong.
     */
    @Test
    void theStrikableTagCoversStoneAndNotSoftGround() {
        String tag = read(MAIN.resolve(DATA + "tags/block/strikable.json"));
        assertTrue(tag.contains("#minecraft:base_stone_overworld"), "stone, granite, diorite, andesite, tuff, deepslate");
        assertTrue(tag.contains("#minecraft:base_stone_nether"), "netherrack, basalt, blackstone");
        assertTrue(tag.contains("#c:ores"), "ore in the rock is rock");
        for (String soft : List.of("leaves", "dirt", "grass", "sand", "logs", "planks", "wool")) {
            assertFalse(tag.contains(soft), "the tag must not include " + soft);
        }
    }
}
