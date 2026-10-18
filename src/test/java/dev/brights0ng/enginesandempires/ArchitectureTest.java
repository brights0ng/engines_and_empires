package dev.brights0ng.enginesandempires;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Locks the mod family's dependency graph (split plan section 2) before the build is sliced (split step 1d). Until
 * step 2 every mod still compiles in one source tree, so nothing but this test stops, say, Seismic importing Frontier.
 * It reads source text, not classes: a mention in a comment, a string or a fully qualified name counts as much as an
 * import, since each one is a reference that breaks (or lies) once the mods are separate jars.
 *
 * <p>Rules: (a) a file's mod is the root of its package; (b) a file may mention only its own mod and the mods the
 * graph lets it use, with {@code frontier.seismic} the one Frontier package allowed Seismic and Sparse Ores; (c) an
 * outside mod a file's mod does not require may be mentioned only from a {@code compat} package, or, for Sable, a
 * {@code sable} package, unless {@code arch-allowlist.txt} says why not; feature mods never mention
 * {@code EnginesAndEmpiresMod}; and the per-mod file counts of {@code docs/split/mod-split-manifest.csv} hold.
 *
 * <p>Since step 2b the mods are being sliced into Gradle projects of their own. The test scans the {@code src/main/java}
 * and {@code src/test/java} of every project in {@code settings.gradle} (the root, which is the core and still holds
 * every mod not yet sliced, and one project per sliced mod), and checks that every file of a sliced mod sits in that
 * mod's project and nowhere else. Gradle and javac now enforce the graph between projects; the rules above still catch
 * what they cannot see (a mention in a comment or string, the core-only edges inside the root, {@code frontier.seismic}).
 * Paths are relative to the root project directory, which is the working directory Gradle runs the core's tests in.
 */
class ArchitectureTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path TEST = Path.of("src", "test", "java");
    private static final Path SETTINGS = Path.of("settings.gradle");
    private static final Pattern INCLUDE = Pattern.compile("^\\s*include\\s+(.+)$", Pattern.MULTILINE);
    private static final Pattern PROJECT_PATH = Pattern.compile("['\"]:?([\\w:-]+)['\"]");
    private static final Pattern MOD_ID = Pattern.compile("^mod_id\\s*=\\s*(\\w+)\\s*$", Pattern.MULTILINE);
    private static final Path MANIFEST = Path.of("docs", "split", "mod-split-manifest.csv");
    private static final String ALLOWLIST = "/arch-allowlist.txt";
    private static final String OURS = "dev.brights0ng.";

    /** Package root (the segment after {@code dev.brights0ng}) to mod id. */
    private static final Map<String, String> ROOTS = Map.of(
            "paradynelib", "paradyne_lib",
            "sablefixes", "sable_fixes",
            "sparseores", "sparse_ores",
            "seismicsurveyance", "seismic_surveyance",
            "meteodyne", "meteodyne",
            "frontier", "frontier",
            "simplespoilage", "simple_spoilage",
            "enginesandempires", "engines_and_empires",
            // The test-only mod of the core-less combined run (coreless/, split step 4c).
            "corelesstests", "coreless_tests");

    /** Plan section 2: the other mods of ours each mod may mention (its own is always allowed). */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "paradyne_lib", Set.of(),
            "sable_fixes", Set.of(),
            "sparse_ores", Set.of("paradyne_lib"),
            "seismic_surveyance", Set.of("paradyne_lib", "sparse_ores"),
            "meteodyne", Set.of("paradyne_lib"),
            "frontier", Set.of("paradyne_lib"),
            "simple_spoilage", Set.of("paradyne_lib"),
            "engines_and_empires", Set.of("paradyne_lib", "sable_fixes", "sparse_ores", "seismic_surveyance",
                    "meteodyne", "frontier", "simple_spoilage"),
            // Every feature mod, never the core: its tests are of the feature mods working together without it.
            "coreless_tests", Set.of("paradyne_lib", "sable_fixes", "sparse_ores", "seismic_surveyance", "meteodyne",
                    "frontier", "simple_spoilage"));

    /** The only Frontier package that may mention Seismic Surveyance and Sparse Ores (Seismic is optional to Frontier). */
    private static final String GATED_PACKAGE = "dev.brights0ng.frontier.seismic";
    private static final Set<String> GATED_TARGETS = Set.of("seismic_surveyance", "sparse_ores");

    /**
     * Outside mods, by key, and the packages that are theirs. A mention is the package followed by a dot, so a mod id
     * in a string ({@code ModList.isLoaded("sereneseasons")}) is not one. CloudWorks counts as Diet: it is Diet's
     * required library and comes and goes with it. Create brings Flywheel and Catnip.
     */
    private static final Map<String, List<String>> OUTSIDE = new LinkedHashMap<>();

    static {
        OUTSIDE.put("sable", List.of("dev.ryanhcode.sable"));
        OUTSIDE.put("coldsweat", List.of("com.momosoftworks.coldsweat"));
        OUTSIDE.put("minecolonies", List.of("com.minecolonies"));
        OUTSIDE.put("sereneseasons", List.of("sereneseasons"));
        OUTSIDE.put("iris", List.of("net.irisshaders.iris", "net.coderbot.iris"));
        OUTSIDE.put("appleskin", List.of("squeek.appleskin"));
        OUTSIDE.put("diet", List.of("net.appleseed", "com.cloudworks"));
        OUTSIDE.put("simulated", List.of("dev.simulated_team.simulated"));
        OUTSIDE.put("aeronautics", List.of("dev.eriksonn.aeronautics"));
        OUTSIDE.put("offroad", List.of("dev.ryanhcode.offroad"));
        OUTSIDE.put("dieselgenerators", List.of("com.jesz.createdieselgenerators"));
        OUTSIDE.put("create", List.of("com.simibubi.create", "dev.engine_room.flywheel", "net.createmod.catnip"));
        OUTSIDE.put("encumbered", List.of("com.khofonyx.encumbered"));
    }

    /** Plan section 2: the outside mods each mod requires, which it may mention anywhere. */
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "sable_fixes", Set.of("sable"),
            "seismic_surveyance", Set.of("create"),
            // Create, through Seismic Surveyance, which requires it; the combined run loads it.
            "coreless_tests", Set.of("create"),
            "engines_and_empires", Set.of("sable", "create", "simulated", "aeronautics", "offroad", "encumbered"));

    /**
     * Manifest rows the plan later moved or renamed (steps 1c-A and 1c-B), as {@code kind:path} to the mod and file
     * name they have now. Every other row must sit in its manifest mod under its manifest name.
     */
    private static final Map<String, String[]> MANIFEST_MOVES = Map.of(
            // S5: ShotSource moves to Seismic.
            "main:frontier/deep/ShotSource.java", new String[]{"seismic_surveyance", "ShotSource.java"},
            // S9: ReadingDeposits goes to the owner of what it touches (the reader's reading).
            "main:oregen/worldgen/ReadingDeposits.java", new String[]{"seismic_surveyance", "ReadingDeposits.java"},
            // S15: RefiningItems becomes sparseores.chips.ChipItems.
            "main:oregen/refining/RefiningItems.java", new String[]{"sparse_ores", "ChipItems.java"},
            // Step 1c-A task 10: the CloudWorks fix is outside-mod compat, so it goes to the core.
            "main:mixin/CloudWorksDebugDumpMixin.java", new String[]{"engines_and_empires", "CloudWorksDebugDumpMixin.java"},
            // Step 4b: Frontier's tier map on Seismic's maps is Frontier's own (standing principle), not core glue (S8).
            "main:geophone/client/TierOverlay.java", new String[]{"frontier", "TierOverlay.java"});

    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern OUR_MENTION = Pattern.compile("(?<![\\w.])dev\\.brights0ng\\.(\\w+)");
    private static final Pattern CORE_ENTRY = Pattern.compile("\\bEnginesAndEmpiresMod\\b");

    /**
     * A Java source file: the Gradle project folder it is in ({@code "."} for the root), its path under that project's
     * source root, its package, the mod that package belongs to (null when the root is not one of ours) and its text.
     */
    private record Source(String module, String path, String pkg, String mod, String text) {

        /** Where it is, from the root project directory, for reports. */
        String where(Path root) {
            return (module.equals(".") ? "" : module + "/") + root + "/" + path;
        }
    }

    // (a) ------------------------------------------------------------------------------------------------------------

    @Test
    void everyFileBelongsToAModByItsPackage() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path root : List.of(MAIN, TEST)) {
            for (Source s : sources(root)) {
                String expected = s.path.substring(0, s.path.lastIndexOf('/')).replace('/', '.');
                if (s.pkg == null) {
                    problems.add(s.where(root) + ": no package declaration");
                } else if (!s.pkg.equals(expected)) {
                    problems.add(s.where(root) + ": package " + s.pkg + " does not match its folder");
                } else if (s.mod == null) {
                    problems.add(s.where(root) + ": package " + s.pkg + " is not under any mod's root");
                }
            }
        }
        assertTrue(problems.isEmpty(), report("files outside the mod family's packages", problems));
    }

    /**
     * Every file of a mod that has a Gradle project of its own (split step 2) sits in that project, and a mod project
     * holds only its own mod's files; the root (the core) holds the mods not yet sliced out.
     */
    @Test
    void everyFileSitsInItsModsProject() throws IOException {
        Map<String, String> moduleOfMod = new TreeMap<>();
        for (Map.Entry<String, String> e : modules().entrySet()) {
            if (!e.getKey().equals(".")) {
                assertTrue(ROOTS.containsValue(e.getValue()), e.getKey() + "/gradle.properties: mod_id " + e.getValue()
                        + " is not a mod of the family");
                assertTrue(moduleOfMod.put(e.getValue(), e.getKey()) == null, "two projects build " + e.getValue());
            }
        }
        List<String> problems = new ArrayList<>();
        for (Path root : List.of(MAIN, TEST)) {
            for (Source s : sources(root)) {
                if (s.mod == null) {
                    continue;
                }
                String expected = moduleOfMod.getOrDefault(s.mod, ".");
                if (!s.module.equals(expected)) {
                    problems.add(s.mod + ": " + s.where(root) + " belongs in "
                            + (expected.equals(".") ? "the root project" : expected + "/"));
                }
            }
        }
        assertTrue(problems.isEmpty(), report("files in another mod's Gradle project", problems));
    }

    // (b) ------------------------------------------------------------------------------------------------------------

    @Test
    void noFileMentionsAModTheGraphDoesNotAllow() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path root : List.of(MAIN, TEST)) {
            for (Source s : sources(root)) {
                if (s.mod == null) {
                    continue;
                }
                Set<String> seen = new TreeSet<>();
                Matcher m = OUR_MENTION.matcher(s.text);
                while (m.find()) {
                    String target = ROOTS.get(m.group(1));
                    if (target == null) {
                        seen.add(OURS + m.group(1) + " (not a mod of the family)");
                    } else if (!allowedEdge(s, target)) {
                        seen.add(target + " (" + OURS + m.group(1) + ")");
                    }
                }
                seen.forEach(t -> problems.add(s.mod + ": " + s.where(root) + " -> " + t));
            }
        }
        assertTrue(problems.isEmpty(), report("references across forbidden edges of the mod graph (plan section 2)", problems));
    }

    private static boolean allowedEdge(Source s, String target) {
        if (target.equals(s.mod) || ALLOWED.get(s.mod).contains(target)) {
            return true;
        }
        return s.mod.equals("frontier") && GATED_TARGETS.contains(target)
                && (s.pkg.equals(GATED_PACKAGE) || s.pkg.startsWith(GATED_PACKAGE + "."));
    }

    @Test
    void featureModsDoNotMentionTheCoreEntryClass() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Path root : List.of(MAIN, TEST)) {
            for (Source s : sources(root)) {
                if (s.mod != null && !s.mod.equals("engines_and_empires") && CORE_ENTRY.matcher(s.text).find()) {
                    problems.add(s.mod + ": " + s.where(root));
                }
            }
        }
        assertTrue(problems.isEmpty(), report("feature-mod files that mention EnginesAndEmpiresMod", problems));
    }

    // (c) ------------------------------------------------------------------------------------------------------------

    @Test
    void optionalOutsideModsOnlyFromCompatOrSablePackages() throws IOException {
        Map<String, String> allowlist = allowlist();
        Set<String> violations = new TreeSet<>();
        for (Source s : sources(MAIN)) {
            if (s.mod == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> outside : OUTSIDE.entrySet()) {
                String key = outside.getKey();
                if (REQUIRED.getOrDefault(s.mod, Set.of()).contains(key) || gatedPackage(s.pkg, key)) {
                    continue;
                }
                for (String pkg : outside.getValue()) {
                    if (Pattern.compile("(?<![\\w.])" + Pattern.quote(pkg) + "\\.\\w").matcher(s.text).find()) {
                        violations.add(s.path + " | " + key);
                        break;
                    }
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (String v : violations) {
            if (!allowlist.containsKey(v)) {
                problems.add(v);
            }
        }
        List<String> stale = new ArrayList<>();
        for (String entry : allowlist.keySet()) {
            if (!violations.contains(entry)) {
                stale.add(entry);
            }
        }
        assertTrue(problems.isEmpty(), report("optional outside mods mentioned outside a compat (or, for Sable, sable)"
                + " package; move the code, or add 'path | mod | reason' to " + ALLOWLIST.substring(1), problems));
        assertTrue(stale.isEmpty(), report(ALLOWLIST.substring(1) + " entries that no longer match anything; delete them", stale));
    }

    /** A {@code compat} package may touch any outside mod; a {@code sable} package only Sable. */
    private static boolean gatedPackage(String pkg, String key) {
        List<String> segments = List.of(pkg.split("\\."));
        return segments.contains("compat") || (key.equals("sable") && segments.contains("sable"));
    }

    /**
     * {@code path | outside mod | reason} per line, path under a project's {@code src/main/java}; blank lines and
     * {@code #} skipped.
     */
    private static Map<String, String> allowlist() throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (InputStream in = ArchitectureTest.class.getResourceAsStream(ALLOWLIST)) {
            assertNotNull(in, ALLOWLIST + " is missing");
            int n = 0;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                n++;
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\\|", 3);
                assertEquals(3, parts.length, ALLOWLIST + " line " + n + ": expected 'path | mod | reason': " + line);
                String path = parts[0].strip();
                String key = parts[1].strip();
                assertTrue(OUTSIDE.containsKey(key), ALLOWLIST + " line " + n + ": unknown outside mod '" + key + "'");
                assertFalse(parts[2].isBlank(), ALLOWLIST + " line " + n + ": every exception needs a reason");
                boolean exists = false;
                for (String module : modules().keySet()) {
                    exists |= Files.isRegularFile(Path.of(module).resolve(MAIN).resolve(path));
                }
                assertTrue(exists, ALLOWLIST + " line " + n + ": no such file " + path);
                assertTrue(entries.put(path + " | " + key, parts[2].strip()) == null, ALLOWLIST + " line " + n + ": duplicate");
            }
        }
        return entries;
    }

    // Manifest ---------------------------------------------------------------------------------------------------------

    /**
     * Every manifest file sits in its mod (after the moves the plan made since), so the per-mod counts of the plan hold
     * to the file. Files the split added (entry classes, mixin plugins, provider interfaces, this test) are not in the
     * manifest and are not counted.
     */
    @Test
    void theManifestsFileCountsPerModHold() throws IOException {
        Map<String, Integer> expected = new TreeMap<>();
        Map<String, Integer> found = new TreeMap<>();
        Map<String, List<String>> tree = new TreeMap<>();
        for (Path root : List.of(MAIN, TEST)) {
            String kind = root.equals(MAIN) ? "main" : "test";
            for (Source s : sources(root)) {
                tree.computeIfAbsent(kind + ":" + s.mod + ":" + s.path.substring(s.path.lastIndexOf('/') + 1),
                        k -> new ArrayList<>()).add(s.path);
            }
        }
        List<String> missing = new ArrayList<>();
        Set<String> listed = new TreeSet<>();
        int rows = 0;
        for (String line : Files.readAllLines(MANIFEST, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank() || line.equals("kind,mod,path")) {
                continue;
            }
            rows++;
            String[] cols = line.split(",", 3);
            listed.add(cols[0] + ":" + cols[2]);
            String kind = cols[0];
            String mod = cols[1];
            String name = cols[2].substring(cols[2].lastIndexOf('/') + 1);
            String[] moved = MANIFEST_MOVES.get(kind + ":" + cols[2]);
            if (moved != null) {
                mod = moved[0];
                name = moved[1];
            }
            String key = kind + " " + mod;
            expected.merge(key, 1, Integer::sum);
            List<String> candidates = tree.get(kind + ":" + mod + ":" + name);
            if (candidates != null && !candidates.isEmpty()) {
                candidates.remove(0);
                found.merge(key, 1, Integer::sum);
            } else {
                missing.add(line + " (expected " + name + " in " + mod + ")");
            }
        }
        // Plan section 2: 530 main files and 124 unit test files at commit 9ebdb39.
        assertEquals(654, rows, "the manifest should list 530 main and 124 test files");
        assertTrue(listed.containsAll(MANIFEST_MOVES.keySet()), "a moved file is no longer in the manifest");
        assertTrue(missing.isEmpty(), report("manifest files not found in their mod", missing));
        assertEquals(expected, found, "files per mod (kind, mod): manifest vs tree");
    }

    // Helpers ----------------------------------------------------------------------------------------------------------

    private static final Map<Path, List<Source>> CACHE = new ConcurrentHashMap<>();

    private static List<Source> sources(Path root) throws IOException {
        List<Source> cached = CACHE.get(root);
        if (cached != null) {
            return cached;
        }
        assertTrue(Files.isDirectory(root), root + " not found; the tests must run from the root project directory");
        List<Source> list = new ArrayList<>();
        for (String module : modules().keySet()) {
            Path dir = Path.of(module).resolve(root).normalize();
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                    String text = Files.readString(p, StandardCharsets.UTF_8);
                    String path = dir.relativize(p).toString().replace('\\', '/');
                    Matcher m = PACKAGE.matcher(text);
                    String pkg = m.find() ? m.group(1) : null;
                    String mod = null;
                    if (pkg != null && pkg.startsWith(OURS)) {
                        String rest = pkg.substring(OURS.length());
                        mod = ROOTS.get(rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest);
                    }
                    list.add(new Source(module, path, pkg, mod, text));
                }
            }
        }
        assertFalse(list.isEmpty(), "no Java files under " + root);
        CACHE.put(root, list);
        return list;
    }

    private static Map<String, String> moduleCache;

    /**
     * The Gradle projects of the build, from {@code settings.gradle}: project folder ({@code "."} for the root) to the
     * id of the mod it builds (its {@code gradle.properties} {@code mod_id}). A project that builds no mod (since split
     * step 4b, {@code coreless/}, the feature mods' combined run) has no {@code gradle.properties} of its own; it must
     * hold no Java sources either, so nothing escapes these rules by sitting in it, and it is left out.
     */
    private static synchronized Map<String, String> modules() throws IOException {
        if (moduleCache != null) {
            return moduleCache;
        }
        Map<String, String> found = new LinkedHashMap<>();
        found.put(".", modId(Path.of(".")));
        assertTrue(Files.isRegularFile(SETTINGS), SETTINGS + " not found; the tests must run from the root project directory");
        Matcher include = INCLUDE.matcher(Files.readString(SETTINGS, StandardCharsets.UTF_8));
        while (include.find()) {
            Matcher project = PROJECT_PATH.matcher(include.group(1));
            while (project.find()) {
                String dir = project.group(1).replace(':', '/');
                assertTrue(Files.isDirectory(Path.of(dir)), "settings.gradle includes " + dir + ", which has no folder");
                if (!Files.exists(Path.of(dir).resolve("gradle.properties"))) {
                    assertFalse(Files.exists(Path.of(dir).resolve("src")), dir + " builds no mod (no gradle.properties)"
                            + " and so may hold no sources, but has a src folder");
                    continue;
                }
                found.put(dir, modId(Path.of(dir)));
            }
        }
        assertEquals("engines_and_empires", found.get("."), "the root project should build the core");
        moduleCache = found;
        return moduleCache;
    }

    private static String modId(Path dir) throws IOException {
        Path properties = dir.resolve("gradle.properties");
        assertTrue(Files.isRegularFile(properties), properties + " not found");
        Matcher m = MOD_ID.matcher(Files.readString(properties, StandardCharsets.UTF_8));
        assertTrue(m.find(), properties + " has no mod_id");
        return m.group(1);
    }

    private static String report(String what, List<String> problems) {
        return problems.size() + " " + what + ":\n  " + String.join("\n  ", problems) + "\n";
    }
}
