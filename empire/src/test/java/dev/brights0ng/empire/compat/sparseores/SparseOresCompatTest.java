// Written by Gemini.
package dev.brights0ng.empire.compat.sparseores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.sparseores.OreType;
import dev.brights0ng.sparseores.OreTypes;
import dev.brights0ng.sparseores.Realm;

class SparseOresCompatTest {

    @Test
    void registerTalcRegistersValidOverworldOre() {
        SparseOresCompat.Hook.register();

        OreType talc = OreTypes.byId("talc");
        assertNotNull(talc, "Talc must be registered in OreTypes");
        assertEquals("talc", talc.id());
        assertEquals(192, talc.layer().scale());
        assertEquals(Realm.OVERWORLD, talc.realm());
        assertEquals(-48, talc.depth().minY());
        assertEquals(96, talc.depth().maxY());
        assertEquals(350, talc.size().median());
        assertTrue(OreTypes.ALL.contains(talc));
        assertTrue(OreTypes.forRealm(Realm.OVERWORLD).contains(talc));
    }
}
