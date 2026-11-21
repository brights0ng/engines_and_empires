<!-- Written by Claude. -->
# Local air (retired)

Retired in GreedyFluids M7f f3 (2026-11-16): the local-air domains (`meteodyne/.../heat/world/LocalAir*`) and Paradyne Lib's coupled thermal-flow solver (`paradynelib/heat/air`: `CoupledAirGrid`, `FlowGrid`, `ThermalTransport` and the classes only they used) were deleted when GreedyFluids airflow became the default heat engine (`[heat] engine = AIRFLOW`; `LEGACY` keeps the legacy heat regions).

These docs are kept as a record, unchanged apart from relative links rewritten for this folder. The code, commands and Gradle tasks they name are gone; read them at `c8bc765` (feat/airflow-f), the last commit before the deletion. Saves from before f3 may hold a `local_air` chunk compound; it is ignored and not written back.

- Start with [`local-air-model-plan.md`](local-air-model-plan.md) (it superseded [`airflow-prototype.md`](airflow-prototype.md)); the `local-air-*-results.md` files are its stage reports.
- [`benchmarks/`](benchmarks/) holds the multi-fork JSON and summaries those reports cite.
- The legacy heat regions' harness and response control stay live in [`../../airflow/`](../../airflow/).
