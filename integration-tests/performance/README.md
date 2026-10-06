# Vanilla-navigation branch performance review

Reviewed 2026-10-06 against `main` at `efc9d4579e2c51d2330d849d271085ab4ceecd22`.
This is a source audit and a controlled count of plugin navigation requests,
not a measurement of total server CPU, memory, or ModelEngine rendering costs.

## Measured reduction

The same MockBukkit fixture and follow-step probe were compiled against both
checkouts. A healthy pet eight blocks from its online owner received 240
`FOLLOW` decisions, with timestamps 250 ms apart. The body stayed at its initial
position so every decision represented an opportunity to update the route.
The probe invoked `PetTicker.stepMode` directly and counted its pathfinder
`moveTo` submissions; other behaviors and native AI were excluded.

| Follow decision replay | main | This branch |
| --- | ---: | ---: |
| Decisions | 240 | 240 |
| Plugin path submissions | 240 | 0 |

Normal follow is now a no-op in that switch, and the scheduler does not call
the switch for normal follow. The custom `FollowNavigationGoal` has also been
removed. Its old 250 ms gate permitted up to four route updates per second per
following pet, including formation scans and sometimes a fallback path query.
Those plugin decisions are eliminated, as are custom idle exploration and
plugin follow teleport decisions. Native wolf/cat AI still calculates paths
and performs catch-up teleportation; its cost has not disappeared.

The probe uses the existing `PetInteractionTest` fixture. Its source is
`integration-tests/performance/FollowWorkProbe.java`; output is retained locally in
`.scratch/performance-probe/result.log`, with the baseline output in the
separate `companionpets-performance-main` worktree. The committed regression
`normalFollowingLeavesNativeNavigationUntouchedAndDoesNotTeleport` verifies
that ordinary following does not submit plugin paths. The Paper integration
helper verifies actual native following separately.

To reproduce in each checkout with Java 21, compile the tests and build a test
classpath, then compile the same probe source against that checkout. The probe
is deliberately outside the helper Maven source tree because it uses the
plugin's test fixture. Example PowerShell, with `$probeSource` pointing to the
absolute path of the committed probe in this branch:

```powershell
mvn -q test-compile dependency:build-classpath '-Dmdep.outputFile=target/performance-classpath.txt' '-Dmdep.includeScope=test'
$probeClasspath = "$PWD/target/classes;$PWD/target/test-classes;" + (Get-Content target/performance-classpath.txt -Raw).Trim()
New-Item -ItemType Directory -Force target/performance-probe | Out-Null
javac -proc:none -cp $probeClasspath -d target/performance-probe $probeSource
java -cp "target/performance-probe;$probeClasspath" net.tfminecraft.companionpets.runtime.FollowWorkProbe
```

## Work that remains

| Work | Frequency at 20 TPS | Scaling concern |
| --- | --- | --- |
| `PetVisualTicker` | 20/s | Iterates every saved record, samples every loaded modeled body, checks model animation/tail state |
| `PetTicker.lookBars` | 20/s per online player | Up to one block ray trace and one entity ray trace each time, plus component creation/status delivery |
| Care, movement state, toy/greeting/social discovery | 2/s | Several passes over saved records; nearby queries and repeated owner/body lookups |
| Ambient voice scheduling, added in this branch | 2/s | Another saved-record pass; actual sounds have their own long intervals/cooldowns |
| Active custom interaction goals | Native AI goal ticks | Temporary fetch/training/play/social control still costs work while active; registered inactive goals retain activation checks |
| `PetStore.save` | Every 300 s, plus explicit saves | Builds the full YAML snapshot and performs replacement/backup I/O synchronously |

`PetStore.all()` already caches its immutable collection. The remaining issue
is repeatedly traversing it, rather than repeatedly copying it.
`PetStore.of()` and the fallback `PetStore.byEntity()` still scan all records.
`PetRuntime.byEntity()` first reads the pet identifier from entity metadata,
so real pet lookup usually takes the fast route. Ordinary nearby entities
without that identifier use the linear fallback, including during social
discovery and player look queries. Crowded areas magnify this cost.

The tail/animation ticker also constructs samples and computes greeting mood
every frame, even when no greeting or toy excitement needs that result.
Social discovery queries neighbors twice per second per eligible pet and
checks remembered pairs. Many pets together create much more work than the
same number spread across different areas.

Training head tilt now loops through ModelEngine instead of being restarted
every frame. The controller keeps it until focus ends, yields to tricks, and
resumes afterwards. It adds no scheduler task per pet. Idle pets with no
training session skip the trainer/distance checks through a constant-time
session lookup.

## Recommended next changes, in order

1. Run look/status discovery at 5 Hz and cache the last target/status, refreshing
   unchanged text periodically. This reduces that scheduler's ray-trace
   opportunities by 75%, not overall server CPU by 75%. The status can react
   within 200 ms; chat/trick interactions remain event driven.
2. Index loaded active pets, owners, and entity identifiers. Animation and
   interaction loops should visit the active set instead of every saved pet.
   Update indices on spawn/store/death/unload/reload and keep persistence
   independent. Eliminate the linear lookup for unrelated nearby entities.
3. Separate animation-pose detection from gestures needing per-tick work.
   Detect locomotion/pose at 10 Hz, let ModelEngine continue its own animation
   playback, and retain 20 Hz only for an active procedural tail/head gesture
   or shake effect. Compute mood only when it contributes to a gesture.
   This halves pose-sampling opportunities; it needs a client smoothness check.
4. Discover new social encounters at 1 Hz, staggered across pets, while keeping
   active interactions responsive. Reuse neighbor results briefly and process
   each pair once. Keep greeting onset within approximately one second.
5. Separate slow care and ambient scheduling from immediate interactions.
   Care already integrates elapsed time, so it can use a slower/staggered
   cadence; training sessions and active play need not slow down with it.
   Offline/stored records can be handled independently of visible bodies.
6. Consider ordered background persistence only after profiling saves. Capture
   a coherent snapshot on the server thread, then serialize/write that
   snapshot through one ordered worker. Preserve durable deletion logs,
   recovery guards, backup semantics, and a final flush at shutdown. Bukkit
   entities and mutable live records must remain on the server thread.

## What cannot yet be concluded

The removed normal navigation control is a substantial architectural saving,
but there is no representative before/after CPU profile from TF dev. These
results cannot establish a percentage improvement in milliseconds per tick,
a safe maximum pet count, or that the plugin is necessarily too expensive.
Models, nearby mob density, online players, and simultaneous interactions
affect the result. The remaining work above justifies another optimization
pass before considering the plugin well optimized at larger scales.

A useful real-server comparison would use the same worlds, players, pet
counts/models and durations for both revisions: normal following, stationary
owners, held toys, fetching, and crowded social encounters. Compare profiler
samples for CompanionPets, native pathfinding, and ModelEngine together, plus
tick-time distributions and allocation/GC pressure. An empty server's TPS
or this MockBukkit request count cannot substitute for that measurement.
