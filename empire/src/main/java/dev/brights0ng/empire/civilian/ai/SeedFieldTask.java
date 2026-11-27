// Written by Gemini.
package dev.brights0ng.empire.civilian.ai;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import dev.brights0ng.empire.civilian.CivilianEntity;
import dev.brights0ng.empire.rules.FarmerUtility;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.CropBlock;

/**
 * Level-3 Farmer task (seeding-only): selects the nearest {@code FIELD} room with seedable empty farmland and plants
 * every empty plot in that field before returning to Level 2 for re-evaluation.
 *
 * <p>Interrupts cleanly (preserving Seed momentum and requesting a shed fetch) if the farmer runs out of carried seeds
 * while more seeds remain available in shed storage.
 */
public final class SeedFieldTask implements CivilianTask {

    private final FarmWorkScanner scanner;
    private final Set<BlockPos> skippedPositions = new HashSet<>();
    private int activeRoomIndex = -1;
    private boolean interruptedOutOfSeeds;
    @Nullable
    private FarmWorkScanner.EmptyPlotTarget currentTarget;
    @Nullable
    private CivilianAction currentAction;

    public SeedFieldTask(FarmWorkScanner scanner) {
        this.scanner = scanner;
    }

    @Override
    public String name() {
        return "farmer_seed_field";
    }

    @Override
    public boolean isEligible(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        return snap.valid()
                && FarmWorkScanner.countSeedableEmptyPlots(ctx.level(), ctx.civilian(), snap) > 0;
    }

    @Override
    public double baseWeight() {
        return FarmerUtility.BASE_PLANT_WEIGHT;
    }

    @Override
    public double urgency(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        return FarmerUtility.plantUrgency(FarmWorkScanner.countSeedableEmptyPlots(ctx.level(), ctx.civilian(), snap));
    }

    @Override
    public MomentumPolicy momentumPolicy() {
        return MomentumPolicy.SET;
    }

    @Override
    public double momentumBonus() {
        return FarmerUtility.FIELD_WORK_MOMENTUM_BONUS;
    }

