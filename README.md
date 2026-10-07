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
- **Durable saves** — atomic saves, a deletion journal, and recovery for pets left
  outside across restarts.

## Documentation

[Project documentation](https://github.com/TF-Minecraft/Docs/blob/main/projects/CompanionPets/README.md)

Technical documentation is maintained in [TF-Minecraft/Docs](https://github.com/TF-Minecraft/Docs).

## Pet configuration

Keep each `pets` key stable: it is the type ID in saved pets. Species templates
and model changes do not rename that ID or require a save migration. The built-in
`dog` and `cat` species supply WOLF and CAT bodies, their native voices, all
compatible tricks, and their current default behaviors. Omit repeated lists so
new default behaviors are inherited when the plugin is updated. The following
syntax example illustrates optional overrides; it is not the TF Dev configuration.

```yaml
custom-tricks:
  tongue: {display-name: Tongue, animation: tongue}
  croak: {display-name: Croak, animation: croak}

species:
  frog:
    entity: CAT
    voice: frog
    behaviors: [greeting, greeting-approach, greeting-jumps, toy-anticipation, toy-jumps, fetch]
    tricks: [follow, come, stay, jump, lay, tongue, croak]
    animations: {lie: lay, sleep: lay}

pets:
  beagle:
    species: dog
    model: beagle
    egg: mmoitems:PETS:PET_BEAGLE_EGG
  chihuahua:
    species: dog
    model: chihuahua
    egg: mmoitems:PETS:PET_CHIHUAHUA_EGG
    voice: {pitch: 1.3}
    behaviors: {remove: [dig-gifts]}
  frog:
    species: frog
    model: frog
    egg: mmoitems:PETS:PET_FROG_EGG
```

Species can define the same shared fields as a pet: body, voice, behaviors,
tricks, default learned tricks, animation mappings, appearance settings,
interaction items, sex selection and native combat. Definitions under
`species.dog` or `species.cat` customize those built-ins. A custom species needs
an `entity` (or a MythicMob body with an explicit supported base). Each pet can
override any inherited field. Mappings merge by key; ordinary lists replace
the corresponding list. Templates have one layer; a species does not inherit
another species. Unknown species are warned about and skipped.

The body supplies navigation and native AI/mechanics; `behaviors` chooses the
plugin's optional actions independently of WOLF/CAT, and `voice` chooses its
sound identity. Only **WOLF and CAT** supply supported native following. A model of a fox, frog
or another animal must use one of those bodies and select its own voice. Saved
pets with unsupported or missing type definitions remain stored. For MythicMobs,
declare `entity: WOLF` or `CAT` and retain its native FollowOwner goal. Active
bodies are replaced when reattached on reload/startup; stored pets use the new
body on their next summon, retaining their identity and saved care.

### Behaviors and tricks

Both `behaviors` and `tricks` accept these forms in species and pet definitions:

| Form | Result |
| --- | --- |
| Omitted | Inherit the species, or entity defaults for a legacy pet |
| `{add: [id], remove: [id]}` | Edit the inherited set; removal wins if an ID occurs in both |
| `[id, id]` | Replace the entire inherited set (legacy behavior) |
| `[]` | Clear the set |

IDs are case-insensitive. Unknown IDs are warned about and skipped. Invalid
adjustment lists are ignored; an invalid scalar disables the set as before.
Species adjustments apply first, then pet adjustments. Global and per-pet
`default-tricks` remain lists: they grant fully learned tricks and ensure those
tricks stay allowed, even if removed from `tricks`. Set `default-tricks: []` to
prevent that grant. Removing definitions never erases stored learning or words.

Built-in tricks: `follow`, `come`, `sit`, `stay`, `lay`, `paw`, `speak`, `jump`.
Legacy `sleep` resolves to `lay`. Lay holds an awake lying posture; automatic
rest handles sleeping. `come` resumes the previous sit/lay/stay posture on arrival.
`custom-tricks` entries need an animation, fallback text, or both. Plain fallback
text supports `{pet}` and `{owner}`. `duration` defaults to two seconds for text
and zero-length poses; normal clips use their own length. A custom trick without
a usable animation or fallback text is unavailable for that pet.

Optional behavior IDs:

- Greeting: `greeting`, `greeting-approach`, `greeting-circles`, `greeting-jumps`,
  `greeting-tail-wag`, `greeting-meows`.
- Play: `toy-anticipation`, `toy-vocalizing`, `toy-jumps`, `toy-tail-wag`, `fetch`, `cat-play`.
- Social: `social-greeting`, `social-tail-wag`, `social-jumps`, `social-vocalizing`,
  `social-sniff`, `social-chase`, `social-protest`, `recognize-carers`, `pet-friendships`.
- Moments: `affection`, `affection-jumps`, `belly-rub`, `mischief`, `dig-gifts`.

`roam` is an accepted obsolete ID: idle movement now comes from native AI.
Automatic care and responding to the pet's name remain independent of these sets.
`toy-jumps` enables both waiting hops and favorite-toy reaction hops on either
supported body. `toy-tail-wag` controls the tail gesture and the eager whole-body
shuffle in front of a held toy; removing it disables both. `cat-play` controls
the gentler favorite-toy side steps (and cat-style fetch investigation), even on
a WOLF body. Default dog/cat templates retain their existing movement patterns.
Native wolf water shakes, anger handling, cat crouching/sitting and navigation
still depend on the physical body.

### Models and animations

`model: beagle` selects ModelEngine with scale 1. For advanced settings use
`appearance: {type: modelengine, model: beagle, scale: 0.8}`. Explicit appearance
fields override the shortcut; `appearance.type: vanilla` disables a model.
Top-level `animations` is the shorthand for `appearance.animations`; explicit
appearance mappings win for the same action. Only the configured model's actual
blueprint clips are used. ModelEngine 4 is optional: unavailable models fall back
to their vanilla body and report the reason.

```yaml
animations:
  lie: lay
  pet: pet2
  paw: {name: give_paw, speed: 1.2, blend: 0.15}
  shake: ""  # Disable this action even if a clip exists.
```

Default action names match the clips: `idle`, `walk`, `crouch`, `sit`, `lie`,
`sleep`, `jump`, `swim`, `head_tilt`, `shake`, `paw`, `pet`, `fly`, `hover`,
`attack`, `hurt`, `death`, `eat`, `speak`, `spawn`, `lie_back`, `belly_up`, `get_up`.
Missing clips are optional. Idle/walk are recommended to avoid a static model.
The default `lie` also recognizes `lay`, then an available `sleep`. Explicitly
disabled or remapped `lie` does not use that alias. Sleep falls back to lie/sit,
lie to sit, crouch/swim/fly to walk, and jump/hover to idle. ModelEngine drives
idle, walk, jump, fly, hover and death from body movement; the plugin adds postures
and gestures. `run`, `fall`, `run-speed`, `sick`, `pet1`, `pet2` and `despawn` are
not action hooks; `pet: pet2` can select a model clip with that name.

The plugin automatically disables `belly-rub` if any mapped `lie_back`, `belly_up`
or `get_up` clip is absent or disabled. Modeled pets without a `tail`, `tail_…`
or `tail1…` bone disable all three tail-wag behaviors. It keeps jumps, fetching
and other actions that can still work through movement, sounds or fallbacks.
Unavailable models are checked again on the next capability query, so initial
blueprint registration needs no CompanionPets reload. Once a model is available,
its capabilities are cached until `/companionpets reload`; reload CompanionPets
after regenerating blueprints that were already loaded.

### Voices

Omit `voice` and `sounds` to retain native audio and interaction sounds from the
base `entity`. `voice: frog` selects a profile and silences the base body's audio.
Enhanced presets: `wolf`, `cat`, `fox`, `frog`, `parrot`; `none` starts empty.
Any other vanilla entity ID, such as `voice: rabbit` or `voice: pig`, generates a
voice from its registered Minecraft sounds. Ambient, greeting, toy, happy,
happy-quiet, social, sad and protest use `minecraft:entity.<entity>.ambient`;
hurt/death use `.hurt`/`.death`, and eat uses `minecraft:entity.generic.eat`.
Each automatic sound is checked against the server registry. Missing sounds fall
back to a valid ambient sound, or the event is disabled with a warning. An unknown
entity or an entity without any valid entity sounds warns and stays silent.
The enhanced presets retain their purr/whine/hiss and other specialized cues.
`voice: false` silences the pet. A mapping can change just the inherited global pitch:

```yaml
voice:
  preset: fox
  pitch: 1.3
  ambient-interval-seconds: 25
  greeting: {sounds: [ENTITY_FOX_AMBIENT, 'tfmc:pet.hello'], volume: 0.6, pitch: 1.1}
  toy: false
```

`pitch` multiplies every event's pitch (default 1, accepted range 0.1–2); playback
is clamped to 0.1–2. A pitch-only mapping inherits the species voice or base
entity preset. Explicit profiles replace native audio and default to an ambient
interval of 25 seconds, randomized by ±20%; zero disables idle sounds.

Events: `ambient`, `happy`, `happy-quiet`, `sad`, `hurt`, `death`, `greeting`,
`toy`, `social`, `protest`, `eat`. Each accepts a sound name, a list, `false`/`[]`,
or `{sounds: [...], volume: 0.8, pitch: 1.0, min-interval-seconds: 0}`. Volume
ranges from 0–4, and intervals from 0–3600 seconds. Use Bukkit sound names or
namespaced resource-pack keys. Minecraft keys must exist in the server registry;
explicit custom resource-pack namespaces may be client-only and keep their
existing syntax. Omitted events inherit the selected or generated voice.
The existing `sounds` syntax supports the same settings and remains valid;
explicit `sounds` fields override `voice` fields in the same definition.

### Eggs, items and care

Each pet needs a unique egg selector. Selectors accept vanilla `STICK` or
`minecraft:stick`, `mmoitems:PETS:PET_BEAGLE_EGG` (`mi:TYPE:id`), and
`itemsadder:namespace:item` (`ia:namespace:item` or `namespace:item`). Legacy
`v.stick`, `m.pets.meat_treat`, and `ia.namespace:item` still load. Provider IDs
must exist; custom items never match vanilla items solely by material. Legacy
`egg-custom-model-data` is a nonnegative integer for vanilla eggs only.
Custom provider identity takes priority over material/model matches.

Global `items` provides foods, treats, medicines, brushes and toys. Species and
pet overrides replace each category independently; omitted categories inherit,
and `[]` disables one. Foods use `{item: <selector>, hunger: 45}`. All accepted
treats work for training, and switching treats keeps a session active. Hungry
pets can eat treats without entering training. Legacy `care.foods`,
`care.favorite`, `care.medicine` and root `toys` remain category replacements.
`sex: random` is the default; `sex: choose` prompts the player when naming.
`native-combat: false` prevents native targeting/attacks while retaining movement.

Pet House placement is global: set `items.kennel` to an item and `kennel-block`
to a block (BARREL by default). Simple ItemsAdder furniture also needs matching
`kennel-furniture: namespace:item`; complex furniture is unsupported.

`config.yml` includes the numeric care, play, training, greeting, social and moment
settings. Care values are starting points: TF Dev can use slower decay for roleplay.
`sleep-minutes-to-full` controls energy recovery when sitting, lying or sleeping;
only sleep slows hunger. Natural health recovery requires food, cleanliness and
energy at least 25 (or resting); feeding/brushing also heal 25% of points restored.
Held toys lose interest after the configured attention interval; moving, switching
toys or putting them away renews it. Favorites extend attention without a speed bonus.
`moments.dig-loot` accepts item/weight entries (or the legacy material/weight map);
weights are positive chances, and `[]` disables loot.

### Inspecting the result

Use `/companionpets inspect beagle` from chat or the console. It shows the resolved
species, body, egg, voice (preset/generated/none) and event pitches, configured and active behaviors,
reasons for automatic disabling, allowed/default tricks, unavailable custom tricks,
and model clips found, missing or explicitly disabled. It uses the same capability
resolution as gameplay and requires `companionpets.admin.list`. Tab completes
configured type IDs. `/companionpets reload` refreshes configuration, active models
and cached capabilities.

## Tests

With Java 21 and Maven installed, run:

```sh
mvn -Pcoverage clean install -DskipTests=false -Dmaven.test.skip=false
```

Unit and MockBukkit workflow tests run with JUnit. The `coverage` profile writes
a JaCoCo report to `target/site/jacoco/`. The dev-only Paper server check is
described in [integration-tests/README.md](integration-tests/README.md).

To also verify the prepared private TF Dev config against its original snapshot
and model sources, add `-Dcompanionpets.tfdev-config=<absolute-path-to-config.yml>`.
That optional test checks all fourteen IDs, egg selectors, shared settings, voice
presets and model clips without copying private assets into this repository.

## License

Copyright (c) 2026 TF-Minecraft contributors.

TF-Minecraft-authored material in this repository is licensed under the
[Artistic License 2.0](LICENSE). Third-party dependencies and pre-existing
material retain their own licenses.
