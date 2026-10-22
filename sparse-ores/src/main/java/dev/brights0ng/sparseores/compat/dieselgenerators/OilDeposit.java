// Written by Gemini.
package dev.brights0ng.sparseores.compat.dieselgenerators;

import dev.brights0ng.sparseores.shape.oil.OilPotentialField;
import dev.brights0ng.sparseores.shape.oil.OilTrapType;

/**
 * An active, multi-chunk oil reservoir that contracts in area as hydrocarbons are pumped.
 */
public final class OilDeposit {

    private final long seed;
    private final int centerX;
    private final int centerZ;
    private final OilTrapType trapType;
    private final double strikeAngle;
    private final OilPotentialField field;
    private final int initialAmount;
    private int remainingAmount;

    public OilDeposit(long seed, int centerX, int centerZ, OilTrapType trapType, double strikeAngle,
                      OilPotentialField field, int initialAmount, int remainingAmount) {
        this.seed = seed;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.trapType = trapType;
        this.strikeAngle = strikeAngle;
        this.field = field;
        this.initialAmount = initialAmount;
        this.remainingAmount = Math.max(0, Math.min(initialAmount, remainingAmount));
    }

    public long seed() {
        return seed;
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public OilTrapType trapType() {
        return trapType;
    }

    public double strikeAngle() {
        return strikeAngle;
    }

    public OilPotentialField field() {
        return field;
    }

    public int initialAmount() {
        return initialAmount;
    }

    public synchronized int remainingAmount() {
        return remainingAmount;
    }

    public synchronized double fullness() {
        return initialAmount <= 0 ? 0.0 : ((double) remainingAmount / initialAmount);
    }

    public synchronized boolean isDepleted() {
        return remainingAmount <= 0;
    }

    /**
     * Reports oil accessible to this 16x16 chunk.
     * Returns 0 if the reservoir's shrinking boundary has retreated past this chunk.
     */
    public synchronized int getChunkOilAmount(int chunkX, int chunkZ) {
        if (remainingAmount <= 0) {
            return 0;
        }
        double f = fullness();
        if (!field.touchesChunk(chunkX, chunkZ, f)) {
            return 0;
        }
        // Cap below CDG's 10,000,000 infinite threshold so finite pumpjack stroke depletion occurs
        return Math.min(remainingAmount, 8_000_000);
    }

    /**
     * Extracts fluid volume from the master reservoir.
     *
     * @param amount fluid volume in mB
     * @return actual amount drained
     */
    public synchronized int extractOil(int amount) {
        if (remainingAmount <= 0 || amount <= 0) {
            return 0;
        }
        int drained = Math.min(amount, remainingAmount);
        remainingAmount -= drained;
        return drained;
    }

    /**
     * Updates remaining amount directly (e.g. from debug commands or refill).
     */
    public synchronized void setRemainingAmount(int newAmount) {
        this.remainingAmount = Math.max(0, Math.min(initialAmount, newAmount));
    }

    /**
     * Returns the active perimeter polygon at current fullness.
     */
    public synchronized double[][] polygon(int numPoints) {
        return field.polygon(fullness(), numPoints);
    }
}
