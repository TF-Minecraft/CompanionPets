# CompanionPets

> Companion pet hatching, care, training, and play for TF-Minecraft.

CompanionPets keeps one record per pet: name, sex, personality, needs, bond, tricks, and a
rolled favourite toy. The Minecraft entity is only the body that is currently
in the world. Vanilla wolves, cats, and foxes work with this plugin alone.
MythicMobs and ModelEngine are optional and are loaded by reflection when those
plugins are installed.

## Learnable tricks per pet type

`pets.<id>.tricks` optionally selects which existing tricks a type can learn
and perform, for both vanilla and ModelEngine appearances:

```yaml
# Inside a frog pet definition:
tricks: [come, stay, speak, jump]
```

Available IDs: `sit`, `come`, `stay`, `speak`, `jump`, `spin`, `sleep`, `paw`,
`beg`, plus IDs defined under `custom-tricks`. Names are case-insensitive and
duplicates are ignored. Omitting `tricks` enables all compatible base/custom
tricks; `tricks: []` disables them. Unknown IDs are skipped with a warning.
A malformed list disables that type's tricks, but the pet still loads.

The selection filters learning/learned menus, word suggestions, binding,
training rewards, and spoken/command-driven trick execution. Disabled tricks
retain their saved words and progress so they can be enabled later. Ordinary
care, automatic rest, navigation, and responding to the pet's name are unchanged.
An animation's presence does not grant a trick, and a permitted trick does not
require a custom animation.

Define custom gestures at the top level:

```yaml
custom-tricks:
  salute:
    display-name: "Saludar"
    animation: salute
    fallback-text: "{pet} te saluda."
    duration: 2
  roll:
    display-name: "Rodar"
    animation: roll
```

Custom tricks share the normal word binding, training, progress and rewards.
The animation is an exact model clip name. If it is unavailable, fallback-text
appears above the pet (`{pet}` and `{owner}` are replaced; plain text only).
Without either an available animation or fallback text, the trick is omitted
for that pet. Invalid definitions are skipped individually. Base trick IDs
cannot be redefined. Menus paginate when there are more than nine choices.
`duration` defaults to two seconds and controls text and zero-length animation
poses; moving clips play once at their own length. Definitions may be removed
and restored without losing stored words or progress.

## Belly rub moment

Owner petting with an empty hand can trigger `lie_back` -> `belly_up` -> `get_up`.
All three clips must exist and be enabled. Vanilla pets or incomplete models
keep their normal petting interaction. Configure `moments.belly-up`:

```yaml
moments:
  belly-up:
    enabled: true
    chance: 25
    idle-seconds: 5
    cooldown-seconds: 60
    min-mood: 70
```

The pet must be healthy with no low needs, hunger >= 60, energy >= 40,
health >= 70 and the configured mood. The owner must be within six blocks.
Training, combat, fetching and active play prevent it. Each eligible attempt
uses the cooldown, including unsuccessful rolls. While belly-up, owner
right-clicks scratch its belly and restart the idle timer. Rewards retain
their usual cooldown. Navigation pauses through the transitions, then the
pet resumes its previous order. Damage, water, orders, illness, leaving or
storage interrupt the moment. Setting `enabled: false` disables the experiment.

A zero-length `belly_up` is held by the plugin; an animated one loops.
Transitions retain their final frame until the next stage to avoid snapping.
Operators can look at their pet and run `/companionpets moment belly` to
trigger this moment without the chance roll or cooldown. The pet must still
meet the health and activity requirements and have all three model clips.

For a frog, the existing `animation.frog.croak` can optionally be mapped to
`appearance.animations.speak`. Its tongue animation can be explicitly mapped
to `eat` or `attack` if desired. These are species-specific uses of existing
clips, not additional animation requirements for every pet.

## Interaction items and per-pet overrides

Item IDs use the same notation and optional provider APIs as Archaeo:

| Provider | Example |
| --- | --- |
| Vanilla | `STICK` or `minecraft:stick` |
| MMOItems | `mmoitems:PETS:MEAT_TREAT` (type `PETS`, item ID `MEAT_TREAT`) |
| ItemsAdder | `itemsadder:tfmc:pet_ball` (namespace `tfmc`, item ID `pet_ball`) |

