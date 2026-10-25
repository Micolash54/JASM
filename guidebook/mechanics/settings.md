---
navigation:
  title: Settings
  parent: mechanics/index.md
  position: 130
  icon: wrench
---

# Settings

Every number and switch JASM lets you change, in the order the config screen shows them.

<br />
<br />
## Changing a value

* In singleplayer, open a world, then choose **Mods**, **JASM**, **Config**.
* The screen has two parts: **World rules** (`jasm-server.toml`) and **My settings** (`jasm-client.toml`).
* On a server, edit `jasm-server.toml`.
* Players who join a server get the server's world rules.
* Deck slots, the features and the Basic Bitling recipe apply after the world reloads.

<br />
<br />
## Where the values are kept

* Each world keeps its own copy, at `saves/<world>/serverconfig/jasm-server.toml`.
* The copy in `config/` is only what new worlds start with.
* An update never changes a value that is already saved in a file. An old world keeps its old numbers until you change them.

<br />
<br />
## World rules

These are in `jasm-server.toml`. Each heading below is one screen under **World rules**.

<br />
<br />
## Preset

| Setting | Key | What it does |
| --- | --- | --- |
| Preset | `preset` | Fills in many of the numbers below at once: Relaxed, Standard or Hardcore. Picking Standard sets them all back. |

<br />
<br />
## Decks

| Setting | Key | What it does |
| --- | --- | --- |
| Power per item moved (FE) | `energyPerItem` | FE a Deck uses for each item that goes into or out of its wafers. |
| Send blocks per second | `sendBlocksPerSecond` | How many blocks a Deck to Deck trip covers each second. |

**Wafer slots** is a screen inside Decks. Lowering a number never deletes a wafer: the extra ones wait in the Deck to be taken out.

| Setting | Key | What it does |
| --- | --- | --- |
| Starter Deck | `starter` | Wafer slots in a Starter Deck. |
| Basic Deck | `basic` | Wafer slots in a Basic Deck. |
| Advanced Deck | `advanced` | Wafer slots in an Advanced Deck. |
| Elite Deck | `elite` | Wafer slots in an Elite Deck. |
| Ultimate Deck | `ultimate` | Wafer slots in an Ultimate Deck. Past 24, the Deck's wafer panel gets pages. |

<br />
<br />
## Power

| Setting | Key | What it does |
| --- | --- | --- |
| Encoder drain (FE/t) | `terminalDrain` | FE a Network Encoder uses each tick. |
| Rack drain (FE/t) | `rackDrain` | FE a Recipe Rack uses each tick. |
| Server drain (FE/t) | `serverDrain` | FE a Crafting Server uses each tick on its own, busy or idle. |
| Port drain (FE/t) | `portDrain` | FE an Access Port uses each tick. |
| Basic Processor use (FE/t) | `basicProcessorDrain` | FE each Basic Processor adds to its server's use each tick. |
| Adv. Processor use (FE/t) | `advancedProcessorDrain` | FE each Advanced Processor adds to its server's use each tick. |
| Elite Processor use (FE/t) | `eliteProcessorDrain` | FE each Elite Processor adds to its server's use each tick. |
| Resonator drain (FE/t) | `resonatorDrain` | FE a powered Crystal Resonator uses each tick. |
| Foundry drain (FE/t) | `foundryDrain` | FE the Crystal Foundry uses each tick while it grows. |
| Battery: Basic Bitling (FE) | `batteryBasic` | FE a Basic Bitling's own battery holds. |
| Battery: typed Bitling (FE) | `batteryBitling` | FE a typed Bitling's own battery holds. |
| Battery: Nibbling (FE) | `batteryNibbling` | FE a Nibbling's own battery holds. |
| Battery: Byteling (FE) | `batteryByteling` | FE a Byteling's own battery holds. |
| Most blocks in a Battery | `batteryMaxBlocks` | Most blocks one Battery may have. A block that would join up a bigger one can't be placed. |
| Battery capacity bonus per block (%) | `batteryCapacityBonus` | Extra room in each Battery block for every block in its group, in percent. A lone block has no bonus. |
| Battery charging slot base (FE/t) | `batteryChargeBase` | FE each tick a Battery's charging slot gives an item, before the size bonus. |
| Battery charging slot per block (FE/t) | `batteryChargePerBlock` | Extra FE each tick the charging slot gives for every block in the Battery. |
| Battery charging slot max (FE/t) | `batteryChargeMax` | Most FE each tick the charging slot gives, however big the Battery is. |

<br />
<br />
## Autocrafting

| Setting | Key | What it does |
| --- | --- | --- |
| Craft ticks | `craftTicks` | Ticks one craft takes on a Crafting Server, on every Processor tier. |
| Max request | `maxRequest` | Most items one crafting request may ask for. |

<br />
<br />
## Chips and Bitlings

