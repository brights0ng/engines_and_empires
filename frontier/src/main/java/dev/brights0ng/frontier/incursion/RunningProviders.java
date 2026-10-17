package dev.brights0ng.frontier.incursion;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ways to tell that a loud machine is running beyond its own block state (split seam S14). Frontier itself knows only
 * the {@code powered} and {@code lit} properties ({@link LoudMachines#isRunning}); another mod registers a
 * {@link Provider} at mod setup with {@code LoudMachines.PROVIDERS.register} (the pack registers Create's: a kinetic
 * block turning, or an engine's shaft turning). With none registered, a machine runs only by those properties.
 *
 * <p>Generic in what it is asked about only so that unit tests, which run without Minecraft, can try it with
 * stand-ins; {@link LoudMachines} keeps a {@code RunningProviders<LoudMachines.Machine>}.
 */
public final class RunningProviders<M> {

    /** A way to tell whether a machine is running right now. */
    @FunctionalInterface
    public interface Provider<M> {
        boolean isRunning(M machine);
    }

    private final List<Provider<? super M>> providers = new CopyOnWriteArrayList<>();

    /** Adds a provider. Call at mod setup. */
    public void register(Provider<? super M> provider) {
        providers.add(Objects.requireNonNull(provider));
    }

    /** Whether any registered provider says the machine is running. */
    public boolean isRunning(M machine) {
        for (Provider<? super M> provider : providers) {
            if (provider.isRunning(machine)) {
                return true;
            }
        }
        return false;
    }
}
