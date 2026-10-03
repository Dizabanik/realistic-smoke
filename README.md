# Realistic Smoke (Fabric 1.21.11)

A server-authoritative smoke and ventilation mod inspired by Valheim-style building constraints. It runs entirely on the server, so players connecting to a dedicated server do not need a client-side mod installed.

## Gameplay

- Lit furnaces, smokers, blast furnaces, campfires, and soul campfires produce smoke.
- Smoke rises, gathers beneath ceilings, and fills unventilated rooms.
- Chimneys and open roofs vent smoke outside. Glass roofs seal smoke even though they let skylight through.
- Open doors, trapdoors, and fence gates act as ventilation openings. Fluids block smoke.
- Inhaling dense smoke builds exposure based on concentration at the player's eye level. High exposure brings nausea, blindness, and potentially lethal damage.
- Creative mode, spectator mode, and dead players do not build exposure.
- Exposure tracks across dimensions and dissipates in clean air. Respawning, dying, disconnecting, or stopping the server resets tracked exposure.
- Optional vanilla particles visualize plumes, exhaust, and ceiling smoke. The underlying physics and exposure checks run whether particles are enabled or disabled.

## Simulation and realism

The world is partitioned into `2 x 2 x 2` block cells. Each cell contains up to eight air pockets grouped by six-neighbor connectivity, determined by an eight-bit passability mask. The engine precomputes all 256 masks and their internal pocket graphs, allocating field entries only for pockets that currently hold smoke.

Smoke moves across cell boundaries only through aligned, passable block pairs that connect directly to the source pocket. Flow scales with the open cross-section: a single open pair provides one-fourth the conductance of a completely open face. This design accommodates single-block chimneys and keeps solid walls from leaking smoke across diagonal cell corners.

Vertical movement simulates thermal buoyancy. Lateral diffusion flows along concentration gradients between neighboring pockets. Downward displacement also follows concentration gradients once smoke density exceeds `pressureDownThreshold`.

The simulation tracks absolute smoke mass in each pocket, while exposure and particle thresholds use concentration (mass divided by pocket volume). If competing transfers request more mass than a pocket holds, the transfers scale down proportionally to conserve mass. Incoming fractional transfers accumulate across steps before negligible pockets are pruned.

Exhaust relies on physical clearance above the roof. The engine queries columns downward from the world height limit, skipping open blocks and decorative obstacles until it finds a solid barrier. Pockets directly above that barrier vent to the sky according to their open column area. Smoke trapped under a ceiling must find an opening to reach an exhaust pocket.

When block placement or destruction alters cell geometry, existing smoke redistributes among the remaining open blocks in the pocket. Splits and merges conserve total mass, and placing solid blocks into a smoke pocket displaces that smoke into adjacent open space.

### Approximation limits

The simulation focuses on survival building mechanics, using discrete approximations:

- Air within a single connected pocket inside a two-block cell mixes instantly. Buoyancy and inter-cell diffusion update at discrete tick intervals.
- Block passability uses bounding box collision states. Non-empty shapes block smoke unless they expose an active `OPEN` property (such as opened doors or trapdoors). Partial shapes like stairs, slabs, fence gaps, and open door leaves are evaluated at whole-block granularity.
- The simulation omits ambient wind vectors, dynamic temperature layers, oxygen depletion, and fire choking.
- Natural dissipation, outdoor venting, density caps, pocket pruning, and chunk unloads intentionally discard smoke. Mass conservation governs cell-to-cell transport, while sinks like venting and natural dissipation discard smoke intentionally.
- Active smoke fields do not persist through chunk unloads or server restarts.

## Performance and safety

Smoke runs on a numeric grid without spawning entities or custom blocks. The simulation step runs every 10 ticks by default (twice per second at standard 20 TPS).

- Smoke sources register through Fabric block-entity lifecycle callbacks. On chunk load, fallback scans check stored block-entity coordinates instead of scanning the full chunk volume.
- A chunk-to-source index avoids iterating the full dimensional registry when chunks unload.
- Chunk unloads and cache trimmings batch once per world tick. Intermediate calculation buffers clear after every step.
- Simulation routines, exposure lookups, roof checks, and particle dispatch use non-loading chunk queries to avoid triggering unwanted chunk loads.
- Block geometry caches in a bounded primitive LRU table (defaulting to 24,000 cells per dimension). Exhaust column results use a separate cache sized at four times that limit.
- A server-side `WorldChunk` mixin invalidates cached geometry and roof profiles when blocks change. Time-based expiration and periodic clearing clean up lingering cache entries.
- Hot loops use fastutil primitive iterators to avoid per-entry heap allocations.
- Active smoke fields hold up to 12,000 pockets per dimension by default (configured via `maxActiveCellsPerWorld`).
- Temporary working buffers limit scratch pockets to 5x the active cap and candidate transfers to 25x the active cap during a step.
- Exposure checks sample eye-level concentration once per second (every 20 ticks). Smoke damage accrues independently and executes at most once every 20 ticks to align with vanilla damage immunity cooldowns.

