<!-- Written by Claude. -->
# Local-air soak protocol

Two 30-minute captures of the automatic local-air runtime, one calm and one under scripted churn, so later changes have a baseline to compare against. This is debug tooling only.

## Test world
- A new plains world. Record the **seed** and the soak origin **P** (where you stand when you run `soak build`).
- Stand at station 0 (P) for the whole run; do not fly off or change dimension. Heat view off.
- Live wind cannot be controlled. Record the weather (clear / rain / thunder, and the time of day) at the start of each capture.

## Commands, in order
```
/meteodyne heat air plan 4 4 32 8
/meteodyne heat air auto 4 131072 1000000 4
/meteodyne heat air soak build
/meteodyne heat air soak start calm
/meteodyne heat benchmark start 1800
```
Wait for `Thermal capture saved` (10 s warmup plus 30 min). Then:
```
/meteodyne heat air soak start busy
/meteodyne heat benchmark start 1800
```
Wait for `Thermal capture saved` again, then:
```
/meteodyne heat air soak stop
```
`/meteodyne heat air soak status` shows the mode, ticks run and action counts at any time.

`soak build` places four stations 48 blocks apart along +X from P (campfire, furnace, a 6x6 wheat field, an oak log with leaves, a line of redstone wire) and force-loads their chunks. `soak start busy` then toggles furnaces every 10 s, campfires every 30 s, one wheat every second, one leaf every 2 s, all wires every half second, and force-loads two chunks 24 chunks east of P in turn every 5 s. `soak stop` unforces every chunk the soak forced.

## Reports
Each capture writes `build/benchmarks/thermal/live-<UTC time>/`: `summary.json` (including `localAirAvailability` per domain and the `soak` counts), `summary.csv`, `jobs.csv` and `local_air_timeline.csv` (one row per local-air owner every second).

Copy the two folders to `docs/benchmarks/air-soak-<date>/calm/` and `docs/benchmarks/air-soak-<date>/busy/`, and add a short note with the seed, P, and the weather at the start of each capture.
