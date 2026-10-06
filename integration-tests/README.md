# CompanionPets Paper integration checks

This dev-only helper runs once, 12 seconds after enabling, then disables itself.
It uses its own `plugins/CompanionPetsSmoke/` persistence files and namespace.
It never edits the live CompanionPets records. Temporary entities are removed
when it finishes, including failed runs.

Build the plugin and helper with Java 21:

```sh
mvn -Pcoverage clean install "-DskipTests=false" "-Dmaven.test.skip=false"
mvn -f integration-tests/pom.xml clean package
```

If the plugin Maven version differs from `main-SNAPSHOT`, supply
`-Dcompanionpets.version=<version>` to the second command. Copy the helper JAR
from `integration-tests/target/` to the dev server alongside the matching
CompanionPets JAR and its configured provider plugins, then restart dev.
The server process must be able to create or write `plugins/CompanionPetsSmoke/`;
prepare this isolated directory with the server account's ownership if the
plugins directory is not writable.

Look for `COMPANIONPETS_INTEGRATION PASS checks=...` or
`COMPANIONPETS_INTEGRATION FAIL` in the log. Checks use the active config: every
type's body, egg identity, model attachment and available mapped/custom clips;
native owner-follow goals and movement without plugin follow commands,
training attention over nine seconds of native ticks, optional head-tilt persistence
and release back to native follow,
audio-profile muting, native AI pause/resume and frozen needs; adoption and body restoration without
losing learning; persistent postures and default learned Follow;
modeled wolves starting shake with the native shake clock; duplicate removal,
owner/staff Pet House, confirmed
release and heal; deletion keeps the vanilla body hidden and clears the
ModelEngine registration and modeled owner; toy landing,
offline return with original data, and rejection of stale projectiles. The final
validation checks configured provider IDs and required animations.

The helper creates isolated records and writes its own staff audit and deletion
journal. Keep those with the log as test evidence. Remove the helper JAR after
running; each installed helper would run again at the next server restart.
The harness is compiled in CI but requires the actual Paper server and providers
to execute. It does not establish correct rendering in a Minecraft client.
