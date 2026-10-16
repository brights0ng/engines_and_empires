package dev.brights0ng.frontier.incursion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.paradynelib.services.Services;

/**
 * Split seam S6: Frontier knows no thumpers of its own. With none registered (Seismic Surveyance absent) the key hands
 * out its default (no thumpers); a registered access replaces it.
 */
class ThumperAccessTest {

    @Test
    void withNothingRegisteredThereAreNoThumpers() {
        // Unit tests run without Minecraft's classes, so what the default answers is checked in
        // FrontierGameTests.withoutSeismicThereAreNoThumpers; here, that it is what the key hands out.
        assertSame(ThumperAccess.NONE, ThumperAccess.KEY.fallback());
        assertFalse(Services.isRegistered(ThumperAccess.KEY), "unit tests register nothing");
        assertSame(ThumperAccess.NONE, ThumperAccess.get());
    }

    @Test
    void aRegisteredAccessAnswers() {
        // A stand-in that answers nothing differently: what matters here is which one the key hands out.
        ThumperAccess registered = new ThumperAccess() {
        };
        ThumperAccess before = Services.unregister(ThumperAccess.KEY);
        try {
            assertSame(ThumperAccess.NONE, ThumperAccess.get());
            Services.register(ThumperAccess.KEY, registered);
            assertSame(registered, ThumperAccess.get());
            assertTrue(Services.isRegistered(ThumperAccess.KEY));
        } finally {
            Services.unregister(ThumperAccess.KEY);
            if (before != null) {
                Services.register(ThumperAccess.KEY, before);
            }
        }
    }
}