Aliases `mi:TYPE:id`, `ia:namespace:id` and bare `namespace:id` also work.
Quote custom IDs in YAML; one-key maps caused by unquoted colons are recovered
as in Archaeo. Provider IDs are matched case-insensitively and independently of
base material/model data. Vanilla excludes provider-identified items but accepts
renamed/enchanted vanilla items. MMOItems identity uses its `getTypeName`/`getID`
API; custom APIs are optional and loaded by reflection. CompanionPets has no
runtime dependency on Archaeo, Cooking or MCPets. The former dotted selectors
(`v.stick`, `m.pets.meat_treat`, `ia.tfmc:pet_ball`) remain compatible.

The five global categories are under `items`. All species share these defaults:

```yaml
items:
  treats:
    - "mmoitems:PETS:MEAT_TREAT"
    - "mmoitems:PETS:FISH_TREAT"
  foods:
    - {item: "mmoitems:PETS:UNIVERSAL_FEED", hunger: 35}
  medicines:
    - "mmoitems:PETS:GREEN_CONCOCTION"
    - "mmoitems:PETS:RED_CONCOCTION"
  brushes:
    - "mmoitems:PETS:CARING_ITEM"
  toys:
    - STICK
```

Both treats train/reward every species. You may switch accepted treats without
ending the session. Treat rewards consume one and use the training settings;
as with former favourite food, hungry pets may eat a treat for 30 hunger points
and the `care.favorite-food-mood` bonus. Regular food uses each entry's `hunger`
value. Both medicines consume one to treat sickness and apply
`care.medicine-health-bump`; they do not grant MCPets experience. The glove cleans
without being consumed. Toys retain their full metadata through throwing,
fetching, returns and saved carried items.

For a species-specific replacement, use the same fields under `pets.<id>.items`:

```yaml
pets:
  cat:
    entity: CAT
    egg: CAT_SPAWN_EGG
    items:
      treats: ["mmoitems:PETS:FISH_TREAT"]
      foods: [{item: SALMON, hunger: 55}]
      medicines: []
      # brushes and toys are omitted, so they remain global.
```

A present category **replaces the entire corresponding global list**. It never
appends to that list. An omitted category inherits; `[]` disables that category.
`items: {}` inherits everything. Malformed/invalid override entries are logged
and skipped without falling back to the global category. The shipped pet types
have no active overrides; config.yml includes a commented example for each
category. The default custom IDs must be registered in MMOItems; servers using
only vanilla items can replace the global lists with vanilla IDs.

Legacy per-pet `care.foods`, `care.favorite`, `care.medicine` and root `toys`
remain category replacements unless the corresponding new `items` list is
present. The former global `items.brush` is accepted if `items.brushes` is absent.
Food accepts the old `MATERIAL: hunger` map as well as `item`/`hunger` lists.
Finite nonnegative hunger values are required. Dig gifts accept the same item
IDs in `item`/`weight` lists (positive integer weights); the old material map
also works and `dig-loot: []` disables gifts.

Custom IDs reference provider-owned items. Unknown/unavailable items never
substitute unrelated vanilla items. Menus show the provider model/name and all
accepted treats, medicines and brushes. Lookups retry as provider registries
become available. Existing saved vanilla toys and favourite-toy IDs still load.

Eggs remain unique per pet type. Use `mmoitems:PETS:PET_BEAGLE_EGG` or an
ItemsAdder ID alone for custom eggs. The legacy `egg-custom-model-data` only
works with vanilla material selectors; those material/model combinations also
accept provider-created eggs for compatibility. Explicit provider IDs take
priority. Confirmation rechecks the held item before consuming it.

Shelter placement stays global: `items.kennel` selects the consumed held item;
`items.kennel-block` selects the actual vanilla block (BARREL for custom tokens).
This places an ordinary shelter, not ItemsAdder furniture. Plant/block settings
continue to use vanilla materials.

## Pet types and ModelEngine

`src/main/resources/config.yml` defines pet types under `pets`. The key is the
saved type ID and the name shown in menus: `wolf` and `beagle` are distinct pets.
`entity` selects the Minecraft body and AI, so both can use `WOLF`.
`appearance.type` selects `vanilla` (the default) or `modelengine`.
Vanilla pets need no ModelEngine installation and keep their existing behaviour.
`mythic-mob` can supply the body instead of `entity`; CompanionPets should own
the appearance and animations of a body using this integration.

