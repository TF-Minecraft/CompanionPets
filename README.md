# CompanionPets

> Companion pet hatching, care, training, and play for TF-Minecraft.

CompanionPets gives players a companion that lives beyond a single entity. Each
pet hatches from an egg with its own name, sex, personality, and favourite toy,
and keeps its needs, bond, and learned tricks between summons. Vanilla wolves,
cats, and foxes need no model plugin; ModelEngine, MythicMobs, MMOItems, and
ItemsAdder are optional.

## Features

- **Hatching and Pet Houses** — hatch a named pet from an egg, then store, summon,
  call, or release it from a placed Pet House.
- **Care and needs** — hunger, energy, cleanliness, mood, and health change with
  where the owner is. Anyone nearby can feed, clean, and treat a pet.
- **Training by voice** — teach tricks with treats and spoken words, then give
  orders by looking at a pet or saying its name.
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
- **Pet recovery** — keep pets and their learning across restarts, including pets
  left outside their Pet House.

## Documentation

The editable [Chihuahua and Yorkshire ModelEngine blueprints](models/modelengine/companions/)
include the corrected textures and Yorkshire eyebrow geometry. Their installation
instructions are in the model directory.

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/CompanionPets/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Tests

With Java 21 and Maven installed, run:

```sh
mvn clean verify
```

Tests use JUnit, MockBukkit, and Mockito. Surefire reports go to
`target/surefire-reports/`; JaCoCo HTML, XML, and CSV reports go to
`target/site/jacoco/`. Verification requires 100% production line coverage without
class or package exclusions, plus the execution data and XML report. CI uploads
test and coverage reports.

These tests simulate Bukkit and provider integrations. Real Paper behaviour and
client rendering need separate checks; see the
[testing guide](https://github.com/TF-Minecraft/Docs/blob/main/projects/CompanionPets/docs/testing.md).

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and pre-existing
material retain their own licenses.