| Setting | Key | What it does |
| --- | --- | --- |
| Seeded wear chance | `seededWearChance` | Chance that a Seeded Amethyst wears down one stage each time a Data Crystal bud grows on it. |
| Resonator interval | `resonatorInterval` | Ticks between the extra growth tries a Crystal Resonator gives each plant or budding block it touches. |
| Resonator wear chance | `resonatorWearChance` | Chance that a Seeded Amethyst wears down one stage each time a Crystal Resonator's extra growth makes a bud grow. |
| Foundry crystals per seed | `foundryCrystalsPerSeed` | Data Crystals, cut straight into Blank Chips, that the Crystal Foundry grows from one Crystal Seed. |
| Typed: own-type chance | `oddsOwnTypeBitling` | Chance a typed Bitling makes chips of its own type. The other two types share the rest. |
| Basic: Advanced chance | `advancedChanceBasic` | Chance a chip made by a Basic Bitling comes out Advanced. |
| Typed: Advanced chance | `advancedChanceBitling` | Chance a chip made by a typed Bitling comes out Advanced. |
| Nibbling: Advanced chance | `advancedChanceNibbling` | Chance a chip made by a Nibbling comes out Advanced. |
| Chips before a typed Bitling grows up | `trainingBitling` | Chips a typed Bitling must make before it can grow up. |
| Chips before a Nibbling grows up | `trainingNibbling` | Chips a Nibbling must make before it can grow up. |

<br />
<br />
## Network Brain

The three lists start with a lone Network Brain, then a tower of 1 floor, 2 floors, and so on. A taller tower uses the last entry.

| Setting | Key | What it does |
| --- | --- | --- |
| Machines without a brain | `limitWithoutBrain` | Machines a network may hold without a working Network Brain. |
| Most floors in a tower | `maxFloors` | Floors one brain tower may have. Floors stacked past this make a tower of their own. |
| Machines by floors | `machines` | List: machines a network may hold with a brain or a tower. |
| Power use by floors (FE/t) | `drains` | List: FE a whole tower uses every tick. |
| Power held by floors (FE) | `pools` | List: FE a whole tower holds in one shared pool. |
| No machine limit | `noMachineLimit` | Every network holds any number of machines, with or without a Network Brain. Brains still use power. |
| Machine limit multiplier | `machineMultiplier` | Multiplies every machine limit above, the one without a brain too. |

<br />
<br />
## Wild Bitlings

| Setting | Key | What it does |
| --- | --- | --- |
| Spawn rate (%) | `spawnRate` | How often a growing Seeded Amethyst draws a wild Bitling over, in percent of normal. 0 means never. |
| Diamond trades give amethyst blocks | `diamondAmethystDrop` | Wild Bitlings accept diamonds and leave a Block of Amethyst. Off: they refuse diamonds without taking them. |
| Basic Bitling recipe | `basicBitlingRecipe` | Lets players craft a Basic Bitling from Logic Chips, Memory Chips and an iron ingot, for worlds without wild Bitlings. |
| Follow range | `followRange` | Blocks away a player can get before a wild Bitling stops following them. |
| Health | `health` | Health of a wild Bitling, 2 per heart. |

<br />
<br />
## Bays

| Setting | Key | What it does |
| --- | --- | --- |
| Cycle ticks | `cycleTicks` | List: ticks one bay action takes with no Speed Upgrades, then one, two and so on. Extra upgrades use the last entry. |
| Energy capacity (FE) | `energyCapacity` | FE each bay holds. |
| Tank buckets | `tankBuckets` | Buckets each bay's tank holds. |
| Demolition cost/point (FE) | `demolitionFePerPoint` | FE the Demolition Bay uses for each point of a block's break cost. |
| Demolition scoop cost (FE) | `demolitionScoopCost` | FE the Demolition Bay uses to scoop one bucket of fluid. |
| Deployment cost (FE) | `deploymentCost` | FE the Deployment Bay uses for each block placed, fluid placed or stack thrown. |
| Drop entity limit | `dropEntityLimit` | The Deployment Bay's drop mode pauses while this many entities are near it. |
| Drop entity radius | `dropEntityRadius` | How far, in blocks, the Deployment Bay's drop mode looks for those entities. |

<br />
<br />
## Features

Each one turns a part of JASM on or off for the world.

| Setting | Key | What it does |
| --- | --- | --- |
| Deck to Deck | `deckToDeck` | Deck to Deck sending. Off: nothing new can be sent, but trips already on their way still arrive. |
| Bays | `bays` | The Deployment and Demolition Bays. Off: they can't be crafted, and placed ones stop working but keep their contents. |
| Combustion Generators | `generators` | The Combustion Generators. Off: they can't be crafted, and placed ones stop burning but keep their fuel and charge. |

<br />
<br />
## My settings

These are your own, in `jasm-client.toml`. They don't change with the world.

**Screens**

| Setting | Key | What it does |
| --- | --- | --- |
| Deck grid height | `deckSize` | Height of the Deck screen's item grid. |
| Deck sort | `deckSort` | What the Deck screen sorts its grid by. |
| Deck sort ascending | `deckAscending` | Sorts the Deck screen's grid from smallest to largest, or A to Z. |
| Sync Deck search with JEI | `deckSearchSync` | With JEI installed, the Deck's search and JEI's search show the same text. |
| Fold bay filters | `bayFilterCollapsed` | Folds the filter away on bay panels. |

**Sounds**

| Setting | Key | What it does |
| --- | --- | --- |
| Mute the Demolition Bay laser | `bayLaserMuted` | Silences every Demolition Bay's laser. |
