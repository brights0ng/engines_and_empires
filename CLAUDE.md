# Engines and Empires: working rules

NeoForge 1.21.1, Java 21, ModDevGradle 2.0.147, Gradle 9.2.1. Bright (brights0ng) owns design decisions; do not make them. If the plan is silent or wrong, stop at a green commit and say so in the PR.

## Split in progress
The single mod is being split into a family. Read `docs/split/mod-split-plan.md` first, then `docs/split/mod-split-manifest.csv` (every Java file -> target mod) and `docs/split/mod-split-seams.csv`.

Mods (id -> package): paradyne_lib -> dev.brights0ng.paradynelib; sable_fixes -> ...sablefixes; sparse_ores -> ...sparseores; seismic_surveyance -> ...seismicsurveyance; meteodyne -> ...meteodyne; frontier -> ...frontier; simple_spoilage -> ...simplespoilage; engines_and_empires (core) -> dev.brights0ng.enginesandempires.

## Import rules (compile-time graph; must stay acyclic)
lib <- sparse_ores <- seismic_surveyance <- (gated package frontier.seismic only) frontier
lib <- meteodyne;  lib <- simple_spoilage;  everything <- core;  sable_fixes imports nothing of ours.
Never: lib importing a feature mod; Sparse Ores or Seismic importing Frontier; Frontier importing Seismic/Sparse Ores outside `frontier.seismic`; a feature mod importing core.
Placement rule: code that makes our mechanism work or look right stays with the mechanism; code that makes someone else's mod follow ours, or fills in pack-specific content, goes to core.
Optional outside mods (Sable, Cold Sweat, MineColonies, Serene Seasons, Iris, AppleSkin, Diet, Simulated, Aeronautics, Offroad, Diesel Generators): only in `<mod>.compat.<othermod>` or a `sable` sub-package, reached after `ModList.isLoaded`; never in a signature or field of an always-loaded class. Create is a HARD dependency of Seismic and core only.

## Never touch
`pack/`, anything for the Modrinth profile, `tools/profiling`, `run/`. Do not delete tests to make things pass. Do not weaken assertions.

## Commands
Full check (baseline 964 unit tests with 2 skipped, 202/202 game tests): `./gradlew compileJava compileTestJava test runGameTestServer`
Cold compile ~9 min, warm ~5 min, game tests ~3 min on 2 cores / 7 GB. Run in the background and poll; do not run two Gradle builds at once.
Client runs and shaders cannot be tested in the cloud; say so rather than guess.

## Conventions
`git mv` for moves (keep history). One logical change per commit. Commit messages say what and why. Lang keys, ids and resource paths change only in the step that says so.
