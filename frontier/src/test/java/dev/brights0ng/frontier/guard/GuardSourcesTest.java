package dev.brights0ng.frontier.guard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Split seam S12: guards beyond the tag come only from registered sources. Unit tests run without Minecraft, so the
 * entities here are stand-ins (names).
 */
class GuardSourcesTest {

    @Test
    void withNothingRegisteredOnlyTheTagCounts() {
        GuardSources<String> sources = new GuardSources<>();
        assertFalse(sources.isCandidate("colonist"));
        assertFalse(sources.isGuard("knight"));
    }

    @Test
    void aRegisteredSourceAddsItsGuards() {
        GuardSources<String> sources = new GuardSources<>();
        // A fake colony: colonists are worth tracking, and the knight is a guard.
        sources.register(new GuardSources.Source<String>() {
            @Override
            public boolean isCandidate(String entity) {
                return entity.equals("colonist") || entity.equals("knight");
            }

            @Override
            public boolean isGuard(String entity) {
                return entity.equals("knight");
            }
        });
        assertTrue(sources.isCandidate("colonist"));
        assertFalse(sources.isGuard("colonist"), "a colonist without a guard job is not a guard yet");
        assertTrue(sources.isCandidate("knight"));
        assertTrue(sources.isGuard("knight"));
        assertFalse(sources.isCandidate("zombie"));
        assertFalse(sources.isGuard("zombie"));

        // Any source saying yes is enough.
        sources.register(new GuardSources.Source<Object>() {
            @Override
            public boolean isCandidate(Object entity) {
                return entity.equals("guard villager");
            }

            @Override
            public boolean isGuard(Object entity) {
                return entity.equals("guard villager");
            }
        });
        assertTrue(sources.isGuard("guard villager"));
        assertTrue(sources.isGuard("knight"));
    }
}
