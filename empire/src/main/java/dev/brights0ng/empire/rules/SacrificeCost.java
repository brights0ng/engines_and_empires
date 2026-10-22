// Written by Gemini.
package dev.brights0ng.empire.rules;

import java.util.List;

public interface SacrificeCost {

    record Requirement(String itemId, int count) {}

    List<Requirement> costFor(int dominion);
}
