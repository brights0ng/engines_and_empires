<!-- Written by ChatGPT. -->
# Static-fluid thermal support and boundary contract, 2026-10-20

The coupled kernel now supports **stationary fluid heat storage and conduction**, closing the earlier numerical gap where fluid occupancy blocked both air and heat. It adds no liquid motion, liquid buoyancy, evaporation, phase changes, or new weather/source coefficients. The live thermal-only path and dry-house calibration remain unchanged.

## Implemented numerical behavior

`ThermalGeometry` has an explicit constructor accepting separate solid and fluid conductance/capacity arrays. It retains existing air states and solid indices, then appends one thermal state per fluid-bearing block. Air, solid and fluid temperatures remain separate in a waterlogged cell. Capacities scale with their respective occupied volumes and the existing `solidCap` storage units. Material inputs are consumed during construction, not retained as mutable caller arrays.

Fluid/air exchange uses the existing film plus half-block material resistance. Fluid/solid and fluid/fluid conduction use the existing two half-block resistances in series. Actual overlapping sixteenth-block patches determine contact across block faces; internal solid/fluid contacts are counted once. All transfers are equal/opposite pairs in the existing implicit thermal operator. Material state never acquires a velocity or pressure degree of freedom; fluid patches remain closed to airflow.

The original constructor remains available as a historical thermal-only control. `unrepresentedFluidCells()` exposes its missing fluid states. **`CoupledAirGrid` rejects such geometry** before allocation/advancement, so a future adapter cannot silently publish fluid-bearing coupled results with omitted thermal storage. The historical characterization test remains intact.

Source power is not applied twice. Existing air-pocket and neighboring-air/per-face routing is preserved. A fully enclosed non-per-face source uses its solid owner, or its fluid owner when there is no solid. A per-face source with no adjacent air still has no air output. Thermostat limits and withheld/source-energy accounting use the existing solver law; the new constructor does not create any heat sources automatically.

One fluid thermal owner per block is a coarse approximation, like the existing block material owner: disconnected fluid patches within a block share temperature, and half-block resistance is not calibrated to each sampled thickness. No water/lava-specific numerical constants are invented. The future world adapter must supply resolved table values. For example, the current insulation resource specifies water conductance 1.5 and capacity 2.0; lib does not import that resource or Minecraft types. Wet-scene gameplay timing and spatial/depth sensitivity remain unmeasured.

## Exterior contract for the initial prototype

The recommended contract retains the boundary behavior used by the measured dry-house fixtures:

| Boundary | Contract |
| --- | --- |
| Represented air/material/fluid interfaces | Shared geometry and conservative internal exchange; fluid and solid exclude normal air flow |
| Known exterior air | Incoming weather temperature and compatible wind/pressure conditions; outgoing heat follows solved advective flux |
| Outer solid/fluid truncation | Insulated; no invented neighboring material or fixed-temperature bath |
| Zero-advection exterior air | No additional implicit diffusive weather reservoir |
| Missing chunks or a known interior passage cut by coverage | Insufficient coverage; the world adapter must withhold a ready result rather than call it outdoors |

Outer insulation is a finite-domain approximation, not a claim that terrain or an ocean ends there. Keep calculation margins, coverage diagnostics and bounded admission. Previous padding/depth evidence applies to its dry fixtures only. Adding a new external thermal reservoir would be a separate measured behavior change. World-side exterior/missing-chunk classification is **not implemented** by this numerical patch.

## Verification and next step

- **140/140 pure-package tests pass**, none skipped. Ten new tests cover analytic air/fluid and body/body backward-Euler exchange in every axis and orientation, waterlogged storage, partial-face ownership, disconnected air pockets, thermostat/source routing, data validation/ownership, dry-operator identity, fluid-driven air buoyancy, and atomic fluid-state rollback on failure. Analytic exchange controls run all five thermal traversal modes.
- Three dry houses each complete 600 s heating plus 80 s cooling at dt 0.5 s: **4,080 accepted steps, no rejection**. All **90 scalar values and 18,000 field values** exactly match the pre-fluid unity response archive. A structural test also verifies identical dry thermal matrices/capacities with either constructor. The prior long settled calibration remains historical evidence; it was not rerun by this patch.
- The required full-project Gradle check remains blocked before startup by wrapper-lock access. Direct Java 21 compilation/JUnit does not verify the complete mod graph, game tests, client, world material mapping, wet-scene initialization, or runtime worker cost.

The numerical fluid omission now has an explicit supported path and an admission guard. Next is the Stage 4 world snapshot adapter: capture actual solid/fluid occupancy, supply separate existing material values, distinguish unknown/interior cuts from genuine exterior, and pass immutable inputs to the bounded coupled worker path. Preserve the opt-in switch, existing budgets, thermal-only fallback, and reported calibration differences. No live mode, source/material retuning, radius default, or consumer integration is enabled here.

Evidence and reproduction: [boundary archive](../benchmarks/air-boundaries-2026-10-20/README.md).

Follow-up: the [world capture checkpoint](local-air-world-capture-results.md) implements isolated geometry/material capture and immutable diagnostic snapshots. Exterior certification and live worker admission remain separate gates.
