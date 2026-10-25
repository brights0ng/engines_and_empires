// Written by Claude.
package dev.brights0ng.empire.data;

import java.util.UUID;

/**
 * The traveller waiting at an empire's bonfire: its body, which of {@code TravellerWants.WANTS} it asked for, and the
 * game time it gives up and leaves.
 */
public record TravellerSlot(UUID body, int want, long leaveAt) {
}
