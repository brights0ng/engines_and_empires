package dev.brights0ng.frontier.incursion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Split seam S14: beyond its powered and lit properties, a loud machine runs only if a registered provider says so.
 * Unit tests run without Minecraft, so the machines here are stand-ins (names).
 */
class RunningProvidersTest {

    @Test
    void withNothingRegisteredNoMachineRunsByItsBlockEntity() {
        RunningProviders<String> providers = new RunningProviders<>();
        assertFalse(providers.isRunning("turning windmill bearing"));
    }

    @Test
    void aRegisteredProviderTellsWhichRun() {
        RunningProviders<String> providers = new RunningProviders<>();
        Set<String> turning = Set.of("turning windmill bearing");
        providers.register(turning::contains);
        assertTrue(providers.isRunning("turning windmill bearing"));
        assertFalse(providers.isRunning("still mechanical press"));

        // Any provider saying yes is enough.
        RunningProviders.Provider<Object> engines = machine -> machine.toString().endsWith("engine");
        providers.register(engines);
        assertTrue(providers.isRunning("steam engine"));
        assertTrue(providers.isRunning("turning windmill bearing"));
        assertFalse(providers.isRunning("still mechanical press"));
    }
}
