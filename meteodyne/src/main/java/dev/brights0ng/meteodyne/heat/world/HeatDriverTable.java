package dev.brights0ng.meteodyne.heat.world;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/**
 * The heat drivers by id, for {@link HeatDrivers} (split seam S13). Meteodyne registers none of its own: a source whose
 * driver nobody registered stays off. The pack registers its machines' drivers.
 *
 * <p>Generic in the id and the driver only so that unit tests, which run without Minecraft, can try it with stand-ins;
 * {@link HeatDrivers} keeps a {@code HeatDriverTable<ResourceLocation, HeatDrivers.Driver>}.
 */
final class HeatDriverTable<K, D> {

    private final Map<K, D> drivers = new ConcurrentHashMap<>();

    /** Adds (or replaces) a driver. */
    void register(K id, D driver) {
        drivers.put(Objects.requireNonNull(id), Objects.requireNonNull(driver));
    }

    /** The driver registered as {@code id}, or null. */
    D get(K id) {
        return drivers.get(id);
    }

    /** How many drivers are registered. */
    int size() {
        return drivers.size();
    }

    /**
     * A driver's answer as the share of its source's power, 0-1: clamped, and 0 if it isn't a number or the driver
     * fails ({@code onFailure} hears why).
     */
    static double share(DoubleSupplier answer, Consumer<Throwable> onFailure) {
        try {
            double s = answer.getAsDouble();
            return Double.isFinite(s) ? Math.max(0, Math.min(1, s)) : 0;
        } catch (RuntimeException | LinkageError e) {
            onFailure.accept(e);
            return 0;
        }
    }
}