### Overload policy

When new smoke pockets appear, a bounded priority heap admits them based on the highest individual source concentration feeding each pocket. Ties rotate predictably by simulation epoch. Pockets that already exist continue to accept smoke, and multiple sources feeding the same newly admitted pocket combine their output. The admission check evaluates peak individual source strength for the pocket.

During diffusion steps, the system reserves space for existing source pockets before admitting new destinations. If destination buffers fill up, rejected transfers remain in their originating pockets. Once transfers settle and low-density smoke prunes, only the densest pockets up to the active cap carry over into the next step. When memory limits are reached, the mod drops lower-density smoke to keep resource usage bounded.

The field and cache limits restrict spatial smoke tracking, but the source registry and player counts scale with the active world state.

## Particles

The particle engine reads precomputed ceiling and exhaust profiles generated during the simulation pass, checking chunk status and block passability prior to spawning. It verifies block types and active burn states so extinguished or broken sources stop emitting immediately.

- The default budget allows 64 spawn events per visual update, running every 10 ticks (up to 128 events per second at 20 TPS).
- Up to 16 events per update are reserved for sources and up to 12 for vents, with remaining slots allocated to ceiling smoke.
- Plume and vent selections cycle on an epoch counter to avoid favoring specific hash table entries. Category priority shifts when source or exhaust allocations fill the frame.
- Ceiling smoke selection prioritizes dense pockets close to players, adding slight temporal jitter to prevent repetitive patterns. Spatial player indexing avoids evaluating every candidate against every online player.
- Particle limits apply to server spawn events rather than individual network packets; one event reaches all qualified players in range. Individual player checks observe both the configured view distance and the vanilla 32-block client particle threshold.
- Exhaust smoke calculations operate normally down through negative Y levels.

Ceiling effects spawn vanilla `LARGE_SMOKE` particles just below obstructed roof blocks, using wide horizontal dispersion and tight vertical drift to give the appearance of trapped smoke on unmodified clients.

## Configuration

The configuration file is created at `config/realistic-smoke.json` on first run. Valid entries persist, and any missing keys are populated with defaults.

