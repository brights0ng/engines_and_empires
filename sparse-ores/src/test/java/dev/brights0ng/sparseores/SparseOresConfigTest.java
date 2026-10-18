package dev.brights0ng.sparseores;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Chip drops is a startup config (split step 4d), which the game reads while the mod is constructed, before any data is
 * loaded, so the recipes' load condition and the loot see the real value from the first data load on; 4c's one-time data
 * reload (needed while it was a server config, read only after a world's first data load) is gone.
 *
 * <p>Unit tests here run without Minecraft or NeoForge on the classpath, so they cannot load the config; these read the
 * source instead, to keep the wiring from drifting back to a server config or to a reload. That the game really reads the
 * value before its first data load is {@code ChipDropsGameTests.theStartupConfigWasReadBeforeTheFirstDataLoad}.
 */
class SparseOresConfigTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/brights0ng/sparseores");

    @Test
    void theConfigIsRegisteredAsAStartupConfigWithTheNameNeoForgeWouldGiveIt() throws IOException {
        String config = read("SparseOresConfig.java");
        assertTrue(config.contains("public static final ModConfig.Type TYPE = ModConfig.Type.STARTUP;"), "a STARTUP config");
        assertTrue(config.contains("FILE_NAME = \"sparse_ores-startup.toml\""), "named like a startup config of this mod");
        assertTrue(read("SparseOres.java").contains("registerConfig(SparseOresConfig.TYPE, SparseOresConfig.SPEC, SparseOresConfig.FILE_NAME)"),
                "the mod registers it by that type, while it is constructed");
        for (String source : sources()) {
            assertFalse(source.contains("ModConfig.Type.SERVER"), "Sparse Ores has no server config any more");
            assertFalse(source.contains("sparse_ores-server.toml"), "the old file name is gone");
        }
    }

    @Test
    void changingChipDropsAsksForARestart() throws IOException {
        String config = read("SparseOresConfig.java");
        int declaration = config.indexOf("CHIP_DROPS = BUILDER");
        int define = config.indexOf(".define(\"chipDrops\", true)");
        assertTrue(declaration > 0 && define > declaration);
        String chipDrops = config.substring(declaration, define);
        assertTrue(chipDrops.contains(".gameRestart()"), "the config screen says a restart is needed");
        assertTrue(chipDrops.contains("changing it needs a restart"), "and the file's comment says so");
    }

    @Test
    void nothingReloadsTheDataForTheSettingAnyMore() throws IOException {
        for (String source : sources()) {
            for (String reload : List.of("reloadResources", "ServerStartedEvent", "ModConfigEvent", "needsReload", "reloadIfStale")) {
                assertFalse(source.contains(reload), "the one-time reload of step 4c is gone, but a source mentions " + reload);
            }
        }
        assertTrue(read("chips/ChipDropsCondition.java").contains("SparseOresConfig.chipDrops()"),
                "the load condition reads the setting itself");
    }

    private static String read(String file) throws IOException {
        return Files.readString(SOURCES.resolve(file));
    }

    private static List<String> sources() throws IOException {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files.filter(p -> p.toString().endsWith(".java")).map(p -> {
                try {
                    // Code only: a comment may say what used to be.
                    return Files.readString(p).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)^\\s*//.*$", "");
                } catch (IOException e) {
                    throw new IllegalStateException(p.toString(), e);
                }
            }).toList();
        }
    }
}
