// Updated by Gemini: removed MineColonies from optional dependency expectations.
package dev.brights0ng.enginesandempires;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The core's dependencies are exactly plan section 2's (split step 2f): it requires the library, the six feature mods,
 * Sable, Create, the three Simulated Project mods and Encumbered; everything else it touches is optional.
 */
class CoreMetadataTest {

    private static final Path METADATA = Path.of("src/main/templates/META-INF/neoforge.mods.toml");
    private static final Pattern DEPENDENCY = Pattern.compile(
            "\\[\\[dependencies\\.\\$\\{mod_id}]]\\s*modId=\"([^\"]+)\"\\s*type=\"([^\"]+)\"");

    @Test
    void theCoreRequiresAndOffersExactlyWhatThePlanSays() throws IOException {
        Set<String> required = new TreeSet<>();
        Set<String> optional = new TreeSet<>();
        // Without its comments (the template's first entries carry the example file's comments between their lines)
        Matcher matcher = DEPENDENCY.matcher(Files.readString(METADATA).replaceAll("#[^\n]*", ""));
        while (matcher.find()) {
            (matcher.group(2).equals("required") ? required : optional).add(matcher.group(1));
        }
        assertEquals(new TreeSet<>(Set.of("neoforge", "minecraft", "paradyne_lib", "sparse_ores", "seismic_surveyance",
                "meteodyne", "frontier", "simple_spoilage", "sable", "create", "simulated", "aeronautics", "offroad",
                "encumbered")), required);
        assertEquals(new TreeSet<>(Set.of("cold_sweat", "sereneseasons", "appleskin", "appleseed",
                "guardvillagers", "born_in_chaos_v1", "naturalist")), optional);
    }
}
