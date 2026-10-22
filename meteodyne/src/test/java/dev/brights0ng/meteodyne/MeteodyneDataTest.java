package dev.brights0ng.meteodyne;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Meteodyne's data names only vanilla's ids, the common {@code c:} tags and its own (split step 2c). Entries that fill
 * in another mod's blocks (Create's blaze burner and casings, Cold Sweat's hearths, Diesel Generators' engines,
 * Simulated's and Aeronautics' burners, Farmer's Delight's crops) are pack content: the core ships
 * them in files of the same ids, which NeoForge merges into Meteodyne's data maps and tags. So a run with Meteodyne and
 * the library alone (its bare game test run) never names a block that isn't there.
 */
class MeteodyneDataTest {

    private static final Path DATA = Path.of("src", "main", "resources", "data");
    private static final Set<String> ALLOWED = Set.of("minecraft", "c", Meteodyne.MODID);
    /** An id in a JSON string: {@code "ns:path"} or a tag {@code "#ns:path"}. */
    private static final Pattern ID = Pattern.compile("\"#?([a-z0-9_.-]+):[a-z0-9_/.-]+\"");

    @Test
    void meteodynesDataNamesOnlyVanillaCommonAndItsOwnIds() throws IOException {
        assertTrue(Files.isDirectory(DATA), DATA + " not found; the tests must run from the meteodyne project directory");
        List<String> problems = new ArrayList<>();
        int files = 0;
        try (Stream<Path> walk = Files.walk(DATA)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".json")).sorted().toList()) {
                files++;
                Matcher m = ID.matcher(Files.readString(p, StandardCharsets.UTF_8));
                while (m.find()) {
                    if (!ALLOWED.contains(m.group(1))) {
                        problems.add(DATA.relativize(p) + ": " + m.group());
                    }
                }
            }
        }
        assertFalse(files == 0, "no data files under " + DATA);
        assertTrue(problems.isEmpty(), problems.size() + " ids of other mods in Meteodyne's data (they belong in the"
                + " core's data):\n  " + String.join("\n  ", problems) + "\n");
    }
}
