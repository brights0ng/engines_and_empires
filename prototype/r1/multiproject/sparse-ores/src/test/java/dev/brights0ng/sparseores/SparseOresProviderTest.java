package dev.brights0ng.sparseores;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import dev.brights0ng.paradynelib.Services;
import dev.brights0ng.paradynelib.ToyProvider;

/** Unit tests of a module compile and run against the modules it requires. */
class SparseOresProviderTest {

    @Test
    void registersThroughTheLibrary() {
        Services.register(ToyProvider.class, new SparseOresProvider());
        assertEquals("sparse_ores", Services.get(ToyProvider.class, ToyProvider.NONE).name());
    }
}
