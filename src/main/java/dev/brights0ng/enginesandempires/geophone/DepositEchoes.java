package dev.brights0ng.enginesandempires.geophone;

import java.util.Arrays;

import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;

/**
 * Turns a deposit into what a vibration bounces off: the centres of its ore blocks. Every ore block of the
 * deposit's body reflects, as the deposit was generated, whether or not it has since been mined. (A
 * deposit that has been mined out altogether is left out of a search before it gets here.)
 *
 * <p>Nothing here touches Minecraft.
 */
public final class DepositEchoes {

    /** The deposit as a vibration sees it. */
    public static SeismicWave.Echoer of(ResolvedDeposit resolved) {
        Deposit deposit = resolved.deposit();
        DepositBody body = resolved.body();
        int capacity = body.oreCount();
        double[] x = new double[capacity];
        double[] y = new double[capacity];
        double[] z = new double[capacity];
        int[] count = {0};
        body.forEach((dx, dy, dz, kind) -> {
            if ((kind == DepositBody.ORE || kind == DepositBody.RICH) && count[0] < capacity) {
                x[count[0]] = deposit.x() + dx + 0.5;
                y[count[0]] = body.centerY() + dy + 0.5;
                z[count[0]] = deposit.z() + dz + 0.5;
                count[0]++;
            }
        });
        if (count[0] == capacity) {
            return new SeismicWave.Echoer(deposit.seed(), deposit.oreId(), new WaveModel.OreCells(x, y, z));
        }
        return new SeismicWave.Echoer(deposit.seed(), deposit.oreId(), new WaveModel.OreCells(
                Arrays.copyOf(x, count[0]), Arrays.copyOf(y, count[0]), Arrays.copyOf(z, count[0])));
    }

    private DepositEchoes() {
    }
}
