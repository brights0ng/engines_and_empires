package dev.brights0ng.enginesandempires.geophone;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dev.brights0ng.enginesandempires.EnginesAndEmpiresMod;
import dev.brights0ng.enginesandempires.oregen.OreMap;
import dev.brights0ng.enginesandempires.oregen.RemainingOre;
import dev.brights0ng.enginesandempires.oregen.worldgen.LevelDeposits;
import dev.brights0ng.enginesandempires.oregen.worldgen.LevelWorldView;
import dev.brights0ng.enginesandempires.oregen.worldgen.OreMaps;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Asks a dimension where its nearby deposits are: the game-facing side of {@link DepositFinder}.
 *
 * <p>A search has two parts, and they run in different places.
 * <ol>
 *   <li><b>Finding the deposits</b> is the expensive part: a wide search has to resolve every deposit it might
 *       reach, and one that has not been resolved yet costs milliseconds. It runs on a small pool of
 *       background threads, since it must never run on the main thread or the game would stutter, and touches
 *       nothing but the ore maps and the terrain estimate.</li>
 *   <li><b>Checking for mined-out deposits</b> reads blocks, which is only safe on the main thread, so it runs
 *       there once the search is done. It is cheap: chunks that are not loaded are never read (the ledger's
 *       recorded count stands in for them), and a deposit nowhere near a player costs almost nothing. Deposits
 *       that have been mined out are left out of the results, and each remaining one is given its count of ore
 *       blocks left.</li>
 * </ol>
 * {@link #scanAsync} does both and hands back a future that completes on the main thread, ready to use. Searches
 * over ground already resolved come back almost at once, because every deposit's fate is remembered.
 *
 * <p>Everything the search needs from the level is read on the calling thread, before the work is handed over, so
 * the background threads never touch the level itself.
 *
 * <p>Results are ground truth about what is there. See {@link DepositFinder}.
 */
@EventBusSubscriber(modid = EnginesAndEmpiresMod.MODID)
public final class DepositScanner {

    /**
     * A finished search.
     *
     * @param searchNanos    how long finding the deposits took
     * @param depletionNanos how long checking them for mined-out ore took, on the main thread
     */
    public record Scan(DepositFinder.Result result, long searchNanos, long depletionNanos) {
    }

    /** The level's state, read on the calling thread. */
    private record Prepared(LevelDeposits deposits, List<OreMap> maps) {
    }

    /** Background threads for searches: few, and slightly below normal priority, so worldgen and the game come first. */
    private static final int THREADS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    private static ExecutorService executor;

    /**
     * Searches and checks for depletion now, on the calling thread. Must be the main thread, and only for small
     * searches; use {@link #scanAsync} otherwise.
     */
    public static Scan scan(ServerLevel level, DepositFinder.Query query) {
        Prepared prepared = prepare(level);
        return applyDepletion(level, prepared, search(prepared, query));
    }

    /**
     * Searches on a background thread, then checks the results for mined-out deposits on the main thread. Must be
     * called on the main thread, because it reads the level's state there. The future completes on the main
     * thread, so the caller can use the result straight away.
     */
    public static CompletableFuture<Scan> scanAsync(ServerLevel level, DepositFinder.Query query) {
        Prepared prepared = prepare(level);
        return CompletableFuture.supplyAsync(() -> search(prepared, query), executor())
                .thenApplyAsync(searched -> applyDepletion(level, prepared, searched), level.getServer());
    }

    private static Prepared prepare(ServerLevel level) {
        LevelDeposits deposits = OreMaps.of(level);
        return new Prepared(deposits, OreMaps.forRealm(level.getSeed(), deposits.realm()));
    }

    private static Scan search(Prepared prepared, DepositFinder.Query query) {
        long start = System.nanoTime();
        DepositFinder.Result result = DepositFinder.find(prepared.maps(), query, prepared.deposits()::resolve);
        return new Scan(result, System.nanoTime() - start, 0L);
    }

    /** Leaves out the deposits that have been mined out, and gives the rest their remaining ore. Main thread only. */
    private static Scan applyDepletion(ServerLevel level, Prepared prepared, Scan searched) {
        long start = System.nanoTime();
        LevelWorldView world = new LevelWorldView(level);
        List<DepositFinder.Sighting> kept = new ArrayList<>();
        int depleted = 0;
        for (DepositFinder.Sighting sighting : searched.result().sightings()) {
            RemainingOre.Assessment assessment = RemainingOre.assess(
                    sighting.resolved(), prepared.deposits().ledger(), world, Integer.MAX_VALUE);
            if (assessment.depletedForGood()) {
                prepared.deposits().markDepleted(sighting.deposit().seed());
            }
            if (assessment.depleted()) {
                depleted++;
            } else {
                kept.add(sighting.withRemaining(assessment.remaining()));
            }
        }
        return new Scan(searched.result().afterDepletion(kept, depleted), searched.searchNanos(), System.nanoTime() - start);
    }

    private static synchronized ExecutorService executor() {
        if (executor == null) {
            AtomicInteger count = new AtomicInteger();
            executor = Executors.newFixedThreadPool(THREADS, runnable -> {
                Thread thread = new Thread(runnable, "engines-and-empires-scan-" + count.incrementAndGet());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            });
        }
        return executor;
    }

    @SubscribeEvent
    public static synchronized void onServerStopped(ServerStoppedEvent event) {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private DepositScanner() {
    }
}
