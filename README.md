# PolaroidTotems

[![build](https://img.shields.io/github/actions/workflow/status/PolaroidStudio/PolaroidTotems/build.yml?branch=main&style=flat-square&label=build)](https://github.com/PolaroidStudio/PolaroidTotems/actions/workflows/build.yml) [![release](https://img.shields.io/github/v/release/PolaroidStudio/PolaroidTotems?style=flat-square)](https://github.com/PolaroidStudio/PolaroidTotems/releases) ![Paper 1.21+ and 26.x](https://img.shields.io/badge/Paper-1.21%2B%20%E2%80%A2%2026.x-555555?style=flat-square) ![Java 21 and 25](https://img.shields.io/badge/Java-21%20%E2%80%A2%2025-555555?style=flat-square) [![License: PolyForm Noncommercial 1.0.0](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-555555?style=flat-square)](LICENSE)

Custom Totems of Undying for Paper. Three things vanilla will not do:

1. **Per-type stack sizes.** A totem type can stack to 16, or 4, or stay at 1 — set per type, written
   through the real `minecraft:max_stack_size` data component, not a visual trick.
2. **Activation from anywhere in the inventory.** Not just the two hands.
3. **Several totem types with different effects**, each defined in `totems.yml`.

There is no database and no per-player state. A totem's identity travels inside the item's own
PersistentDataContainer, so it survives being dropped, chested, traded and restarted. An unclean
shutdown loses nothing because there is nothing held in memory to lose.

---

## Table of contents

- [Which jar to download](#which-jar-to-download)
- [Requirements](#requirements)
- [Installation](#installation)
- [Files on disk](#files-on-disk)
- [Configuration reference — `totems.yml`](#configuration-reference--totemsyml)
- [The reserved `vanilla` entry](#the-reserved-vanilla-entry)
- [Appearance: `item-model`, `custom-model-data` and Nexo](#appearance-item-model-custom-model-data-and-nexo)
- [Configuration reference — `config.yml`](#configuration-reference--configyml)
- [How activation actually works](#how-activation-actually-works)
- [Why normalization exists](#why-normalization-exists)
- [Commands and permissions](#commands-and-permissions)
- [Building from source](#building-from-source)
- [License](#license)

---

## Which jar to download

The build ships **two jars**. Take exactly one.

| Jar | Server line | `api-version` | Bytecode |
|---|---|---|---|
| `PolaroidTotems-<version>-mc1.21.11.jar` | the 1.21.x line | `1.21` | Java 21 |
| `PolaroidTotems-<version>-mc26.2.jar` | the calendar-versioned 26.x line | `26.2` | Java 25 |

### Why two jars rather than one

Minecraft's `1.x` line ended at **1.21.11** and was replaced by calendar versioning — 26.1, 26.2,
and onwards. The two eras need different `api-version` declarations, and `api-version` is read by
the server out of `paper-plugin.yml` **before any plugin code runs**, so it cannot be decided at
runtime by inspecting the server.

Declaring an old `api-version` on a 26.x server is not harmless: it routes the plugin through
Paper's legacy plugin path, with material and name remapping, which is where old-api-version plugins
misbehave.

The second reason is the compiler. `paper-api` 26.2 is itself compiled to Java 25 (class file major
version 69), and `javac --release 21` refuses to read class files newer than 21 — it fails outright
rather than warning. So the 26.x jar has to be compiled at release 25. That costs nothing in reach:
a 26.x server already requires a Java 25 runtime.

Everything else is shared. **Every API call this plugin makes was verified byte-identical between
`paper-api` 1.21.11 and `paper-api` 26.2** — same signatures, same call-site descriptors. That is
why one source tree serves both eras, why there is no compatibility shim, and why the only line that
differs between the two jars is `api-version`.

---

## Requirements

| | |
|---|---|
| Server | Paper **1.21** or newer. Folia is supported (`folia-supported: true`). |
| Java | **21** for the `mc1.21.11` jar, **25** for the `mc26.2` jar |
| Optional | [Nexo](https://nexomc.com) — only if you want a totem type backed by a Nexo item |

**Why the floor is 1.21 exactly.** The single hard API requirement is the `minecraft:max_stack_size`
data component, which is what gives each totem type its own stack size. That component shipped in
1.20.5 together with the Paper route the plugin calls for it (`ItemStack#setData` with
`DataComponentTypes.MAX_STACK_SIZE`). Every other API used here — `EntityResurrectEvent#getHand`,
`PersistentDataContainer`, `EntityEffect.PROTECTED_FROM_DEATH`, `JavaPlugin#registerCommand` — is
older or arrived alongside it. `1.21` is declared rather than `1.20.5` because 1.21 is the oldest
line actually tested; declaring a floor below what is tested would be a claim that cannot be backed.

The plugin ships **no textures, fonts, sprites or resource packs.** Custom appearance is your
resource pack's job, or Nexo's — see [Appearance](#appearance-item-model-custom-model-data-and-nexo).

---

## Installation

1. Download the jar that matches your server line from the table above.
2. Drop it into `plugins/`.
3. Start the server. The default `config.yml`, `totems.yml` and language files are written on first
   enable.
4. Edit `plugins/PolaroidTotems/totems.yml` to define your totem types.
5. Run `/totems reload`. Every YAML file is re-read; no restart is needed.

With the shipped defaults the plugin is already useful: ordinary totems stack to 16, activate from
anywhere in the inventory, and three example types (`guardian`, `ember`, `eclipse`) are available
through `/totems give`.

---

## Files on disk

```
plugins/PolaroidTotems/
├── config.yml        activation, normalization, resurrection behaviour, sound
├── totems.yml        every totem type the server knows
└── lang/
    ├── messages_en.yml
    └── messages_es.yml
```

All player-facing text lives in `lang/messages_<language>.yml`, selected by the `language` key in
`config.yml`. Missing keys fall back to English automatically, so a partial translation is safe to
ship. Every file is MiniMessage.

---

## Configuration reference — `totems.yml`

Every totem type lives under the top-level `totems:` key. The key you write is the type's **id**: it
is what `/totems give <player> <type>` takes, and it is the exact string stamped into the item's PDC.

Ids are lowercased when read, so `Guardian` and `guardian` are the same type. A broken entry costs
you that one type and logs a warning; it never takes the plugin down with it.

### Every key a totem can have

| Key | Type | Default | Accepted values |
|---|---|---|---|
| `item` | string | `vanilla` | `vanilla`, `vanilla:SOME_MATERIAL`, a bare material name, or `nexo:my_totem` / `nexo-my_totem` |
| `stack-size` | integer | `1` | `1`–`99`. Out-of-range values are clamped and logged, never rejected |
| `display-name` | MiniMessage string | unset | Any MiniMessage. Omit to keep the item's own name |
| `lore` | list of MiniMessage strings | empty | One entry per line. Omit or leave empty to leave the item's lore alone |
| `item-model` | namespaced key | unset | e.g. `polaroid:item/guardian_totem`. A bare `item/x` takes the `minecraft` namespace. An unparseable key is ignored with a warning |
| `custom-model-data` | integer | unset | Any integer your resource pack selects on. Legacy route |
| `consume` | boolean | `true` | `true` uses the totem up when it saves the player; `false` makes it reusable |
| `heal-to-full` | boolean | `false` | `true` heals to the player's real max-health attribute instead of vanilla's 1 HP |
| `permission` | string | unset (anyone) | A permission node. A player without it has the totem skipped entirely |
| `effects` | list of mappings | empty | See below |

### `effects` sub-keys

Each entry in `effects:` is a mapping. Effects are applied **one tick after** the resurrection — see
[How activation actually works](#how-activation-actually-works).

| Sub-key | Type | Default | Accepted values |
|---|---|---|---|
| `type` | string | required | An effect id, e.g. `REGENERATION` or `minecraft:regeneration`. Spaces become underscores; case does not matter. An unknown name skips that entry with a warning |
| `seconds` | number | `10` | Duration in seconds, converted to ticks for you. Rounded, minimum 1 tick |
| `level` | integer | `1` | Human level: `1` = I, `2` = II. Not the zero-based amplifier. Values below 1 are raised to 1 |
| `ambient` | boolean | `false` | `true` shows the faint "beacon" particle haze |
| `particles` | boolean | `true` | `false` hides particles entirely |

Vanilla always grants Regeneration II, Absorption II and Fire Resistance as part of any
resurrection. Naming one of those three in `effects:` replaces vanilla's version with yours;
anything else is granted on top.

### A complete worked example

```yaml
totems:
  guardian:
    item: vanilla                         # the ordinary Totem of Undying
    stack-size: 4                         # this type's own max_stack_size
    display-name: '<#95d027>ɢᴜᴀʀᴅɪᴀɴ ᴛᴏᴛᴇᴍ'
    lore:
      - '<#E9FDFB>Restores you to full health'
      - '<#E9FDFB>instead of a single heart.'
      - ''
      - '<#95d027>▸ ᴋᴇᴇᴘ ɪᴛ ᴀɴʏᴡʜᴇʀᴇ ᴏɴ ʏᴏᴜ'
    item-model: 'polaroid:item/guardian_totem'   # your resource pack maps this key to a model
    consume: true
    heal-to-full: true
    permission: ''                        # blank or absent: anyone may use it
    effects:
      - type: REGENERATION
        seconds: 12
        level: 3                          # Regeneration III
      - type: ABSORPTION
        seconds: 30
        level: 3
        ambient: false
        particles: true
```

`totems.yml` ships with this type plus two more (`ember`, a fire-resistance escape totem; `eclipse`,
a permission-gated rarity) and a commented Nexo-backed example, all documented line by line.

### About `stack-size`

`1`–`99` is the data component's own legal range, not a plugin limit. A value outside it is clamped
and logged rather than rejected, so a typo costs you a warning instead of a failure every time that
totem is handed out.

The component is written through Paper's
`ItemStack#setData(DataComponentTypes.MAX_STACK_SIZE, n)`. (`ItemMeta#setMaxStackSize` is the Bukkit
equivalent and writes the same component.) A `max_stack_size` above 1 is mutually exclusive with
`max_damage`; totems carry no durability, so the two can never collide here.

**Two types with different stack sizes never share a stack.** That is not a bug — see
[Why normalization exists](#why-normalization-exists).

---

## The reserved `vanilla` entry

One id is special. **`vanilla` describes the plain Totem of Undying**, and it is the type every
totem *without* a PDC tag is treated as.

```yaml
totems:
  vanilla:
    item: vanilla
    stack-size: 16      # ordinary totems now stack 16 high
    consume: true
    heal-to-full: false
    effects: []         # no custom effects: a plain totem does plain things
```

That single rule is what makes the plugin installable on a live server. It never has to hunt down
every totem already sitting in a chest somewhere — untagged simply *means* vanilla, and
normalization stamps them as they pass through a player's hands.

Raising `vanilla.stack-size` is how you make ordinary totems stack. `1` keeps strict vanilla
behaviour.

**Deleting the section is allowed.** The plugin then falls back to a synthesized default:
`item: vanilla:TOTEM_OF_UNDYING`, `stack-size: 1`, `consume: true`, `heal-to-full: false`, no
effects, no permission, no appearance keys — that is, exactly vanilla behaviour. Note that the
shipped file sets `stack-size: 16`; the fallback does not. If you want stacking totems, keep the
section.

Give the `vanilla` entry custom effects only if you really mean to change the ordinary item for
everyone on the server, including players who have never heard of this plugin.

---

## Appearance: `item-model`, `custom-model-data` and Nexo

Three different things, on two different layers. This is the section server owners most often get
wrong.

### `item-model` — the modern vanilla route

Writes the `minecraft:item_model` component: a namespaced model key that a **resource pack**
resolves to a model. You name the model outright instead of overloading a number. Vanilla moved to
this approach in 1.21.4, and it is the one to prefer for anything new.

```yaml
item-model: 'polaroid:item/guardian_totem'
```

A bare value such as `item/my_totem` is read in the `minecraft` namespace. An unparseable key is
ignored with a warning, so one typo never stops the totem from being handed out.

### `custom-model-data` — the legacy numeric route

Writes the `minecraft:custom_model_data` component. Still supported, because plenty of existing
resource packs select on this number.

```yaml
custom-model-data: 1001
```

The two keys are **independent components**: set either, both, or neither. When both are present the
client resolves `item_model` first, so your pack decides which one wins. The plugin does not
arbitrate.

### Nexo is a different layer

`item:` is an **item** reference. `item-model:` is a **model** key. They are not interchangeable.

```yaml
item:       'nexo:my_totem'      # an ITEM reference — Nexo hands back a finished item
                                 # that ALREADY carries its own model
item-model: 'polaroid:item/x'    # a vanilla MODEL key, resolved by a resource pack
```

**`item-model: 'nexo:my_totem'` does not work and cannot be made to work.** The Nexo API this plugin
calls exposes only `NexoItems.itemFromId` — an item, not a model key. Written as an `item-model`, the
client would hunt for a model literally named `nexo:my_totem` in the resource pack, which Nexo never
registers, and the totem would render as a missing model.

So with Nexo: **use `item:` alone and set neither appearance key.** The plugin stamps appearance
components *after* resolving the item, so setting `item-model` on top of a Nexo item overwrites the
model Nexo just gave it and the texture is lost.

Both separators are accepted: `nexo:my_totem` and `nexo-my_totem` are the same reference.

Nexo items are resolved **lazily, at the moment a totem is handed out** — never cached at startup,
because Nexo loads its items asynchronously and an id that looks missing during boot resolves
perfectly ten seconds later. If Nexo is absent or the id is unknown, `/totems give` says so and the
rest of the file keeps working.

### One real constraint when building a totem in Nexo

**Base the Nexo item on `TOTEM_OF_UNDYING`.**

The inventory-wide search matches on that material, deliberately: it runs at the instant a player
would otherwise die and has to be fast enough to be invisible. A material check is one field
comparison, while "does this item carry our tag" means reading item metadata for every slot.

A totem built on any other material still works perfectly when held in a hand — exactly like a
vanilla one — but is not found by the inventory-wide search. You would lose the feature this plugin
exists for.

### Nexo glyphs

Write a Nexo glyph tag such as `<glyph:totem>` anywhere in a config string and it renders. Nexo
registers its `<glyph:…>` tags with the server's MiniMessage through Paper's bootstrap tag registry;
this plugin simply lets config strings through untouched. With Nexo absent the tag degrades to
literal text and nothing breaks.

---

## Configuration reference — `config.yml`

Every key here is re-read by `/totems reload`.

| Key | Default | What it does |
|---|---|---|
| `language` | `en` | Which `lang/messages_<language>.yml` to load |
| `activation.from-inventory` | `true` | Master switch for inventory-wide activation. `false` restores vanilla hand-only behaviour; stack sizes and hand-held custom effects still work |
| `activation.include-armor-slots` | `false` | Also search the armour slots |
| `activation.require-permission` | `''` | Permission needed for inventory-wide activation. Blank allows everyone. A player without it keeps ordinary vanilla hand-held behaviour |
| `normalization.enabled` | `true` | Master switch for every normalization trigger below |
| `normalization.on-pickup` | `true` | Stamp a totem picked up off the ground, from a mob drop or from a hopper |
| `normalization.on-join` | `true` | Stamp everything already in a player's inventory when they log in |
| `normalization.on-inventory-click` | `true` | Stamp a totem moved by an inventory click |
| `normalization.on-inventory-open` | `true` | Scan a container the moment it is opened |
| `resurrection.effect-delay-ticks` | `1` | Ticks to wait before applying custom effects. Clamped to a minimum of 1 — see below |
| `resurrection.play-animation` | `true` | Play the vanilla totem animation on a resurrection the plugin forced |
| `resurrection.announce` | `true` | Tell the player in chat which totem type saved them (`totem.saved` in the language file) |
| `sound.resurrect` | `ITEM_TOTEM_USE` | The one plugin sound, played on a successful resurrection and nowhere else. An unknown name logs a warning and falls back to the totem sound |
| `sound.volume` | `1.0` | Volume |
| `sound.pitch` | `1.0` | Pitch |

A refused totem and a failed `/totems give` stay silent by design.

---

## How activation actually works

`EntityResurrectEvent` is a strange event, and the plugin is built around four facts about it.

1. **It fires on every lethal hit, always** — including when the entity carries no totem at all.
   With no totem in a hand it arrives **pre-cancelled**.
2. **`getHand()` returns `null` when the event is pre-cancelled.** That is the normal case, not an
   edge case. A plugin that reads it without a null check throws on every death on the server.
3. **The listener registers with `ignoreCancelled = false`.** Setting it `true` would skip exactly
   the pre-cancelled events this plugin exists to handle. Its priority is `HIGH` rather than
   `MONITOR`, because the decision is a real one that a later listener may still veto.
4. **`setCancelled(false)` forces the resurrection.** But when `getHand()` was null, vanilla consumes
   *nothing*, plays no animation and grants no effects for a totem it never saw — so the plugin
   removes one item from the inventory itself, plays the animation, and applies the effects.

### The two paths

| Path | What vanilla does | What the plugin does |
|---|---|---|
| Totem in a hand (event not cancelled) | Consumes the totem, plays the animation, grants its own effects | Adds that type's custom effects, the sound, and `heal-to-full` on top. Consumes nothing — vanilla already did |
| Totem elsewhere (event pre-cancelled) | Nothing | Un-cancels the event, consumes one totem from the slot it was found in, plays the animation and sound, applies `heal-to-full` and the custom effects |

The animation call is `player.playEffect(EntityEffect.PROTECTED_FROM_DEATH)`, not the
`TOTEM_RESURRECT` you may expect: that constant has been deprecated for removal since Paper 1.21.2
in favour of this one. Both carry the same wire id, so the client sees an identical animation.

### Search order

Stable and predictable: **main hand → off hand → the 36 storage slots in index order → armour**
(only when `activation.include-armor-slots` is on). A player who wants a specific totem used first
puts it in their hand, exactly as in vanilla.

A type whose `permission:` the player lacks is skipped during the search, not refused afterwards —
the player simply carries it as a souvenir and the search continues to the next candidate.

### Why custom effects land one tick later

Vanilla applies its own Regeneration II, Absorption II and Fire Resistance as part of the resurrect
branch, which runs **after** the plugin un-cancels the event. A custom effect applied in the same
tick is simply overwritten. One tick later, it survives. That is what `resurrection.effect-delay-ticks`
is for, and why it is clamped to a minimum of 1. Raise it only if you want a visible gap between
vanilla's effects and yours.

The delayed work is scheduled on the **entity scheduler**, not Bukkit's — every `BukkitScheduler`
call throws on Folia, and this work belongs to one specific entity. That is what makes
`folia-supported: true` honest.

---

## Why normalization exists

Read this before turning anything in the `normalization:` section off.

**Minecraft decides whether two stacks merge by comparing their components.** A totem carrying this
plugin's PDC type tag and stack-size component is therefore a *different item* from a plain one, and
the two will never stack together.

That is not a bug to work around — it is the mechanism. It is precisely what allows `guardian` to cap
at 4 while `ember` caps at 16. But left alone it means a player who loots totems from several sources
ends up with a shelf of one-item stacks that refuse to combine, which looks exactly like a bug even
though nothing is wrong.

So the plugin stamps every untagged Totem of Undying as the reserved `vanilla` type, at four moments:

| Trigger | Why it exists |
|---|---|
| Item pickup | The common case: ground drops, mob drops, hoppers |
| Player join | Everything that existed before the plugin was installed |
| Inventory click | A totem moved in from a chest or a shop GUI |
| Inventory open | A container full of totems, converted in one pass |

Each is switchable in `config.yml`. After stamping, everything the player carries shares one identity
and stacks to whatever `vanilla.stack-size` says.

**Keeping it cheap is a design constraint, not an afterthought.** Every handler early-exits on the
item *material* — a single field comparison — before anything reads item metadata. An already-tagged
totem is skipped outright. The click handler inspects only the clicked stack and the stack on the
cursor, never the whole inventory. The join and open scans walk storage slots only, and write a slot
back only when a stamp actually happened, so an inventory with no untagged totems performs zero
writes.

Turning normalization off is supported. Expect exactly the un-stacking described above.

---

## Commands and permissions

The root command is `/totems`, with aliases `/totem` and `/pt`.

| Command | Permission | What it does |
|---|---|---|
| `/totems help` | — | The help block. Admins see every subcommand; everyone else sees the short form |
| `/totems list` | `polaroidtotems.admin` | Every totem type, with its stack size and effect count |
| `/totems give <player> <type> [amount]` | `polaroidtotems.admin` | Hands a stamped totem to an online player. `amount` defaults to 1 and is clamped to 1–2304. Leftovers that do not fit are reported rather than silently eaten |
| `/totems reload` | `polaroidtotems.admin` | Re-reads `config.yml`, `totems.yml` and the language file |

| Permission | Default | Grants |
|---|---|---|
| `polaroidtotems.use` | everyone | Being resurrected by a custom totem from anywhere in the inventory |
| `polaroidtotems.admin` | op | `list`, `give` and `reload` |

Tab completion is permission-aware: a player without `polaroidtotems.admin` is suggested only `help`.

A totem type may also carry its own `permission:` key, and inventory-wide activation may require
`activation.require-permission` from `config.yml`. Those are separate nodes you define yourself;
neither is declared in the plugin.

---

## Building from source

```bash
./gradlew allJars      # both shipping jars, into build/libs/
./gradlew test         # the unit suite
./gradlew build        # a single jar targeting the 1.x line, for local testing
```

`allJars` produces `PolaroidTotems-<version>-mc1.21.11.jar` and
`PolaroidTotems-<version>-mc26.2.jar`.

**Toolchains.** Gradle needs a **JDK 21** and a **JDK 25** installed locally: the 1.x jar is compiled
at release 21 and the 26.x jar at release 25, and each target requests a javac that can emit its own
release. Toolchain auto-download is **off by design** (`org.gradle.java.installations.auto-download=false`
in `gradle.properties`) — the build never downloads a JDK behind your back. Auto-detection is on, and
`JAVA_HOME_21_X64` is read as an explicit hint.

There is no shadow jar and no runtime dependency to relocate: no database means no Hikari and no JDBC
driver. `paper-api` and Nexo are both `compileOnly`.

---

## License

[PolyForm Noncommercial License 1.0.0](LICENSE). Copyright PolaroidStudio.

In plain terms: you may use, modify and share this plugin freely for any **noncommercial** purpose,
and you must pass these terms on with any copy you distribute. Running it on a server you do not
charge for is a permitted use. Selling the plugin, or selling access to it, is not.

The licence itself defines a permitted purpose broadly — personal use, hobby projects, study and
research all qualify, as does use by a charity, school, public research body or government
institution regardless of how it is funded. Read the [full text](LICENSE) for the exact wording,
including the patent grant and the thirty-day cure period for violations.

PolyForm Noncommercial is a source-available licence, not an OSI-approved open-source one: the
noncommercial restriction is precisely what open-source licences do not allow. It was chosen over
Creative Commons NC because it is drafted for software rather than for creative works.
