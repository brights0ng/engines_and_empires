package dev.brights0ng.paradynelib;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** R1 prototype: the plan's tiny service registry (register at mod setup, look up by key, safe default when none). */
public final class Services {

    private static final Map<Class<?>, Object> PROVIDERS = new ConcurrentHashMap<>();

    private Services() {
    }

    public static <T> void register(Class<T> key, T provider) {
        PROVIDERS.put(key, provider);
    }

    public static <T> T get(Class<T> key, T fallback) {
        Object provider = PROVIDERS.get(key);
        return provider == null ? fallback : key.cast(provider);
    }

    static void clear() {
        PROVIDERS.clear();
    }
}
