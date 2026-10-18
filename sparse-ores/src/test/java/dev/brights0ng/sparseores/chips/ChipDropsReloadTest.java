package dev.brights0ng.sparseores.chips;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * When the server reloads its data so the chip ores' smelting follows the setting (split step 4c): only when a load has
 * tested the condition and the setting has changed since. A world's first load always sees the default, because its
 * server config is only read after it.
 */
class ChipDropsReloadTest {

    @Test
    void theDataIsReloadedOnlyWhenTheSettingChangedSinceTheLoad() {
        assertFalse(Chips.needsReload(null, true), "no load has tested it yet");
        assertFalse(Chips.needsReload(null, false), "no load has tested it yet");
        assertFalse(Chips.needsReload(true, true), "loaded with chips, chips on");
        assertFalse(Chips.needsReload(false, false), "loaded without chips, chips off");
        assertTrue(Chips.needsReload(true, false), "the world turns chips off after its first load");
        assertTrue(Chips.needsReload(false, true), "switched back on while running");
    }
}
