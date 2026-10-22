<!-- Written by ChatGPT. -->
# Static-fluid boundary evidence

Numerical coverage is in `ThermalFluidTest`; the ten tests include multiple axes, orientations and all five traversal modes. Analytic fixtures use explicit synthetic material values and compare against two-capacity backward-Euler solutions, without external reservoirs. They do not claim water/lava gameplay calibration. Existing material-boundary/zero-flux exterior tests remain unchanged.

| File | Content |
| --- | --- |
| `tests.log` | 140 passing pure-package JUnit tests |
| `gradle-startup.log` | Blocked required full-project Gradle attempt |
| `dry-0.json`, `dry-1.json`, `dry-2.json` | Sealed/window/missing-wall dry regression runs |
| `summary.json` | Exact scalar/field comparison against the pre-fluid unity response archive |
| `summarize.py` | Standard-library validation/derivation script |

Dry fixtures retain their original AirGridTest-derived layout (original fixture authorship unmarked), 75-unit unlimited virtual-air source, response 1, calm 20 C exterior, padding 6/ground depth 3, 600 s heating plus 80 s cooling, dt 0.5 s. Root reports include JVM/settings metadata. No new performance qualification is made.

Compile all pure `heat.air` main/test sources with Java 21 and run the JUnit package selector. This environment uses cached JUnit/Gson jars; the full-project command stops before Gradle startup because the wrapper ZIP lock is inaccessible. Direct checks do not replace the full required build/game tests.

Reproduce each dry case with `:paradyne-lib:coupledAirBenchmark`, mode `qualification-case`, qualification step `0.5`, and case `0,6,6,3`, `1,6,6,3`, or `2,6,6,3`, writing a distinct report. Direct main positional arguments are:

```text
docs/benchmarks/air-boundaries-2026-10-20/dry-0.json qualification-case COMPACT_CACHED true CACHED_TRACE long 0.5 0,6,6,3
```

Repeat with opening 1/2 and matching output names. Then run `python -B docs/benchmarks/air-boundaries-2026-10-20/summarize.py`. See the [boundary results](../../airflow/local-air-boundary-results.md) for the explicit fluid constructor, admission guard, approximation limits, and world-adapter handoff.
