package dev.brights0ng.meteodyne.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A mixin that is silently not applied is the worst kind of bug: the game runs, and vanilla's behaviour is just back.
 * These check the wiring of Meteodyne's mixin config (split step 1b: one config per mod), since the mixins themselves can
 * only run inside the game.
 */
class MixinConfigTest {

    private static final String CONFIG = "/meteodyne.mixins.json";
    private static final String PACKAGE = "dev.brights0ng.meteodyne.mixin";
    private static final String PLUGIN = "dev.brights0ng.meteodyne.MeteodyneMixinPlugin";
    /** The mixins that need another mod loaded, and that mod's id; every other mixin always applies. */
    private static final Map<String, String> GATED = Map.ofEntries();

    @Test
    void theConfigListsOnlyMixinClassesThatExist() throws IOException {
        String config = read(CONFIG);
        assertEquals(PACKAGE, match(config, "\"package\"\\s*:\\s*\"([^\"]+)\""));
        List<String> mixins = mixinNames(config);
        assertFalse(mixins.isEmpty(), "the config should list at least one mixin");
        for (String name : mixins) {
            String resource = classResource(name);
            assertNotNull(MixinConfigTest.class.getResource(resource), "config lists " + name + " but " + resource + " does not exist");
        }
    }

    /** A mixin left out of the config (say, dropped while the configs were split) would never apply. */
    @Test
    void theConfigListsEveryMixinInItsPackage() throws IOException, URISyntaxException {
        List<String> listed = mixinNames(read(CONFIG));
        // The main classes' copy of the package: climb from a listed mixin's class file (tests share the package name).
        String first = listed.get(0);
        Path dir = Path.of(MixinConfigTest.class.getResource(classResource(first)).toURI()).getParent();
        for (int i = 1; i < first.split("\\.").length; i++) {
            dir = dir.getParent();
        }
        Path root = dir;
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.map(p -> root.relativize(p).toString())
                    .filter(p -> p.endsWith(".class") && !p.contains("$"))
                    .forEach(p -> found.add(p.substring(0, p.length() - ".class".length()).replace('/', '.')));
        }
        assertEquals(found, new TreeSet<>(listed), "every class in " + PACKAGE + " should be listed exactly once");
        assertEquals(listed.size(), new TreeSet<>(listed).size(), "a mixin is listed twice");
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
        assertTrue(toml.contains("config=\"" + CONFIG.substring(1) + "\""), "the mixin config is not referenced by the mod metadata");
    }

    /** Mixins on another mod's classes apply only when that mod is loaded; vanilla targets always apply. */
    @Test
    void thePluginGatesExactlyTheMixinsOnOtherModsClasses() throws Exception {
        String config = read(CONFIG);
        assertEquals(PLUGIN, match(config, "\"plugin\"\\s*:\\s*\"([^\"]+)\""));
        // The plugin itself needs Mixin's classes, which unit tests run without; its gate table is kept apart.
        Object gate = Class.forName(PLUGIN + "$Gates").getField("GATE").get(null);
        for (String name : mixinNames(config)) {
            Object mod = gate.getClass().getMethod("requiredMod", String.class).invoke(gate, name);
            assertEquals(GATED.get(name), mod, name);
        }
        assertTrue(mixinNames(config).containsAll(GATED.keySet()), "a gated mixin is not in the config");
    }

    /** "required" makes any mixin failure fatal; only a config whose mixins never depend on another mod keeps it. */
    @Test
    void theConfigIsRequiredOnlyWithoutOptionalTargets() throws IOException {
        assertEquals(String.valueOf(GATED.isEmpty()), match(read(CONFIG), "\"required\"\\s*:\\s*(true|false)"));
    }

    private static String classResource(String name) {
        return "/" + PACKAGE.replace('.', '/') + "/" + name.replace('.', '/') + ".class";
    }

    private static List<String> mixinNames(String config) {
        List<String> names = new ArrayList<>();
        for (String key : List.of("mixins", "client", "server")) {
            Matcher list = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(config);
            if (list.find()) {
                Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
                while (matcher.find()) {
                    names.add(matcher.group(1));
                }
            }
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
