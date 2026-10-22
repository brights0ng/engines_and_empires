package dev.brights0ng.frontier.guard;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Guards a tag can't pick out (split seam S12): another mod
 * registers a {@link Source} at mod setup with {@code GuardTypes.SOURCES.register}. None by default: then only the
 * {@code guards} tag counts (see {@link GuardTypes}).
 *
 * <p>Generic in the entity type only so that unit tests, which run without Minecraft, can try it with stand-ins;
 * Frontier's is {@code GuardSources<Entity>}.
 */
public final class GuardSources<E> {

    /** A source of guards. */
    public interface Source<E> {

        /** Whether the entity is worth tracking: a guard now, or one that could become a guard later. */
        boolean isCandidate(E entity);

        /** Whether the entity is a guard right now. */
        boolean isGuard(E entity);
    }

    private final List<Source<? super E>> sources = new CopyOnWriteArrayList<>();

    /** Adds a source of guards. Call at mod setup. */
    public void register(Source<? super E> source) {
        sources.add(Objects.requireNonNull(source));
    }

    /** Whether any registered source finds the entity worth tracking. */
    public boolean isCandidate(E entity) {
        for (Source<? super E> source : sources) {
            if (source.isCandidate(entity)) {
                return true;
            }
        }
        return false;
    }

    /** Whether any registered source finds the entity a guard right now. */
    public boolean isGuard(E entity) {
        for (Source<? super E> source : sources) {
            if (source.isGuard(entity)) {
                return true;
            }
        }
        return false;
    }
}
