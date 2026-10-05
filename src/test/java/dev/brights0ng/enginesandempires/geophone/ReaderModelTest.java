package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.geophone.ReaderCompass.HeightBand;

/**
 * Checks the held wind-up reader's item model by doing what the game does with it.
 *
 * <p>The model has a list of overrides, each saying "use this picture if these item properties are at least this much". The
 * game goes down the list from the end and uses the first one whose conditions all hold, so the last that fits wins. That is
 * easy to get subtly wrong, and would show up in game only as a needle pointing at the wrong picture or an arrow that is the
 * wrong way up. So this reads the generated model and picks a picture for every combination of properties, exactly the way the
 * game does, and checks it is the one the compass maths says it should be.
 *
 * <p>The model files are plain and regular, as the data generator writes them, so they are read directly rather than with a
 * JSON library.
 */
class ReaderModelTest {

    private static final Path MODELS = Path.of("src/generated/resources/assets/engines_and_empires/models/item");
    private static final String PREFIX = "engines_and_empires:";

    private static final Pattern OVERRIDE = Pattern.compile("\"model\":\\s*\"([^\"]+)\",\\s*\"predicate\":\\s*\\{([^}]*)}");
    private static final Pattern CONDITION = Pattern.compile("\"([^\"]+)\":\\s*(-?[0-9][0-9.eE+-]*)");
    private static final Pattern LAYER = Pattern.compile("\"(layer\\d)\":\\s*\"([^\"]+)\"");

    private record Override(String model, Map<String, Double> conditions) {
    }

    private static String read(String name) {
        try {
            return Files.readString(MODELS.resolve(name + ".json"));
        } catch (IOException e) {
            throw new AssertionError("Missing " + name + ".json: has the Data run been done?", e);
        }
    }

    /** The texture of each layer of a model, by layer name. */
    private static Map<String, String> layers(String json) {
        Map<String, String> layers = new HashMap<>();
        Matcher matcher = LAYER.matcher(json);
        while (matcher.find()) {
            layers.put(matcher.group(1), matcher.group(2));
        }
        return layers;
    }

    /** The overrides of the reader's model, in the order they are listed. */
    private static List<Override> overrides() {
        List<Override> overrides = new ArrayList<>();
        Matcher matcher = OVERRIDE.matcher(read("windup_reader"));
        while (matcher.find()) {
            Map<String, Double> conditions = new HashMap<>();
            Matcher condition = CONDITION.matcher(matcher.group(2));
            while (condition.find()) {
                conditions.put(condition.group(1), Double.parseDouble(condition.group(2)));
            }
            overrides.add(new Override(matcher.group(1), conditions));
        }
        return overrides;
    }

    /** What the game does: from the end of the list, the first override whose every condition is met. Null if none is. */
    private static String choose(List<Override> overrides, float reading, float angle, float height) {
        Map<String, Float> properties = Map.of(PREFIX + "reading", reading, PREFIX + "angle", angle, PREFIX + "height", height);
        for (int i = overrides.size() - 1; i >= 0; i--) {
            boolean fits = true;
            for (Map.Entry<String, Double> condition : overrides.get(i).conditions().entrySet()) {
                float value = properties.getOrDefault(condition.getKey(), 0.0F);
                if (value < condition.getValue().floatValue()) {
                    fits = false;
                    break;
                }
            }
            if (fits) {
                return overrides.get(i).model();
            }
        }
        return null;
    }

    private static String expected(int frame, HeightBand band) {
        return PREFIX + "item/" + ReaderCompass.pictureName(frame, band);
    }

    @Test
    void theOverridesWereReadAsWrittenInTheModel() {
        List<Override> overrides = overrides();
        assertEquals(HeightBand.values().length * (ReaderCompass.FRAMES + 1), overrides.size(),
                "one override per needle position and height, and one to bring the needle round again");
        // The first is the first picture with no arrow, which needs only a reading, and the last is the first picture again
        // with the arrow for a downward reading, which needs the whole of a turn to come round.
        assertEquals(PREFIX + "item/windup_reader_0", overrides.get(0).model());
        assertEquals(Map.of(PREFIX + "reading", 1.0), overrides.get(0).conditions());
        assertEquals(PREFIX + "item/windup_reader_0_down", overrides.get(overrides.size() - 1).model());
        assertEquals(ReaderCompass.wrapThreshold(), overrides.get(overrides.size() - 1).conditions().get(PREFIX + "angle"), 0.0);
    }

    @Test
    void everyCombinationOfReadingHeightAndAngleChoosesThePictureItShould() {
        List<Override> overrides = overrides();
        int checked = 0;
        for (HeightBand band : HeightBand.values()) {
            for (int step = 0; step < 1024; step++) {
                float angle = step / 1024.0F;
                int frame = ReaderCompass.frameFor(angle);
                assertEquals(expected(frame, band), choose(overrides, 1.0F, angle, band.value()),
                        "height " + band + ", angle " + angle + " should be frame " + frame);
                checked++;
            }
        }
        assertEquals(4 * 1024, checked);
    }

