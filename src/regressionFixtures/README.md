# Isolated smoke regression fixtures

From the project root:

```sh
./gradlew regressionFixtureTests
./gradlew runSmokeManagerFixtureTests
```

Add `--offline` when dependencies are cached. The root `build.gradle` applies
`regression-fixtures.gradle`; do not pass it again as an init script. The full
`regressionTests`, `test`, `check`, and `build` workflows include these checks.

## Isolation

These fixtures are **not** a normal source set and are never packaged into the
mod jar. A separate `JavaCompile` task compiles these production classes:

- `SmokeConfig`
- `SmokeExposureTracker`
- `SmokeManager`
- `SmokeCellGeometry`
- `SmokeTransport`
- `SmokeSourceAdmission`

It compiles them alongside explicit fake Fabric/Minecraft classes, a fake mod
logger/source classifier, and a no-op particle renderer. The classpath provides
real Gson and fastutil from the project's dependencies, without including the
main output directory. The runtime puts fixture classes first so no real game
classes or loader initialization are substituted for the fakes.

The actual particle renderer has its own separate standalone regression suite
using the mapped Minecraft classpath: `runSmokeParticleRendererTest`.

## Coverage

`RegressionFixtureTests` exercises production config and exposure code with a
controllable shared server clock, recorded status effects, and accepted/rejected
damage calls:

- Tiny exposure gains, recovery, eye-only sampling, elapsed ticks, dimension
  transfers, duplicate same-tick callbacks, and counter wraparound.
- Damage cadence for every sampling interval from 5 through 100 ticks, attempt
  spacing, bounded rejected budgets, and recovery clearing.
- Lifecycle reset methods, creative/spectator cleanup, and effect durations.
- Missing-file/missing-field defaults; non-finite values for every floating-point
  field; malformed-file preservation; replacement and temporary-file cleanup.

`SmokeManagerFixtureTests` exercises production field logic against fake worlds
with signed block packing, controllable heightmaps/passability/fluids, and
unloaded-read failures:

- Mass conservation and small incoming contributions.
- Disconnected pockets, topology splits/merges, and opening-area conductance.
- Physical glass roofs versus passable decorations, negative-Y exhaust, and
  connected eye-position sampling.
- Source revalidation and facing-based exhaust.
- Geometry cache bounds, donor reservation under candidate saturation, strongest
  field selection, source admission, and batched unload cleanup.

Tests use explicit checks, not Java `assert`, so `-ea` is not required. Reflection
is confined to the harness to seed/read private state without exposing production
test APIs. Runs use temporary config directories under
`build/regressionFixtures/work`; no real Fabric config is read or changed.

## Limits

The fake world does not validate actual Fabric event dispatch, mixin application,
vanilla damage immunity/armor/effect ticking, client particles, world generation,
or real server performance. Those require live Minecraft validation.

File tests exercise the current filesystem's replacement path, but do not
simulate power failure or force the atomic-move fallback. The harness's no-op
renderer cannot establish visual quality or real network fan-out.
