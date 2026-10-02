# CompanionPets

> Companion pet hatching, care, training, and play for TF-Minecraft.

CompanionPets keeps one record per pet: name, sex, personality, needs, bond, tricks, and a
rolled favourite toy. The Minecraft entity is only the body that is currently
in the world. Vanilla wolves, cats and foxes need no model plugin. The default interaction lists reference MMOItems pack items; vanilla-only servers can replace those lists with vanilla items.
MythicMobs and ModelEngine are optional and are loaded by reflection when those
plugins are installed.

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

## Learnable tricks per pet type

`pets.<id>.tricks` optionally selects which existing tricks a type can learn
and perform, for both vanilla and ModelEngine appearances:

```yaml
# Inside a frog pet definition:
tricks: [follow, stay, speak, jump]
```

Available IDs: `sit`, `follow`, `come`, `stay`, `speak`, `jump`, `lay`, `paw`,
`beg`, plus IDs defined under `custom-tricks`. Names are case-insensitive and
duplicates are ignored. Omitting `tricks` enables all compatible base/custom
tricks. `tricks: []` disables additional tricks; configured default tricks are
always enabled. Unknown IDs are skipped with a warning. A malformed list
disables additional tricks, but the pet still loads.

Initial learning is configured globally and optionally replaced per type:

```yaml
training:
  default-tricks: [follow]
pets:
  wolf:
    entity: WOLF
    egg: WOLF_SPAWN_EGG
    # Omit default-tricks to inherit the global list.
    # default-tricks: [follow, sit, lay]
    # default-tricks: [] # No initial learning for this type.
```

These tricks start at 100% and get their lowercase ID as a command word, shown
in the Tricks menu. This also grants missing defaults to existing pets during
startup or reload. Removing a default does not delete learned progress or words.
Existing bindings are preserved when a default word is already used for another
trick. Unknown/removed IDs and malformed default lists reject the configuration.
Custom trick IDs are supported. Follow is continuous following. Come gets the
pet up, walks to the owner's current position and restores its earlier Sit,
Stay or Lay posture on arrival. From Follow it resumes following. Repeating
Come during the trip preserves the original posture; a new Follow/Sit/Stay/Lay
order cancels the pending return. Timeout/disconnection restores the posture at
the pet's current position; normal reload/shutdown also restores pending holds.
Come is learned independently; add it to default-tricks if it should be known
from birth. Saved COME learning remains separate from FOLLOW. The literal
word come that an earlier dev build assigned to FOLLOW is restored to COME,
preserving its progress; other Follow words retain their bindings.

`lay` replaces the former Rest trick (`sleep` ID) and uses the `sleep` animation.
Legacy `sleep` entries in configuration and saved words/progress are accepted as
`lay`; subsequent saves use `LAY`. Existing spoken words remain bound, and the
highest progress is retained if both old and new IDs are present. Pets created with testpet
learn the word `lay`.

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

For the current frog, the existing `croak` can optionally be mapped to
`appearance.animations.speak`. Its tongue animation can be explicitly mapped
to `eat` or `attack` if desired. These are species-specific uses of existing
clips, not additional animation requirements for every pet.

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
    # Inherits the global interaction lists.
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
    # Inherits the global interaction lists.