    /** The exact fractions at which the picture changes are where a mistake would hide. */
    @Test
    void theExactPointsWhereThePictureChangesChooseTheRightSide() {
        List<Override> overrides = overrides();
        for (HeightBand band : HeightBand.values()) {
            for (int frame = 1; frame < ReaderCompass.FRAMES; frame++) {
                float threshold = (float) ReaderCompass.frameThreshold(frame);
                assertEquals(expected(frame, band), choose(overrides, 1.0F, threshold, band.value()), "at the start of frame " + frame);
                assertEquals(expected(frame - 1, band), choose(overrides, 1.0F, Math.nextDown(threshold), band.value()), "just before it");
            }
            float wrap = (float) ReaderCompass.wrapThreshold();
            assertEquals(expected(0, band), choose(overrides, 1.0F, wrap, band.value()), "the last stretch of the turn is the first picture again");
            assertEquals(expected(ReaderCompass.FRAMES - 1, band), choose(overrides, 1.0F, Math.nextDown(wrap), band.value()));
            assertEquals(expected(0, band), choose(overrides, 1.0F, 0.0F, band.value()));
            assertEquals(expected(0, band), choose(overrides, 1.0F, Math.nextDown(1.0F), band.value()), "just short of a whole turn is straight up");
        }
    }

    @Test
    void withNoReadingItIsTheDeadDialWhateverTheOtherProperties() {
        List<Override> overrides = overrides();
        for (HeightBand band : HeightBand.values()) {
            for (int step = 0; step < 64; step++) {
                assertNull(choose(overrides, 0.0F, step / 64.0F, band.value()), "no override may fit when there is no reading");
            }
        }
        assertEquals(PREFIX + "item/windup_reader_idle", layers(read("windup_reader")).get("layer0"));
    }

    @Test
    void everyHeightArrowIsOnlyChosenForItsOwnHeight() {
        List<Override> overrides = overrides();
        for (HeightBand band : HeightBand.values()) {
            String model = choose(overrides, 1.0F, 0.3F, band.value());
            String suffix = band == HeightBand.NONE ? "" : "_" + band.name().toLowerCase(Locale.ROOT);
            assertTrue(model.endsWith(suffix), band + " chose " + model);
            if (band == HeightBand.NONE) {
                assertTrue(model.matches(".*_\\d+"), "with no height there is no arrow: " + model);
            }
        }
    }

    @Test
    void everyPictureAnOverrideNamesExistsWithTheTexturesItShouldHave() {
        for (Override override : overrides()) {
            String name = override.model().substring((PREFIX + "item/").length());
            Map<String, String> layers = layers(read(name));
            boolean hasArrow = layers.containsKey("layer1");
            assertEquals(!name.matches("windup_reader_\\d+"), hasArrow, name + ": an arrow only with a height");
            if (hasArrow) {
                assertTrue(layers.get("layer1").matches(PREFIX + "item/windup_reader_arrow_(up|level|down)"), name);
                assertTrue(layers.get("layer1").endsWith(name.substring(name.lastIndexOf('_') + 1)), name + " has the wrong arrow");
            }
            // The needle picture matches the name: windup_reader_<frame>[_height].
            String frame = name.replaceAll("windup_reader_(\\d+).*", "$1");
            assertEquals(PREFIX + "item/windup_reader_" + frame, layers.get("layer0"), name);
        }
    }

    @Test
    void everyTextureTheModelsUseExists() {
        Path generated = Path.of("src/generated/resources/assets/engines_and_empires/textures");
        for (String texture : ReaderTextures.all().keySet()) {
            assertTrue(Files.exists(generated.resolve(texture + ".png")), texture + " is missing");
        }
    }

    /** The placed reader is drawn from a body (case, crank axle, crank grip) and a lamp, dark and lit: the renderer asks for each by name. */
    @Test
    void thePlacedReaderHasItsBodyAndBothLamps() throws IOException {
        Path blockModels = Path.of("src/generated/resources/assets/engines_and_empires/models/block");
        String body = Files.readString(blockModels.resolve("windup_reader_body.json"));
        assertEquals(3, count(body, "\"from\":"), "the case, the crank's axle and its grip");
        for (String lamp : List.of("windup_reader_lamp", "windup_reader_lamp_lit")) {
            String model = Files.readString(blockModels.resolve(lamp + ".json"));
            assertEquals(1, count(model, "\"from\":"), lamp);
            assertTrue(model.contains("engines_and_empires:block/" + lamp), lamp + " uses its own texture");
        }
        assertTrue(body.contains("engines_and_empires:block/windup_reader"));
    }

    @Test
    void theReaderCanBeCraftedAndIsNamed() throws IOException {
        String recipe = Files.readString(Path.of("src/generated/resources/data/engines_and_empires/recipe/windup_reader.json"));
        assertTrue(recipe.contains("\"id\": \"engines_and_empires:windup_reader\""));
        assertTrue(recipe.contains("minecraft:compass"), "a compass at its heart");
        String lang = Files.readString(Path.of("src/main/resources/assets/engines_and_empires/lang/en_us.json"));
        assertTrue(lang.contains("\"item.engines_and_empires.windup_reader\""));
        assertTrue(lang.contains("\"entity.engines_and_empires.windup_reader\""));
    }

    private static int count(String text, String what) {
        int count = 0;
        for (int at = text.indexOf(what); at >= 0; at = text.indexOf(what, at + what.length())) {
            count++;
        }
        return count;
    }
}
