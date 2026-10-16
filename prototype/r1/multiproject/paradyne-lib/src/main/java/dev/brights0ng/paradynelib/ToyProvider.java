package dev.brights0ng.paradynelib;

/** R1 prototype: stands in for TemperatureProvider and friends. */
public interface ToyProvider {

    ToyProvider NONE = () -> "none";

    String name();
}
