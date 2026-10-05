package dev.brights0ng.enginesandempires.geophone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.brights0ng.enginesandempires.oregen.Deposit;
import dev.brights0ng.enginesandempires.oregen.DepositBody;
import dev.brights0ng.enginesandempires.oregen.DepositResolver;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.OreType;
import dev.brights0ng.enginesandempires.oregen.OreTypes;
import dev.brights0ng.enginesandempires.oregen.ResolvedDeposit;

/** The wave model applied to real deposits, not made-up ones. */
class DepositEchoesTest {

    private static final long SEED = 20260920L;
    private static final DepositResolver.Outcome FIRST_TRY = new DepositResolver.Outcome(0, 1.0, false);

    private static ResolvedDeposit deposit(OreType type) {
        Deposit deposit = new OreMap(SEED, type.layer()).depositsNear(0, 0, type.layer().scale() * 4).get(0);
        return new ResolvedDeposit(deposit, FIRST_TRY, DepositBody.generate(deposit, type));
    }

    @Test
    void theCellsAreTheCentresOfTheDepositsOreBlocks() {
        for (OreType type : List.of(OreTypes.IRON, OreTypes.GOLD, OreTypes.COPPER)) {
            ResolvedDeposit resolved = deposit(type);
            SeismicWave.Echoer echoer = DepositEchoes.of(resolved);
            WaveModel.OreCells cells = echoer.cells();
            assertEquals(type.id(), echoer.oreId());
            assertEquals(resolved.body().oreCount(), cells.size(), type.id());
            for (int i = 0; i < cells.size(); i++) {
                assertEquals(0.5, cells.x()[i] - Math.floor(cells.x()[i]), 1.0e-9, "a block centre");
                int dx = (int) Math.floor(cells.x()[i]) - resolved.deposit().x();
                int dy = (int) Math.floor(cells.y()[i]) - resolved.body().centerY();
                int dz = (int) Math.floor(cells.z()[i]) - resolved.deposit().z();
                byte kind = resolved.body().at(dx, dy, dz);
                assertTrue(kind == DepositBody.ORE || kind == DepositBody.RICH, type.id() + " cell " + i + " is not ore");
            }
        }
    }

    @Test
    void aGeophoneNextToADepositHearsItSoonerThanOneFurtherAway() {
        ResolvedDeposit resolved = deposit(OreTypes.IRON);
        SeismicWave.Echoer echoer = DepositEchoes.of(resolved);
        // Strike right on the deposit, with one geophone beside the strike and another 60 blocks from it.
        double x = echoer.cells().x()[0];
        double y = echoer.cells().y()[0];
        double z = echoer.cells().z()[0];
        SeismicWave.Receiver near = new SeismicWave.Receiver(1, x + 2, y, z, GeophoneTier.ANDESITE);
        SeismicWave.Receiver far = new SeismicWave.Receiver(2, x + 60, y, z, GeophoneTier.ANDESITE);

        List<SeismicWave.Pulse> pulses = SeismicWave.pulses(x, y, z, 128, List.of(echoer), List.of(near, far));
        SeismicWave.Pulse nearPulse = pulses.stream().filter(p -> p.receiver() == 1).findFirst().orElseThrow();
        SeismicWave.Pulse farPulse = pulses.stream().filter(p -> p.receiver() == 2).findFirst().orElseThrow();
        assertTrue(nearPulse.startTicks() < farPulse.startTicks());
        assertTrue(nearPulse.startTicks() <= 2, "struck on the ore, with a geophone two blocks off: the echo is immediate");
    }

    @Test
    void anAndesiteGeophoneHearsARealIronDepositButNotARealCoalOne() {
        SeismicWave.Echoer iron = DepositEchoes.of(deposit(OreTypes.IRON));
        SeismicWave.Echoer coal = DepositEchoes.of(deposit(OreTypes.COAL));
        double x = iron.cells().x()[0];
        double y = iron.cells().y()[0];
        double z = iron.cells().z()[0];
        SeismicWave.Receiver andesite = new SeismicWave.Receiver(1, x, y, z, GeophoneTier.ANDESITE);
        assertFalse(SeismicWave.pulses(x, y, z, 128, List.of(iron), List.of(andesite)).isEmpty());
        assertTrue(SeismicWave.pulses(x, y, z, 128, List.of(coal), List.of(andesite)).isEmpty(),
                "andesite geophones do not hear coal, however close");
    }

    @Test
    void bigDepositsAreDrawnOutLongerThanSmallOnesOnAverage() {
        // Heard from the middle of the deposit, the echo lasts as long as it takes to reach its farthest ore and back.
        // Wide coal seams should take longer than small emerald pockets, though any single pair of deposits could differ.
        double seams = meanSpread(OreTypes.COAL);
        double pockets = meanSpread(OreTypes.EMERALD);
        assertTrue(seams > pockets, "coal seams average " + seams + " ticks, emerald pockets " + pockets);
    }

    private static double meanSpread(OreType type) {
        List<Deposit> deposits = new OreMap(SEED, type.layer()).depositsNear(0, 0, type.layer().scale() * 8);
        int used = Math.min(12, deposits.size());
        double total = 0.0;
        for (Deposit deposit : deposits.subList(0, used)) {
            ResolvedDeposit resolved = new ResolvedDeposit(deposit, FIRST_TRY, DepositBody.generate(deposit, type));
            SeismicWave.Echoer echoer = DepositEchoes.of(resolved);
            double x = deposit.x() + 0.5;
            double y = resolved.body().centerY() + 0.5;
            double z = deposit.z() + 0.5;
            total += WaveModel.arrival(echoer.cells(), x, y, z, x, y, z, 10_000).spreadTicks();
        }
        return total / used;
    }
}