```

The plain vanilla spawn egg selects `wolf`; an egg of the same material with
CustomModelData `12001` selects `beagle`. The custom egg must be supplied by
your item provider or a command. For this legacy example each type needs a unique combination of egg
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
| `shake` | Begins when the native wolf shake clock starts, alongside its vanilla sound. Play once. |
| `pet` | Normal petting reaction. Does not interrupt a belly moment or another gesture. |
| `jump`, `fall`, `swim` | Optional air/water motions. Jump plays once and holds until landing or the fall pose; swim loops. Missing clips use normal movement/idle fallbacks. |
| `beg`, `attack`, `hurt`, `eat`, `speak`, `spawn` | Automatically used when present for their corresponding behaviour. Missing clips do not prevent the behaviour. |
| `lie_back`, `belly_up`, `get_up` | The optional belly rub moment described above. |

No clip is required. Missing idle/walk logs a warning about a potentially static
model, but does not reject it. Without their clips, eating/speaking use effects
and sounds. No illness clip is needed. Spin is removed from tricks, training,
completion and saved legacy words/progress.
The original `lay` is automatically recognized for `lie` if no mapping overrides
or disables it. If neither `lie` nor `lay` exists, an available, enabled `sleep`
clip supplies the lying posture, including its configured speed and blend.
Zero-length `head_tilt` is held briefly rather than vanishing.
Head tracking belongs to the model's head bone behaviour, not a look animation.

Without jump/swim, dogs and cats use `idle` in the air and `walk` in water;
jumping and falling remain physical movements. The current frog can
use its `jump` and `swim` clips without overrides to retain its
species-specific motions. `beg` remains a learned command using its vanilla
sitting/attention behaviour when no `beg` clip is present.
`pet1`, `pet2`, and `despawn` have no automatic hooks. Map `pet: pet1` to use
an older petting clip. Custom tricks may also refer to arbitrary model clips.

The definitive `beagle`, `chihuahua`, `corgi`, `golden`, `catblack`, `catfunny`,
`catorange` and `fox` models use `idle`, `walk`, `death`, `sit`, `sleep`, `paw`,
`head_tilt`, `lie_back`, `belly_up` and `get_up`. They need no `lay`, `jump` or
`swim` clip. Dogs and the fox also have `shake`; the fox's `pet2` can be enabled
with `pet: pet2`. `paw` plays once even when the authored clip is marked as a loop.
The frog retains `idle`, `walk`, `jump`, `swim`, `lay`, `croak` and `tongue`.
Map `speak: croak` and optionally `eat: tongue`; its sleeping pose falls back to
`lay`. Its zero-length `animation.common.look_at_target` is not an action hook.

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
  pet lies down on its own when exhausted, and the learned Lay trick puts it to rest sooner. Food past a
  full stomach makes it feel worse. A hit makes it yelp and flinch. Food, a
  brush, and medicine act immediately. Anyone nearby can feed, clean, and heal. Play
  and tricks belong to the owner.
- Sneak and right-click with a barrel to place a shelter. Right-click it to
  open the list. Click a pet to open its sheet, the same
  one as sneak-right-clicking it in the world. From the sheet you bring it
  out, call it, store it, let it go, or look up the tricks it has learned.
- Teach a trick by holding any accepted treat, looking at the pet, and saying
  the whole chat line. Use the learned word while looking at the pet, or say
  `<name> <word>` / `<word> <name>` without aiming. Names and command words may
  contain spaces. Only your own loaded pets in the same world and within
  `orders.hearing-radius` (12 blocks) hear named orders. Duplicate names require
  aiming to choose the intended pet. Jump makes a sitting pet stand first.
- Sit, Stay (standing) and Lay (sleeping) remain in place until following is
  resumed, and survive restarts. Lay stays asleep even at full energy. Automatic
  exhaustion sleep can still end when recovered. Sitting and sleeping recover
  energy. Needs and illness can prevent a pet from moving even after release.
- Follow is one trick, learned at 100% by default and shown with its command
  words on the Tricks page. Say `follow` while looking or `<name> follow`.
  The old Come ID migrates to Follow, preserving
  words and progress. Existing custom word bindings are never overwritten.
- Right-click the air with a listed toy to throw it. The summoned pet fetches
  that item and drops it in front of the owner.
- Several pets can be outside at once. Look at one and say its learned word,
  or say its name and word nearby.
- Say a following pet's exact name in chat to call it close. It then waits quietly
  for `roaming.name-attention-seconds` (10 seconds), counted after arrival. Calling
  a pet that is already sitting/staying/sleeping does not cancel that order.
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

Operators can use `/companionpets testpet <type> [name]` to spawn any configured
pet with all compatible tricks learned at 100%, including its custom tricks.
`type` is the key under `pets` in the active config; tab completion lists these
keys. For example, `/companionpets testpet beagle Toby` or
`/companionpets testpet frog Rana` (with `tongue` and `croak` configured).
Only enabled tricks are included; custom tricks need a usable animation or text fallback.
The spawn message lists the bound words; look at the pet and say a word or use
the pet name and word, such as `Rana tongue`.
These are ordinary saved pets: active-pet limits and care needs still apply.
Look at a pet and use `/companionpets moment dig` to show a find.
Use `/companionpets reload` to apply edits to `config.yml` without restarting
the server. The command checks the YAML and keeps the current settings if a
saved pet type would disappear. Active ModelEngine appearances are reapplied;
open pet menus and pending training prompts are closed. Changing a pet's
underlying entity type takes effect when that pet is summoned again. Existing
`config.yml` files are not overwritten when the plugin updates, so add new
sections such as `moments.belly-up` manually when you want to tune them.
Staff can browse players and pets with /companionpets list and locate them by player and pet name with /companionpets find.

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
Sitting, lying from weakness/exhaustion and sleeping all recover energy at the
`care.sleep-minutes-to-full` rate (default: 12.5 points per minute), without idle
energy loss. Away recovery uses the same away rate; frozen presence pauses it.
Standing still with Stay does not count as rest. Only sleeping reduces hunger
decay through `care.sleeping-hunger-multiplier`. Automatic exhaustion sleep ends
at full energy; an explicit Lay order continues until another order changes it.


## Verification

Run `mvn -Pcoverage clean verify` with Java 21 to run tests and generate the
JaCoCo report in `target/site/jacoco/`. The final staff commands and inventory
navigation are introduced in the next PR in this stack.
