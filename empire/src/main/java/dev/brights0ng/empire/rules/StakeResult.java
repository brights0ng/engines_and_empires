// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.Locale;

public enum StakeResult {
    OK,
    NOT_ADJACENT,
    NOT_SETTLED,
    OWNED_BY_OTHER,
    ALREADY_OURS,
    NO_DOMINION;

    public String translationKey() {
        return "message.empire.stake." + name().toLowerCase(Locale.ROOT);
    }
}
