<!-- Measured and recorded by ChatGPT. -->
# Local-air startup characterization, 2026-10-27

Raw test output for the controlled 20 TPS, single-worker 9-cubed natural house. See [the interpretation and limits](../airflow/local-air-startup-results.md). These are individual characterization runs with different JVM warmup; they are not the player's live world or multiplayer capacity measurements.

## Baseline: 256 work units

```text
PACED_STARTUP Report[phase=READY, elapsedMs=8557.3059, requestTick=0, firstFieldMs=8357.3101, firstFieldObservedTick=167, firstFieldThroughTick=11, caughtUpMs=8557.3059, caughtUpObservedTick=171, phaseMs={CATCH_UP=199.9958, THERMAL_INPUT=109.5052, PLAN=61.5438, BAKE=46.42, COVERAGE=247.0411, ESTIMATE=7742.4315, READY=0.0, INITIALIZE=46.4256, CAPTURE=103.9429}, workerJobs=162, workerWallMs=709.8359, executorQueueMs=6.3098, completionWaitMs=7372.6711]
PACED_WORK {BAKE=Row[jobs=1, failed=0, discarded=0, wallNanos=8125200, queueNanos=40500, cpuNanos=0, cpuSamples=1, maxWallNanos=8125200, maxQueueNanos=40500], INITIALIZE=Row[jobs=1, failed=0, discarded=0, wallNanos=4031200, queueNanos=19800, cpuNanos=15625000, cpuSamples=1, maxWallNanos=4031200, maxQueueNanos=19800], PLAN=Row[jobs=1, failed=0, discarded=0, wallNanos=11323700, queueNanos=739400, cpuNanos=15625000, cpuSamples=1, maxWallNanos=11323700, maxQueueNanos=739400], ESTIMATE=Row[jobs=155, failed=0, discarded=0, wallNanos=681462700, queueNanos=5414900, cpuNanos=593750000, cpuSamples=155, maxWallNanos=21208300, maxQueueNanos=76100], STEP=Row[jobs=4, failed=0, discarded=0, wallNanos=4893100, queueNanos=95200, cpuNanos=0, cpuSamples=4, maxWallNanos=1473500, maxQueueNanos=24900]}
```

## Fill the existing 8 ms slice

```text
PACED_STARTUP Report[phase=READY, elapsedMs=4553.4144, requestTick=0, firstFieldMs=4460.5179, firstFieldObservedTick=89, firstFieldThroughTick=11, caughtUpMs=4553.4144, caughtUpObservedTick=91, phaseMs={PLAN=46.4871, THERMAL_INPUT=108.5983, CATCH_UP=92.8965, CAPTURE=114.4095, INITIALIZE=46.5195, READY=0.0, ESTIMATE=3850.4223, COVERAGE=247.6403, BAKE=46.4409}, workerJobs=82, workerWallMs=620.2832, executorQueueMs=2.9527, completionWaitMs=3456.4893]
PACED_WORK {BAKE=Row[jobs=1, failed=0, discarded=0, wallNanos=766200, queueNanos=68800, cpuNanos=0, cpuSamples=1, maxWallNanos=766200, maxQueueNanos=68800], ESTIMATE=Row[jobs=77, failed=0, discarded=0, wallNanos=616375900, queueNanos=2212100, cpuNanos=609375000, cpuSamples=77, maxWallNanos=8222300, maxQueueNanos=97500], PLAN=Row[jobs=1, failed=0, discarded=0, wallNanos=232500, queueNanos=555600, cpuNanos=0, cpuSamples=1, maxWallNanos=232500, maxQueueNanos=555600], STEP=Row[jobs=2, failed=0, discarded=0, wallNanos=2390900, queueNanos=79000, cpuNanos=0, cpuSamples=2, maxWallNanos=1274900, maxQueueNanos=40900], INITIALIZE=Row[jobs=1, failed=0, discarded=0, wallNanos=517700, queueNanos=37200, cpuNanos=0, cpuSamples=1, maxWallNanos=517700, maxQueueNanos=37200]}
```

## Prepaid continuations with shared credit

