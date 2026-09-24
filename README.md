# CompanionPets

> Companion pet hatching, care, training, and play for TF-Minecraft.

CompanionPets keeps one record per pet: name, sex, needs, bond, tricks, and a
rolled favourite toy. The Minecraft entity is only the body that is currently
in the world. Vanilla wolves, cats, and foxes work with this plugin alone.
MythicMobs and ModelEngine are optional and are loaded by reflection when those
plugins are installed.

## What players do

- Use a configured egg, name the pet in chat, and confirm. The egg is spent
  only after the name is confirmed and there is room in the outside quota.
- Sneak and right-click the pet with an empty hand for its info screen. A
  plain right-click checks on the pet: it tells you what is wrong, or you pet
  it when it is fine. Pets only sit through the trained Sit trick. A worn-out
  pet lies down on its own and recovers energy while it sleeps. Food, a
  brush, and medicine act immediately. Anyone nearby can feed, clean, and heal. Play,
  tricks, and the whistle belong to the owner.
- Sneak and right-click with the kennel item to place a kennel. The kennel and
  the whistle open the same list: take a pet out, call one, store one, or
  rename it.
- Teach a trick by holding the favourite food, looking at the pet, and saying
  the whole chat line. A learned word works later only as that exact line.
- Right-click the air with a listed toy to throw it. The summoned pet fetches
  that item and drops it in front of the owner.

Needs stay still in the kennel and while the owner is offline. Outside, they
fall at the normal rate when the owner is nearby and at the away rate when the
owner is online but far.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/CompanionPets/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Build

Java 21 and Maven:

```sh
mvn clean verify -DskipTests=false -Dmaven.test.skip=false
```

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and pre-existing
material retain their own licenses.
