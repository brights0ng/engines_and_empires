package dev.brights0ng.enginesandempires;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * NeoForge's config screen reads its title from {@code <modid>.configuration.title}; without it the screen falls back to
 * NeoForge's own title (Bright, step 1c-B: the key comes back with the mod's real name).
 */
class ConfigTitleTest {

    @Test
    void theConfigScreenIsTitledWithTheModsName() throws IOException {
        JsonObject lang = JsonParser.parseString(
                Files.readString(Path.of("src/main/resources/assets/engines_and_empires/lang/en_us.json"))).getAsJsonObject();
        assertEquals("Engines and Empires", lang.get(EnginesAndEmpires.MODID + ".configuration.title").getAsString());
    }
}
