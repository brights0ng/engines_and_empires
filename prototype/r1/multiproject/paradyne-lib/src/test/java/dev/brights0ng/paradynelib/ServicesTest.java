package dev.brights0ng.paradynelib;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ServicesTest {

    @AfterEach
    void clear() {
        Services.clear();
    }

    @Test
    void fallsBackWhenNothingIsRegistered() {
        assertEquals("none", Services.get(ToyProvider.class, ToyProvider.NONE).name());
    }

    @Test
    void returnsTheRegisteredProvider() {
        Services.register(ToyProvider.class, () -> "registered");
        assertEquals("registered", Services.get(ToyProvider.class, ToyProvider.NONE).name());
    }
}