    @Override
    public void start(AiContext ctx) {
        skippedPositions.clear();
        activeRoomIndex = -1;
        interruptedOutOfSeeds = false;
        currentTarget = null;
        currentAction = null;

        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.emptyPlots().isEmpty()) {
            return;
        }
        activeRoomIndex = pickNearestSeedableRoom(ctx.level(), ctx.civilian(), snap);
        if (activeRoomIndex >= 0) {
            beginNextPlotInRoom(ctx);
        }
    }

    @Override
    public CivilianAction.Status tick(AiContext ctx) {
        if (interruptedOutOfSeeds) {
            return CivilianAction.Status.FAILED;
        }
        if (activeRoomIndex < 0) {
            return CivilianAction.Status.FAILED;
        }
        if (currentAction == null) {
            if (beginNextPlotInRoom(ctx)) {
                return CivilianAction.Status.RUNNING;
            }
            return interruptedOutOfSeeds ? CivilianAction.Status.FAILED : CivilianAction.Status.SUCCEEDED;
        }

        CivilianAction.Status status = currentAction.tick(ctx);
        if (status == CivilianAction.Status.RUNNING) {
            return CivilianAction.Status.RUNNING;
        }

        currentAction.stop(ctx);
        boolean wasMove = currentAction instanceof MoveToAction;
        currentAction = null;

        if (wasMove) {
            if (status == CivilianAction.Status.SUCCEEDED
                    && currentTarget != null
                    && currentTarget.targetSeed() != null) {
                if (FarmWorkScanner.countCarried(ctx.civilian(), currentTarget.targetSeed()) <= 0) {
                    return handleMissingSeed(ctx, currentTarget.targetSeed());
                }
                currentAction = createPlantAction(currentTarget.cropPos(), currentTarget.targetSeed());
                currentAction.start(ctx);
                return CivilianAction.Status.RUNNING;
            }
            if (currentTarget != null) {
                skippedPositions.add(currentTarget.cropPos());
            }
            if (beginNextPlotInRoom(ctx)) {
                return CivilianAction.Status.RUNNING;
            }
            return interruptedOutOfSeeds ? CivilianAction.Status.FAILED : CivilianAction.Status.SUCCEEDED;
        }

        scanner.invalidate();
        if (status != CivilianAction.Status.SUCCEEDED && currentTarget != null) {
            skippedPositions.add(currentTarget.cropPos());
        }

        // Continue planting this same field (keeping seeds equipped) until done or out of seeds.
        if (beginNextPlotInRoom(ctx)) {
            return CivilianAction.Status.RUNNING;
        }
        return interruptedOutOfSeeds ? CivilianAction.Status.FAILED : CivilianAction.Status.SUCCEEDED;
    }

    @Override
    public void stop(AiContext ctx) {
        if (currentAction != null) {
            currentAction.stop(ctx);
            currentAction = null;
        }
        currentTarget = null;
        activeRoomIndex = -1;
        interruptedOutOfSeeds = false;
        skippedPositions.clear();
        EquipAction.stowMainHand(ctx.civilian());
    }

    @Override
    public boolean canYieldCleanly() {
        return currentAction == null || currentAction.canYieldCleanly();
    }

    @Override
    public String activeActionName() {
        return currentAction != null ? currentAction.name() : "seed_field";
    }

    private static int pickNearestSeedableRoom(ServerLevel level, CivilianEntity civ,
                                               FarmWorkScanner.FarmSnapshot snap) {
        BlockPos civPos = civ.blockPosition();
        int bestRoom = -1;
        double bestDistSq = Double.MAX_VALUE;
        for (FarmWorkScanner.EmptyPlotTarget plot : snap.emptyPlots()) {
            if (!FarmWorkScanner.isSeedReachable(level, civ, snap, plot.targetSeed())) {
                continue;
            }
            double d2 = plot.cropPos().distSqr(civPos);
            if (bestRoom < 0 || d2 < bestDistSq) {
                bestRoom = plot.roomIndex();
                bestDistSq = d2;
            }
        }
        return bestRoom;
    }

    private boolean beginNextPlotInRoom(AiContext ctx) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (!snap.valid() || snap.emptyPlots().isEmpty()) {
            return false;
        }
        CivilianEntity civ = ctx.civilian();
        BlockPos civPos = civ.blockPosition();
        FarmWorkScanner.EmptyPlotTarget bestCarried = null;
        double bestCarriedDistSq = Double.MAX_VALUE;
        Item missingSeedInShed = null;

        for (FarmWorkScanner.EmptyPlotTarget plot : snap.emptyPlots()) {
            if (plot.roomIndex() != activeRoomIndex || skippedPositions.contains(plot.cropPos())) {
                continue;
            }
            Item seed = plot.targetSeed();
            if (seed == null) {
                continue;
            }
            if (FarmWorkScanner.countCarried(civ, seed) > 0) {
                double d2 = plot.cropPos().distSqr(civPos);
                if (bestCarried == null || d2 < bestCarriedDistSq) {
                    bestCarried = plot;
                    bestCarriedDistSq = d2;
                }
            } else if (missingSeedInShed == null
                    && FarmWorkScanner.isSeedReachable(ctx.level(), civ, snap, seed)) {
                missingSeedInShed = seed;
            }
        }

        if (bestCarried == null) {
            currentTarget = null;
            if (missingSeedInShed != null) {
                scanner.requestSeedFetch();
                interruptedOutOfSeeds = true;
            }
            return false;
        }

        currentTarget = bestCarried;
        double reach = FarmWorkScanner.workReach(civ);
        if (!bestCarried.cropPos().closerToCenterThan(civ.position(), reach)) {
            currentAction = new MoveToAction(bestCarried.cropPos(), reach, null, null);
        } else {
            currentAction = createPlantAction(bestCarried.cropPos(), bestCarried.targetSeed());
        }
        currentAction.start(ctx);
        return true;
    }

    private CivilianAction.Status handleMissingSeed(AiContext ctx, Item seed) {
        FarmWorkScanner.FarmSnapshot snap = scanner.scan(ctx);
        if (FarmWorkScanner.isSeedReachable(ctx.level(), ctx.civilian(), snap, seed)) {
            scanner.requestSeedFetch();
            interruptedOutOfSeeds = true;
            return CivilianAction.Status.FAILED;
        }
        return CivilianAction.Status.SUCCEEDED;
    }

    private static PlaceBlockAction createPlantAction(BlockPos pos, Item seed) {
        return new PlaceBlockAction(
                pos,
                stack -> stack.is(seed),
                stack -> {
                    CropBlock crop = FarmCrops.cropBlockForSeed(stack.getItem());
                    return crop != null ? crop.defaultBlockState() : null;
                },
                FarmerUtility.PLANT_BASE_TICKS,
                FarmerUtility.PLANT_EXHAUSTION,
                true
        );
    }
}