```yaml
pets:
  wolf:
    entity: WOLF
    appearance:
      type: vanilla
    egg: WOLF_SPAWN_EGG
    sex: random
    care:
      foods: {BEEF: 35, COOKED_BEEF: 55}
      favorite: COOKED_BEEF
      medicine: HONEY_BOTTLE
    toys: [STICK, FEATHER]
  beagle:
    entity: WOLF
    egg: WOLF_SPAWN_EGG
    egg-custom-model-data: 12001
    appearance:
      type: modelengine
      model: beagle
      scale: 1.0
      animations:
        idle: idle
        walk: walk
        sit: sit
        death: death
        lie: sleep
        sleep: sleep
        head_tilt: head_tilt
        shake: shake
        paw: {name: paw, speed: 1.0, blend: 0.15}
    sex: random
    care:
      foods: {BEEF: 35, COOKED_BEEF: 55}
      favorite: COOKED_BEEF
      medicine: HONEY_BOTTLE
    toys: [STICK, FEATHER]
```

The normal spawn egg selects `wolf`; an egg of the same material with
CustomModelData `12001` selects `beagle`. The custom egg must be supplied by
your item provider or a command. Each type needs a unique combination of egg
material and optional custom model data. The plugin checks the egg again when
the player confirms the name.

All model clips are optional. Available clips are discovered by their names,
which default to their configuration keys, so a model
using these names only needs `type: modelengine` and `model: beagle`.
Each mapping accepts a clip name or `{name: ..., speed: 1.0, blend: 0.15}`;
`blend` is the transition time in seconds.

| Clips | Trigger and playback |
| --- | --- |
| `idle`, `walk` | Actual displacement, including following, roaming, fetch and social play. Loop while applicable. |
| `sit` | Sitting. Stay remains standing unless the body is actually sitting. |
| `death` | Natural entity death, rendered by ModelEngine's default death handler. |

Optional clips are discovered by name when present in the model, or explicitly
mapped. Set a mapping to `""` to disable an optional clip.

| Extra clips | Trigger and playback |
| --- | --- |
| `lie`, `sleep` | Exhaustion/weakness and sleeping, including sleep caused by care needs. Loop until the pet recovers or wakes. Missing `sleep` falls back to `lie`, then `sit`. |
| `fly`, `hover` | Airborne flying bodies, moving/stationary. Fall back to `walk`/`idle`. |
| `run` | Falls back to `walk` at 1.5 times its configured speed. `run-speed` defaults to 0.22 blocks/tick, with hysteresis. |
| `crouch` | A cat moving while sneaking; falls back to `walk`. |
| `paw` | Giving a paw. Plays once while navigation pauses. |
| `head_tilt` | Brief head-only gesture for an unknown training word or a failed learning attempt, at most once every three seconds. Layers over the current standing/sitting pose. Author only head bones, and use a distinct clip name. Vanilla wolves use their interested state. |
| `shake` | A wolf drying after getting wet. Play once. |
| `pet` | Normal petting reaction. Does not interrupt a belly moment or another gesture. |
| `jump`, `fall`, `swim` | Optional air/water motions. Jump plays once and holds until landing or the fall pose; swim loops. Missing clips use normal movement/idle fallbacks. |
| `beg`, `spin`, `attack`, `hurt`, `eat`, `speak`, `spawn` | Automatically used when present for their corresponding behaviour. Missing clips do not prevent the behaviour. |
| `lie_back`, `belly_up`, `get_up` | The optional belly rub moment described above. |

No clip is required. Missing idle/walk logs a warning about a potentially static
model, but does not reject it. Without their clips, eating/speaking use effects
and sounds, and spinning uses circular movement. No illness clip is needed.
The original `lay` is automatically recognized for `lie` if no mapping overrides
or disables it. Zero-length `head_tilt` is held briefly rather than vanishing.
Head tracking belongs to the model's head bone behaviour, not a look animation.

Without jump/swim, dogs and cats use `idle` in the air and `walk` in water;
jumping and falling remain physical movements. A frog can explicitly
map `jump: animation.frog.jump` and `swim: animation.frog.swim` to retain its
species-specific motions. `beg` remains a learned command using its vanilla
sitting/attention behaviour when no `beg` clip is present.
`pet1`, `pet2`, and `despawn` have no automatic hooks. Map `pet: pet1` to use
an older petting clip. Custom tricks may also refer to arbitrary model clips.

