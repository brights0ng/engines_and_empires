package dev.brights0ng.paradynelib.services;

import java.util.Objects;

/**
 * How the family's mods answer each other's questions without depending on each other (split seams S1-S3). A question
 * is a {@link Key}: the mod that asks defines it (or the library does, for the shared ones), with a default answer. The
 * mod that can answer registers its provider under the key at mod setup; the mod that asks calls {@link #get}, and gets
 * that provider, or the key's default when nobody registered one, which is how the asking mod behaves without the other.
 *
 * <p>One provider per key; the last one registered wins. Safe to read from any thread.
 */
public final class Services {

    /**
     * A question one mod asks another, and its answer when nobody registered one.
     *
     * @param <T> what answers it
     */
    public static final class Key<T> {

        private final String name;
        private final Class<T> type;
        private final T fallback;
        private volatile T provider;

        private Key(String name, Class<T> type, T fallback) {
            this.name = Objects.requireNonNull(name);
            this.type = Objects.requireNonNull(type);
            this.fallback = Objects.requireNonNull(fallback);
        }

        public String name() {
            return name;
        }

        /** The answer when nobody registered a provider. */
        public T fallback() {
            return fallback;
        }

        @Override
        public String toString() {
            return "Services.Key[" + name + ", " + type.getSimpleName() + "]";
        }
    }

    /** A new key, named for debug output, answered by {@code fallback} until a provider is registered. */
    public static <T> Key<T> key(String name, Class<T> type, T fallback) {
        return new Key<>(name, type, fallback);
    }

    /**
     * Makes {@code provider} the answer to {@code key}. Call at mod setup; the last one registered wins.
     *
     * @return the provider it replaced, or null
     */
    public static <T> T register(Key<T> key, T provider) {
        T previous = key.provider;
        key.provider = key.type.cast(Objects.requireNonNull(provider));
        return previous;
    }

    /** Goes back to the default answer (tests). Returns the provider it removed, or null. */
    public static <T> T unregister(Key<T> key) {
        T previous = key.provider;
        key.provider = null;
        return previous;
    }

    /** The registered provider for {@code key}, or the key's default when none is. */
    public static <T> T get(Key<T> key) {
        T provider = key.provider;
        return provider != null ? provider : key.fallback;
    }

    /** Whether a provider is registered for {@code key}. */
    public static boolean isRegistered(Key<?> key) {
        return key.provider != null;
    }

    private Services() {
    }
}