| Field                           | Default | Meaning                                                                                                                        |
| ------------------------------- | ------: | ------------------------------------------------------------------------------------------------------------------------------ |
| `simulationIntervalTicks`       |      10 | Field update period. Higher values are cheaper but slow movement and change emission/dissipation per second.                   |
| `exposureIntervalTicks`         |      20 | Eye-density sampling period; exposure integrates elapsed server ticks.                                                         |
| `maxActiveCellsPerWorld`        |   12000 | Maximum completed-field smoke pockets per dimension.                                                                           |
| `maxGeometryCacheCellsPerWorld` |   24000 | Independent geometry-cache limit; column-cache limit is four times this.                                                       |
| `minDensity`                    |   0.035 | Prune pockets below this concentration after accumulation.                                                                     |
| `maxDensity`                    |    12.0 | Maximum completed-field concentration.                                                                                         |
| `furnaceEmission`               |    0.55 | Mass emitted per update by furnaces and blast furnaces.                                                                        |
| `smokerEmission`                |    0.85 | Mass emitted per update by smokers.                                                                                            |
| `campfireEmission`              |     1.0 | Mass emitted per update by either campfire variant.                                                                            |
| `baseDissipation`               |   0.018 | Fraction of mass lost naturally per update.                                                                                    |
| `outdoorRetention`              |    0.10 | Fraction retained for a fully exposed four-column pocket; partial exposure scales the loss.                                    |
| `risingFraction`                |    0.56 | Requested upward fraction, scaled by opening area.                                                                             |
| `lateralFractionWhileRising`    |    0.10 | Total lateral coefficient while an upward opening exists.                                                                      |
| `lateralFractionWhenBlocked`    |    0.48 | Total lateral coefficient when upward flow is blocked.                                                                         |
| `pressureDownFraction`          |    0.04 | Downward gradient coefficient above the pressure threshold.                                                                    |
| `pressureDownThreshold`         |     4.0 | Concentration at which downward transport begins.                                                                              |
| `passabilityCacheSteps`         |       4 | Geometry fallback lifetime in simulation steps; block changes invalidate immediately. Zero still allows reuse within one step. |
| `passabilityCacheClearSteps`    |     128 | Periodic cache clearing/trimming interval.                                                                                     |
| `particles`                     |    true | Enable cosmetic particles.                                                                                                     |
| `particleIntervalTicks`         |      10 | Visual update period.                                                                                                          |
| `particleBudgetPerWorld`        |      64 | Shared spawn-event cap per dimension per visual update.                                                                        |
| `sourceParticleBudget`          |      16 | Maximum source events within the shared budget.                                                                                |
| `ventParticleBudget`            |      12 | Maximum exhaust events within the shared budget.                                                                               |
| `particleMinDensity`            |    0.20 | Minimum concentration for ceiling/exhaust candidates.                                                                          |
| `particleViewDistance`          |    48.0 | Configured proximity filter, also subject to vanilla's normal delivery range.                                                  |
| `safeDensity`                   |    0.75 | Eye concentration at or below which exposure recovers.                                                                         |
| `exposureGainPerSecond`         |    0.48 | Gain per second per concentration unit above `safeDensity`.                                                                    |
| `exposureRecoveryPerSecond`     |    0.90 | Recovery per second in safe air.                                                                                               |
| `nauseaExposure`                |     4.0 | Nausea threshold.                                                                                                              |
| `severeExposure`                |     8.0 | Blindness threshold.                                                                                                           |
| `lethalExposure`                |    14.0 | Damage threshold.                                                                                                              |
| `baseDamagePerSecond`           |     1.0 | Damage rate at the lethal threshold, in health points per second.                                                              |
| `maxDamagePerSecond`            |     4.0 | Maximum sustained smoke damage rate.                                                                                           |

The mod validates numeric inputs on startup, replacing non-finite values with defaults. If the file is unreadable or malformed, the game logs a warning and uses default values in memory without overwriting the broken file on disk. Config writes serialize to a temporary file before replacing the target via an atomic filesystem move.

Status effect durations cover the sampling period with an added buffer to prevent visual flicker between ticks. Unapplied damage calls retry across a short grace window, resetting if exposure drops below the lethal threshold. Smoke damage uses vanilla magic damage, respecting standard potion effects and protective enchantments.

## Build and tests

Target dependencies:

- Minecraft `1.21.11`
- Java `21` bytecode
- Yarn `1.21.11+build.6`
- Fabric Loader compile target `0.19.3` (minimum `>=0.18.1`)
- Fabric API `0.141.6+1.21.11`
- Fabric Loom `1.14.10`
- Gradle `9.2+` (use the checked-in wrapper)

From the project root:

```sh
./gradlew build
./gradlew regressionTests
```

On Windows, run `gradlew.bat`. Add `--offline` when building with pre-cached dependencies. The compiled, remapped mod jar is generated in `build/libs/`. The `build`, `check`, and `test` tasks run the standalone regression suites without requiring third-party testing frameworks.

Regression tests verify all 256 mask permutations, face alignment, gradient transport, mass conservation across randomized transfers, source admission heaps, particle budgeting, configuration serialization, and exposure accumulation. Isolated mock-world tests also exercise `SmokeManager` logic directly for block-state updates, glass versus open roofs, negative-height venting, cache limits, and chunk unloads.

The regression fixtures run in a standalone environment and are excluded from the distribution jar. They validate mathematical and structural logic without launching the game engine; verifying mixin injection, live event dispatch, client visuals, and server tick performance requires testing within Minecraft.

## Extending sources

`RealisticSmokeMod.isSmokeSource(...)` defines which block entities to track, `isSmokeSourceBlock(...)` handles chunk-load fallback detection and validation, and `SmokeManager.sourceEmission(...)` sets the emission rate per update. Furnace checks include furnaces, smokers, and blast furnaces, while campfire checks cover both standard and soul campfires.

The mod does not poll plain fire, candles, or lava across the world. When adding non-block-entity smoke sources, use block placement and destruction events or a dedicated registry instead of searching whole chunks.
