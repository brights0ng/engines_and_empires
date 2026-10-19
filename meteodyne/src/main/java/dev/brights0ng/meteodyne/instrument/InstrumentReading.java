// Written by ChatGPT.
package dev.brights0ng.meteodyne.instrument;

/** A server measurement carried by an item. Its world and age prevent showing a stale reading after travel. */
public record InstrumentReading(String dimension, long time, float value) {
    public static final long MAX_AGE = 60;
    public boolean usable(String currentDimension, long now) {
        return dimension.equals(currentDimension) && now >= time && now - time <= MAX_AGE && Float.isFinite(value);
    }
}
