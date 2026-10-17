package dev.brights0ng.paradynelib.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.brights0ng.paradynelib.wind.WindColumn;

/**
 * The library's service registry and its three shared questions (split seams S1-S3): each answers with its default when
 * nobody registered a provider, and a registered provider replaces it.
 */
class ServicesTest {

    private interface Greeting {
        String hello();
    }

    @Test
    void withNothingRegisteredAKeyAnswersWithItsDefault() {
        Greeting quiet = () -> "...";
        Services.Key<Greeting> key = Services.key("greeting", Greeting.class, quiet);
        assertFalse(Services.isRegistered(key));
        assertSame(quiet, Services.get(key));
        assertSame(quiet, key.fallback());
        assertEquals("greeting", key.name());
    }

    @Test
    void aRegisteredProviderAnswersUntilUnregistered() {
        Services.Key<Greeting> key = Services.key("greeting", Greeting.class, () -> "...");
        Greeting hello = () -> "hello";
        assertNull(Services.register(key, hello), "nothing was registered before");
        assertTrue(Services.isRegistered(key));
        assertEquals("hello", Services.get(key).hello());

        // The last one registered wins, and the one it replaced comes back.
        Greeting hi = () -> "hi";
        assertSame(hello, Services.register(key, hi));
        assertEquals("hi", Services.get(key).hello());

        assertSame(hi, Services.unregister(key));
        assertFalse(Services.isRegistered(key));
        assertEquals("...", Services.get(key).hello());
    }

    @Test
    void keysAreSeparateAndRefuseNothing() {
        Services.Key<Greeting> a = Services.key("a", Greeting.class, () -> "a");
        Services.Key<Greeting> b = Services.key("b", Greeting.class, () -> "b");
        Services.register(a, () -> "registered");
        assertEquals("registered", Services.get(a).hello());
        assertEquals("b", Services.get(b).hello(), "registering one key leaves another alone");
        assertThrows(NullPointerException.class, () -> Services.register(b, null));
        assertThrows(NullPointerException.class, () -> Services.key("c", Greeting.class, null));
    }

    // ---- the three shared questions ----
    // Unit tests run without Minecraft's classes, so they cannot ask anything that takes a level or a position: here,
    // each key hands out its default with nothing registered; ServicesGameTests checks what the defaults answer.

    @Test
    void withoutATemperatureProviderTheDefaultAnswers() {
        assertSame(TemperatureProvider.NONE, TemperatureProvider.KEY.fallback());
        assertEquals("temperature", TemperatureProvider.KEY.name());
    }

    @Test
    void withoutAWindProviderItIsCalm() {
        WindProvider none = WindProvider.NONE;
        assertSame(none, WindProvider.KEY.fallback());
        assertEquals("wind", WindProvider.KEY.name());
        none.setOverride(WindColumn.uniform(10, 0)); // nothing to force without a provider
        assertTrue(none.describe().contains("calm"), none.describe());
    }

    @Test
    void withoutAStormProviderTheDefaultAnswers() {
        assertSame(StormProvider.NONE, StormProvider.KEY.fallback());
        assertEquals("storm", StormProvider.KEY.name());
    }

    @Test
    void theSharedKeysHandOutWhatIsRegistered() {
        // Stand-ins that answer nothing differently: what matters here is which one the key hands out.
        TemperatureProvider temperatures = new TemperatureProvider() {
        };
        WindProvider wind = new WindProvider() {
        };
        StormProvider storms = new StormProvider() {
        };
        TemperatureProvider temperaturesBefore = Services.unregister(TemperatureProvider.KEY);
        WindProvider windBefore = Services.unregister(WindProvider.KEY);
        StormProvider stormsBefore = Services.unregister(StormProvider.KEY);
        try {
            assertSame(TemperatureProvider.NONE, TemperatureProvider.get());
            assertSame(WindProvider.NONE, WindProvider.get());
            assertSame(StormProvider.NONE, StormProvider.get());
            Services.register(TemperatureProvider.KEY, temperatures);
            Services.register(WindProvider.KEY, wind);
            Services.register(StormProvider.KEY, storms);
            assertSame(temperatures, TemperatureProvider.get());
            assertSame(wind, WindProvider.get());
            assertSame(storms, StormProvider.get());
        } finally {
            Services.unregister(TemperatureProvider.KEY);
            Services.unregister(WindProvider.KEY);
            Services.unregister(StormProvider.KEY);
            if (temperaturesBefore != null) {
                Services.register(TemperatureProvider.KEY, temperaturesBefore);
            }
            if (windBefore != null) {
                Services.register(WindProvider.KEY, windBefore);
            }
            if (stormsBefore != null) {
                Services.register(StormProvider.KEY, stormsBefore);
            }
        }
    }
}
