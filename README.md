# CompanionPets

> Companion pet hatching, care, training, and play for TF-Minecraft.

CompanionPets keeps one record per pet: name, sex, personality, needs, bond, tricks, and a
rolled favourite toy. The Minecraft entity is only the body that is currently
in the world. Vanilla wolves, cats, and foxes work with this plugin alone.
MythicMobs and ModelEngine are optional and are loaded by reflection when those
plugins are installed.

## What players do

- Use a configured egg, name the pet in chat, and confirm. The egg is spent
  only after the name is confirmed and there is room in the outside quota.
- Sneak and right-click the pet with an empty hand for its info screen. A
  plain right-click checks on the pet: it tells you what is wrong, or you pet
  it when it is fine. If the owner is far away or offline, it whines that it
  misses them. Pets only sit through the trained Sit trick. A worn-out
  pet lies down on its own when exhausted, and a learned rest trick puts it to rest sooner. Food past a
  full stomach makes it feel worse. A hit makes it yelp and flinch. Food, a
  brush, and medicine act immediately. Anyone nearby can feed, clean, and heal. Play
  and tricks belong to the owner.
- Sneak and right-click with a barrel to place a shelter. Right-click it to
  open the list. Click a pet to open its sheet, the same
  one as sneak-right-clicking it in the world. From the sheet you bring it
  out, call it, store it, let it go, or look up the tricks it has learned.
- Teach a trick by holding the favourite food, looking at the pet, and saying
  the whole chat line. A learned word works later only as that exact line.
  Test dogs also understand `follow` as an alias for their learned `come` trick.
  Jump and Spin make a sitting pet stand first; Spin walks a small circle.
- Right-click the air with a listed toy to throw it. The summoned pet fetches
  that item and drops it in front of the owner.
- Several pets can be outside at once. Look at one and say its learned word,
  or use `/companionpets order <word>` while looking at it, to address that pet.
- Say a pet's exact name in chat to call it close for a few seconds of attention.
  When its owner stands still, a following pet explores nearby and alternates
  between looking at its owner, nearby players, and other pets.
- Pets occasionally dig up a gift for their owner. The possible items and their
  weights are in `moments.dig-loot` in `config.yml`. Random moments and the
  operator preview commands require the pet to be awake and following.
- Nearby pets approach to sniff and play chase. Two territorial pets may bark
  repeatedly at each other, but the plugin never makes pets attack each other.
  Right-click a barking pet three times with an empty hand to calm it. Each
  vanilla species keeps its own hunting and combat behavior. A vanilla animal
  targeting a player can also be calmed with repeated right-clicks.

Operators can use `/companionpets testdog <name>` to spawn a dog with every
trick learned. Look at a pet and use `/companionpets personality
friendly|playful|shy|territorial|grumpy` to set its personality, or
`/companionpets moment dig` to show a find. With another pet nearby, use
`/companionpets social sniff|chase|bark` to preview encounters.
Use `/companionpets owner fake` while looking at a pet to give it a fictional,
offline owner for solo encounter testing. Use `/companionpets owner self` to
take it back. An online player name or UUID also works. Command options and
learned trick words are available with Tab completion.

Needs stay still in the shelter and while the owner is offline. Outside, they
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
