package dev.brights0ng.enginesandempires.oregen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class BodyCacheTest {

    @Test
    void returnsTheSameBodyForTheSameDeposit() {
        OreType type = OreTypes.EMERALD;
        Deposit deposit = new OreMap(BodySamples.SEED, type.layer()).depositsNear(0, 0, 4000).get(0);
        BodyCache cache = new BodyCache();
        assertSame(cache.get(deposit, type), cache.get(deposit, type));
    }

    @Test
    void remembersOnlyAFewRecentBodies() {
        OreType type = OreTypes.EMERALD;
        List<Deposit> deposits = new OreMap(BodySamples.SEED, type.layer()).depositsNear(0, 0, 40000);
        assertTrue(deposits.size() > 200);
        BodyCache cache = new BodyCache();
        for (Deposit deposit : deposits.subList(0, 200)) {
            cache.get(deposit, type);
        }
        assertTrue(cache.size() <= 64, "cache holds " + cache.size());
    }

    @Test
    void differentAttemptsAtTheSameDepositAreCachedSeparately() {
        OreType type = OreTypes.EMERALD;
        Deposit deposit = new OreMap(BodySamples.SEED, type.layer()).depositsNear(0, 0, 4000).get(0);
        BodyCache cache = new BodyCache();
        DepositBody first = cache.get(deposit, type, 0);
        DepositBody second = cache.get(deposit, type, 1);
        assertNotSame(first, second);
        assertEquals(1, second.attempt());
        assertSame(first, cache.get(deposit, type), "the two-argument form is attempt 0");
        assertSame(second, cache.get(deposit, type, 1));
    }

    @Test
    void aBodyThatWasPutIsNotBuiltAgain() {
        OreType type = OreTypes.EMERALD;
        Deposit deposit = new OreMap(BodySamples.SEED, type.layer()).depositsNear(0, 0, 4000).get(0);
        BodyCache cache = new BodyCache();
        DepositBody body = DepositBody.generate(deposit, type, 2);
        cache.put(deposit, 2, body);
        assertSame(body, cache.get(deposit, type, 2));
    }
}
