// Written by Gemini.
// Edited by Gemini: fixed full reservoir level-set containment check and chunk intersection.
package dev.brights0ng.sparseores.shape.oil;

/**
 * Continuous potential field representing an oil reservoir's thickness and boundary.
 *
 * <p>The potential field &Phi;(x, z) takes values in [0, 1], where 1.0 represents the thickest
 * structural core and 0.0 is the outer boundary. As oil is extracted, the reservoir shrinks
 * organically along level sets &Phi;(x, z) &ge; 1 - fullness, where fullness = V_rem / V_total.
 */
public interface OilPotentialField {

    /** The geological trap type of this reservoir. */
    OilTrapType trapType();

    /** The deposit center X in world block coordinates. */
    int centerX();

    /** The deposit center Z in world block coordinates. */
    int centerZ();

    /** Maximum radius in blocks covering the full reservoir boundary. */
    double maxRadius();

    /**
     * Normalized reservoir potential &Phi;(x, z) &in; [0, 1].
     * Returns 0.0 outside the maximum reservoir bounds.
     */
    double potential(double x, double z);

    /**
     * Whether the block position (x, z) is currently saturated with oil given the reservoir fullness fraction.
     */
    default boolean contains(double x, double z, double fullness) {
        if (fullness <= 0.0001) {
            return false;
        }
        double pot = potential(x, z);
        return pot > 0.0 && pot >= (1.0 - fullness);
    }

    /**
     * Whether any part of the 16x16 chunk column touches active oil at the given fullness.
     */
    default boolean touchesChunk(int chunkX, int chunkZ, double fullness) {
        if (fullness <= 0.0001) {
            return false;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;

        // Bounding circle culling
        int midX = minX + 8;
        int midZ = minZ + 8;
        double dx = midX - centerX();
        double dz = midZ - centerZ();
        double distSq = dx * dx + dz * dz;
        double reach = maxRadius() + 16.0;
        if (distSq > reach * reach) {
            return false;
        }

        // Check center and four corners plus midpoint edges
        if (contains(midX, midZ, fullness)) return true;
        if (contains(minX, minZ, fullness)) return true;
        if (contains(maxX, minZ, fullness)) return true;
        if (contains(minX, maxZ, fullness)) return true;
        if (contains(maxX, maxZ, fullness)) return true;
        if (contains(midX, minZ, fullness)) return true;
        if (contains(midX, maxZ, fullness)) return true;
        if (contains(minX, midZ, fullness)) return true;
        if (contains(maxX, midZ, fullness)) return true;
        return false;
    }

    /**
     * Generates the 2D perimeter boundary polygon [x, z] at the given fullness.
     * Returns an empty array if fullness &le; 0.0001.
     *
     * @param fullness fraction of oil remaining &in; (0, 1]
     * @param numPoints resolution hint for polygon vertices
     * @return array of [x, z] coordinates in order around the perimeter
     */
    double[][] polygon(double fullness, int numPoints);
}
