<!-- Written by ChatGPT. -->
# Local-air dependency invalidation verification

Recorded 2026-10-21. See [the contract and limitations](../../airflow/local-air-dependency-results.md).

- `local-air-unit-results.json`: extracted JUnit suite counts from the full project check, 75 tests, zero failures/errors/skips. Six are new dependency tests. Extraction metadata and attribution were added by ChatGPT.
- `initial-full-summary.log`: Gradle task outcomes and Minecraft test summaries extracted by ChatGPT from `build/air-dependencies-full.log`. The full invocation exited 1; retain its failures in the record.
- The initial aggregate passed 262/262. Standalone passes: Sable Fixes 2, Frontier 18, Paradyne Lib 3, Seismic 20, Spoilage 34, Sparse Ores 13 both bare and with Create. Coreless failed its async pipeline initial-publication wait. Meteodyne failed the same wait and the coupled fixture's existing cold-fallback energy assertion.
- The pipeline test subsequently received a cooperative 1 ms worker yield per test tick, without changing the 400-tick timeout or assertions. The coupled assertion received detailed failure values, without changing its condition. Production code was unchanged for the retry.
- `retry-summary.log`: extracted from `build/air-dependencies-retry.log`; `:meteodyne:runGameTestServer :coreless:runGameTestServer --continue --console=plain` passed 95/95 and 211/211, exit 0. All suites passed across the initial run and this retry. The earlier coupled fallback failure did not recur; its cause remains unproven.

No paced performance measurement was made. These headless tests verify correctness and asynchronous publication, not player-visible TPS or rebuild latency.
