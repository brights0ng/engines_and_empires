package dev.brights0ng.seismicsurveyance.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

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
