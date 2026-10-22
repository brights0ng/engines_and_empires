// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;

public enum UnstakeResult {
    OK,
    NOT_OURS,
    CORE_CHUNK,
    BRIDGE_CHUNK;

    public String translationKey() {
        return "message.empire.unstake." + name().toLowerCase(Locale.ROOT);
    }
}