An empty Blockbench clip is an authoring placeholder, not a finished action.
Disable unfinished optional clips with `""` until they are animated.

Gestures finish into the pet's current pose. A new command interrupts the
previous gesture; sleep, lying down, water and falling can also interrupt it.
Sickness continues to use care particles and sounds. All tricks still work
with vanilla pets and their existing visual approximations.

Install ModelEngine 4 and serve its generated resource pack to players. Author
the model around the base entity's feet, with a correctly sized ModelEngine
hitbox and head bone behaviour for looking at players. Keep movement clips
in place: the body supplies translation and jumping, so animation root motion
would move the model away from its hitbox. Include all bones needed for each
full-body pose. The plugin hides the vanilla body only after a model is attached,
scales the model and hitbox together, loops poses and forces gestures to play
once. It does not generate model assets or a resource pack.

An unavailable model leaves the pet visible as its vanilla
body and logs the reason. Failed attachments retry every 30 seconds; vanilla
servers remain usable without ModelEngine. Models are reapplied on chunk loads,
removed when pets are stored/released, and detached when CompanionPets stops.
Old top-level `model` is accepted with a migration warning; old `animations`
and `trick-animations` mappings must move to `appearance.animations`.

The integration uses the [ModelEngine 4 animation API](https://ticxo.github.io/Model-Engine-4.0-JavaDocs/com/ticxo/modelengine/api/animation/handler/AnimationHandler.html)
and was checked against the ModelEngine 4.1.1 API available in the workspace.

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
Use `/companionpets reload` to apply edits to `config.yml` without restarting
the server. The command checks the YAML and keeps the current settings if a
saved pet type would disappear. Active ModelEngine appearances are reapplied;
open pet menus and pending training prompts are closed. Changing a pet's
underlying entity type takes effect when that pet is summoned again. Existing
`config.yml` files are not overwritten when the plugin updates, so add new
sections such as `moments.belly-up` manually when you want to tune them.
Use `/companionpets owner fake` while looking at a pet to give it a fictional,
offline owner for solo encounter testing. Use `/companionpets owner self` to
take it back. An online player name or UUID also works. Command options and
learned trick words are available with Tab completion.

Needs stay still in the shelter and while the owner is offline. Outside, they
fall at the normal rate when the owner is nearby and at the away rate when the
owner is online but far.

## Saved data

The plugin stores pets and shelter ownership in `plugins/CompanionPets/pets.yml`.
It saves immediately after important changes, every five minutes, and when the
plugin stops. Each save writes a temporary file and replaces the main file;
`pets.yml.bak` holds the previous valid save. If the main file is damaged or
missing while a backup exists, the plugin disables itself and leaves both files
untouched. To recover, stop the server, preserve both files, and copy the backup
to `pets.yml`. Reconcile ownership changes and deleted pets before starting:
the older snapshot may revert a transfer or restore a pet that was released or
died after that save. Backups are never activated automatically.

Terminal deletions are first appended and flushed to `pet-deletions.log`, before
release or neglect removes the body. The log filters deleted IDs even if the main
save remains stale; keep it when restoring a backup. If the deletion log cannot
be written, releases are refused and body recovery and saving stop. An actual
entity death is irreversible, so it remains pending operator reconciliation.

While the plugin runs, `pets-recovery-required` protects against interruption or
complete disk write failure. It is removed only after a successful final save.
If it remains after a crash or failed shutdown, the plugin refuses to start.
Stop the server, preserve all persistence files, reconcile ownership and actual
deaths in `pets.yml` (respecting `pet-deletions.log`), then remove the marker.
A damaged deletion log also requires manual repair before startup. Back up all
these files together before manually editing saved data.

Pets left outside retain their last position and identity across restarts. When
their chunk's entities have loaded, the plugin reconnects to the tagged body or
recreates it at the saved position if it is missing. Missing or unloaded bodies
do not delete pet records; care pauses until the body is available. Actual deaths
still remove the pet normally. Calling an outside pet from its shelter also
loads its saved chunk and attempts to recover its body.

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

## Verification

Run `mvn -Pcoverage clean verify` with Java 21 to run the unit tests and generate
the JaCoCo report at `target/site/jacoco/index.html`. CI retains test and coverage
reports for review.
