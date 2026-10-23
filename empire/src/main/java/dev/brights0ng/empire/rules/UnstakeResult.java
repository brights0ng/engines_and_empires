// Written by Gemini; DIVINE_CHUNK added by Claude.
package dev.brights0ng.empire.rules;

import java.util.Locale;

public enum UnstakeResult {
    OK,
    NOT_OURS,
    CORE_CHUNK,
    /** One of the 8 chunks around the core chunk: with it, the empire's divine area. */
    DIVINE_CHUNK,
    BRIDGE_CHUNK;

    public String translationKey() {
        return "message.empire.unstake." + name().toLowerCase(Locale.ROOT);
    }
}
