# Loopback ownership fixture

Build after the parent plugin's verified install:

```sh
tfmc-mvn -o -B -f integration-tests/ownership/pom.xml clean package
```

Use a fresh isolated Paper 1.21.10 directory, TLibs 2.6.0 and CompanionPets, loopback IP `127.0.0.1`, port `25583`, offline mode and a disposable world. This probe refuses other addresses/ports. Connect the synthetic offline account `S6PetProbe`; no real character files are required. The fixture registers a fake identity provider, creates its own pet/store and verifies actual native following under A, switches the same connected account to B, checks outgoing prompts/training, native-selector exclusion, swimming safety and body reattachment. It writes `plugins/CompanionPetsOwnershipProbe/result.json`, logs each check and shuts the isolated server down. Never install it on Main or Dev.

With `native-combat: true`, the probe sets real native owner combat memories and verifies both owner-assist goals select A's target and reject same-account B; native self-defense remains registered. The probe also starts an actual training navigation goal under A and verifies it cannot remain active under B. `OWNERSHIP_PROOF PASS` and `result.json.passed=true` are both required; a successful Maven compilation alone is not runtime evidence.

`LegacyApiCheck.java` loads CompanionPets classes and an actual older TLibs JAR in an isolated classloader, with the normal Paper API dependencies on the parent classpath:

```sh
javac -cp "$PAPER_API_CLASSPATH" -d /tmp/cp-legacy-check integration-tests/ownership/LegacyApiCheck.java
java -cp "/tmp/cp-legacy-check:$PAPER_API_CLASSPATH" LegacyApiCheck target/classes /path/to/tlibs-2.3.0.jar
```

All three character/estate capability checks must report false without linkage failure. Use test dependencies as the API classpath; do not include an old TLibs JAR on the parent classpath.
