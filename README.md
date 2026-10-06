# CompanionPets

> Companion pet hatching, care, training, and play for TF-Minecraft.

CompanionPets gives players a companion that lives beyond a single entity. Each
pet hatches from an egg with its own name, sex, personality, and favourite toy,
and keeps its needs, bond, and learned tricks between summons. Vanilla wolves
and cats need no model plugin; ModelEngine, MythicMobs, MMOItems, and
ItemsAdder are optional.

## Features

- **Hatching and Pet Houses** — hatch a named pet from an egg, then store, summon,
  call, or release it from a placed Pet House.
- **Care and needs** — hunger, energy, cleanliness, mood, and health change with
  where the owner is. Anyone nearby can feed, clean, and treat a pet.
- **Training by voice** — teach tricks with treats and spoken words, then give
  orders by looking at a pet or saying its name. During training, pets stay still
  and watch the trainer, with an optional head_tilt gesture when training starts;
  Come and swimming to safety can still move them.
- **Persistent orders** — Sit, Stay, and Lay hold in place and survive
  restarts, while Follow and Come bring a pet back to its owner.
- **Play and social life** — pets anticipate held toys, lose interest when play
  stalls, fetch together, greet returning owners and recognise carers and friends.
  They sniff and chase each other, and can be calmed when they bark.
- **Configurable species** — per-type bodies, eggs, interaction items, learnable
  tricks, optional behaviours, and custom tricks, with optional ModelEngine models
  and animations. Resting pets look around with bounded head movement.
- **Staff tools** — browse, locate, and create pets for any player, give eggs,
  and audit every staff intervention.
- **Durable saves** — atomic saves, a deletion journal, and recovery for pets left
  outside across restarts.

Normal movement uses the wolf or cat's native owner-follow, wandering and
catch-up teleportation. The plugin takes control for explicit interactions,
games, training, greetings and rest, then releases movement to vanilla AI.
It no longer computes follow formations or idle exploration routes.

Only `entity: WOLF` and `entity: CAT` are supported in this version. Other saved
types keep their records, learning and needs; loaded bodies remain paused until
their type is supported again. Removing a type does not delete its pets. Normal
pace and follow distance are vanilla; `presence.follow-teleport-blocks` and the
old `roam` behavior no longer control movement. The remaining `roaming` settings
only affect interaction timing. Model animations still track actual movement.

Per-type `sounds` profiles configure voices separately from movement and
behaviors, including resource-pack keys, volume, pitch and minimum intervals.
Omitting `sounds` preserves native audio; adding a profile silences inherited
body audio and supplies the configured voice. `sounds: false` silences voices.
See the examples in [config.yml](src/main/resources/config.yml). Native combat
is disabled by default and can be enabled with `native-combat: true`.

This removes plugin follow/idle path requests; vanilla AI, model updates and
interaction checks still use server resources. No CPU saving percentage has
been measured.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/CompanionPets/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests

With Java 21 and Maven installed, run:

```sh
mvn -Pcoverage clean install -DskipTests=false -Dmaven.test.skip=false
```

Unit and MockBukkit workflow tests run with JUnit. The `coverage` profile writes
a JaCoCo report to `target/site/jacoco/`. The dev-only Paper server check is
described in [integration-tests/README.md](integration-tests/README.md).

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and pre-existing
material retain their own licenses.
