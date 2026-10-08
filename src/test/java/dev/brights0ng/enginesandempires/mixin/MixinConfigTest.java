package dev.brights0ng.enginesandempires.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * A mixin that is silently not applied is the worst kind of bug: the game runs, and vanilla's ore
 * veins are just back. These check the wiring, since the mixin itself can only run inside the game.
 */
class MixinConfigTest {

    private static final String CONFIG = "/engines_and_empires.mixins.json";

    @Test
    void theConfigListsOnlyMixinClassesThatExist() throws IOException {
        String config = read(CONFIG);
        String pkg = match(config, "\"package\"\\s*:\\s*\"([^\"]+)\"");
        List<String> mixins = mixinNames(config);
        assertFalse(mixins.isEmpty(), "the config should list at least the ore vein mixin");
        for (String name : mixins) {
            String resource = "/" + pkg.replace('.', '/') + "/" + name.replace('.', '/') + ".class";
            assertNotNull(MixinConfigTest.class.getResource(resource), "config lists " + name + " but " + resource + " does not exist");
        }
    }

    /** With defaultRequire the game refuses to start if an injection stops matching, instead of quietly not applying. */
    @Test
    void aMixinThatStopsMatchingFailsLoudly() throws IOException {
        assertTrue(read(CONFIG).replaceAll("\\s", "").contains("\"defaultRequire\":1"));
    }

    @Test
    void theModMetadataRegistersTheConfig() throws IOException {
        String toml = read("/META-INF/neoforge.mods.toml");
        assertTrue(toml.contains("[[mixins]]"), "neoforge.mods.toml has no [[mixins]] entry");
        assertTrue(toml.contains("config=\"engines_and_empires.mixins.json\""), "the mixin config is not referenced by the mod metadata");
    }

    @Test
    void theOreVeinMixinStillTargetsTheOreVeinAccessor() throws IOException {
        try (InputStream in = MixinConfigTest.class.getResourceAsStream("/dev/brights0ng/enginesandempires/mixin/NoiseGeneratorSettingsMixin.class")) {
            assertNotNull(in);
            String classFile = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(classFile.contains("oreVeinsEnabled"), "the mixin should inject into oreVeinsEnabled");
            assertTrue(classFile.contains("net/minecraft/world/level/levelgen/NoiseGeneratorSettings"), "the mixin should target NoiseGeneratorSettings");
        }
    }

    private static List<String> mixinNames(String config) {
        String block = match(config, "\"mixins\"\\s*:\\s*\\[([^\\]]*)\\]");
        List<String> names = new ArrayList<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(block);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static String match(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertTrue(matcher.find(), "no match for " + regex);
        return matcher.group(1);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = MixinConfigTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