```text
PACED_STARTUP Report[phase=READY, elapsedMs=2815.4901, requestTick=0, firstFieldMs=2753.4915, firstFieldObservedTick=55, firstFieldThroughTick=11, caughtUpMs=2815.4901, caughtUpObservedTick=56, phaseMs={BAKE=61.9725, COVERAGE=247.7271, ESTIMATE=2150.7558, READY=0.0, INITIALIZE=46.3688, CAPTURE=107.5921, CATCH_UP=61.9986, THERMAL_INPUT=92.9463, PLAN=46.1289}, workerJobs=42, workerWallMs=666.7613, executorQueueMs=2.0997, completionWaitMs=1694.9397]
PACED_WORK {BAKE=Row[jobs=1, failed=0, discarded=0, wallNanos=819600, queueNanos=42300, cpuNanos=0, cpuSamples=1, maxWallNanos=819600, maxQueueNanos=42300, slices=1, maxSliceNanos=819600], ESTIMATE=Row[jobs=38, failed=0, discarded=0, wallNanos=663476300, queueNanos=1265500, cpuNanos=656250000, cpuSamples=38, maxWallNanos=50107600, maxQueueNanos=84300, slices=102, maxSliceNanos=8708200], PLAN=Row[jobs=1, failed=0, discarded=0, wallNanos=316700, queueNanos=750700, cpuNanos=0, cpuSamples=1, maxWallNanos=316700, maxQueueNanos=750700, slices=1, maxSliceNanos=316700], INITIALIZE=Row[jobs=1, failed=0, discarded=0, wallNanos=900800, queueNanos=18600, cpuNanos=0, cpuSamples=1, maxWallNanos=900800, maxQueueNanos=18600, slices=1, maxSliceNanos=900800], STEP=Row[jobs=1, failed=0, discarded=0, wallNanos=1247900, queueNanos=22600, cpuNanos=0, cpuSamples=1, maxWallNanos=1247900, maxQueueNanos=22600, slices=1, maxSliceNanos=1247900]}
```

## Final full-check repeat

```text
PACED_STARTUP Report[phase=READY, elapsedMs=2402.1895, requestTick=0, firstFieldMs=2355.6973, firstFieldObservedTick=47, firstFieldThroughTick=11, caughtUpMs=2402.1895, caughtUpObservedTick=48, phaseMs={CAPTURE=112.2623, CATCH_UP=46.4922, THERMAL_INPUT=108.6098, PLAN=46.5063, BAKE=46.3811, COVERAGE=247.4967, ESTIMATE=1748.1212, READY=0.0, INITIALIZE=46.3199}, workerJobs=34, workerWallMs=567.6354, executorQueueMs=2.4765, completionWaitMs=1361.4983]
PACED_WORK {BAKE=Row[jobs=1, failed=0, discarded=0, wallNanos=688100, queueNanos=127400, cpuNanos=0, cpuSamples=1, maxWallNanos=688100, maxQueueNanos=127400, slices=1, maxSliceNanos=688100], STEP=Row[jobs=1, failed=0, discarded=0, wallNanos=1151400, queueNanos=23700, cpuNanos=0, cpuSamples=1, maxWallNanos=1151400, maxQueueNanos=23700, slices=1, maxSliceNanos=1151400], INITIALIZE=Row[jobs=1, failed=0, discarded=0, wallNanos=675200, queueNanos=25000, cpuNanos=0, cpuSamples=1, maxWallNanos=675200, maxQueueNanos=25000, slices=1, maxSliceNanos=675200], ESTIMATE=Row[jobs=30, failed=0, discarded=0, wallNanos=564982100, queueNanos=1662100, cpuNanos=562500000, cpuSamples=30, maxWallNanos=50068200, maxQueueNanos=241300, slices=85, maxSliceNanos=10731800], PLAN=Row[jobs=1, failed=0, discarded=0, wallNanos=138600, queueNanos=638300, cpuNanos=0, cpuSamples=1, maxWallNanos=138600, maxQueueNanos=638300, slices=1, maxSliceNanos=138600]}
```
