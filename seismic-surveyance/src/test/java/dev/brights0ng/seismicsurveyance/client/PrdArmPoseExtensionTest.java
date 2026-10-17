package dev.brights0ng.seismicsurveyance.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import dev.brights0ng.seismicsurveyance.SeismicSurveyance;

/**
 * {@code META-INF/enumextensions.json} names {@link PrdArmPose} by its class path. If the class moves and the file
 * does not follow, only a client start fails (the game test server never loads the arm pose enum), so check it here.
 */
class PrdArmPoseExtensionTest {

    @Test
    void theEnumExtensionPointsAtTheArmPoseField() throws IOException {
        String json = read("/META-INF/enumextensions.json");
        Matcher matcher = Pattern.compile("\"class\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"field\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), "enumextensions.json has no class/field parameters");
        String classFile = "/" + matcher.group(1) + ".class";
        assertTrue(classFile.equals("/" + PrdArmPose.class.getName().replace('.', '/') + ".class"),
                "enumextensions.json names " + matcher.group(1) + ", not PrdArmPose");
        assertTrue(new String(readBytes(classFile), StandardCharsets.ISO_8859_1).contains(matcher.group(2)),
                "PrdArmPose has no field " + matcher.group(2));
    }

    /**
     * NeoForge wants an extension's constant named after the mod that adds it (its id in capitals, then an underscore),
     * and the metadata that lists the file is this mod's own (split step 2e).
     */
    @Test
    void theConstantAndTheFileAreThisMods() throws IOException {
        String json = read("/META-INF/enumextensions.json");
        Matcher name = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(name.find(), "enumextensions.json names no constant");
        String prefix = SeismicSurveyance.MODID.toUpperCase(Locale.ROOT) + "_";
        assertTrue(name.group(1).startsWith(prefix), name.group(1) + " should start with " + prefix);
        assertTrue(read("/META-INF/neoforge.mods.toml").contains("enumExtensions=\"META-INF/enumextensions.json\""),
                "the mod metadata does not list enumextensions.json");
    }

    private static String read(String resource) throws IOException {
        return new String(readBytes(resource), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(String resource) throws IOException {
        try (InputStream in = PrdArmPoseExtensionTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing resource " + resource);
            return in.readAllBytes();
        }
    }
}
