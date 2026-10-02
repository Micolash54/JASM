# Crystals, chips and Bitlings

This guide is for JASM 0.6.0 on Minecraft 26.1.2 with NeoForge. See the [requirements](../README.md#requirements) for loader and optional mod versions.

JASM's recipes are made from its own chips. This page shows how to get them, from your first hand-made chip to a Workshop run by a fully grown Byteling, and how a Bitling becomes the brain of your network. The exact recipes are on the [recipes page](recipes.md).

## Growing Data Crystals

1. Craft a **Crystal Seed** and use it on a **Block of Amethyst**. It becomes **Seeded Amethyst**.
2. Seeded Amethyst grows **Data Crystal buds** on its open sides, like budding amethyst: small, medium, large, then a full **cluster**.
3. Mine a full cluster for **Data Crystals** (Fortune gives more). Buds broken early give **Crystal Dust**; four of it and a redstone make a new seed.
4. Each time a bud grows, the block may wear down: Seeded, Worn, Cracked, then back to a plain Block of Amethyst. Mined at any stage, it gives the Block of Amethyst back and the seed is lost.

A powered **Crystal Resonator** next to Seeded Amethyst makes it grow faster. Several Resonators add up. It doesn't give more crystals per seed, only sooner.

## Making chips by hand

- A **Stonecutter** cuts one Data Crystal into one **Blank Chip**.
- Cook a Blank Chip on a **Campfire** for an Unquenched Logic Chip, or in a **Blast Furnace** for an Unquenched Memory Chip.
- Drop the hot chip in **water**, still or flowing. After a moment it hisses and becomes a **Logic Chip** or **Memory Chip**. The whole stack cools at once and no water is used up.

## The Crystal Foundry

The Foundry grows crystals in bulk: every Crystal Seed always makes 16 Blank Chips, more than a seeded block gives on average. It needs power while it grows. Hoppers and pipes can feed it seeds and take the chips out.

## Finding Bitlings

Basic Bitlings aren't crafted. They live in the wild, and you make friends with them.

- **Where**: wild Bitlings turn up now and then on well-lit grass in the Overworld. They don't live in oceans, rivers, beaches, deserts, badlands, mushroom fields, caves or the Deep Dark. They stay where they are, even when you walk away.
- **Drawn to crystals**: growing Data Crystals attract them. While you are near Seeded Amethyst, a Bitling sometimes wanders over from a little way off to have a look, and stays around the crystals for a few minutes before settling down there. None comes while another wild Bitling is already close by.
- **Befriending**: hand a wild Bitling a Logic, Memory or Link Chip (an Advanced one works too). It looks the chip over, nods, and turns into a **Basic Bitling** item at its feet. Blank and unquenched chips only get a shake of the head.
- **Data Crystals**: hand it a Data Crystal and it eats it and follows you for about 30 seconds, so you can lead it somewhere. It stops if you get more than 24 blocks ahead.
- Hit one and it runs off. Knocked out, it vanishes in a puff and leaves nothing behind.

## The Chip Workshop and Bitlings

Put a **Bitling** in the Workshop and feed it Blank Chips. It makes Logic, Memory and **Link Chips**, and now and then a rare **Advanced** chip. Link Chips and Advanced chips can only be made here.

| Critter | Makes |
| --- | --- |
| Basic Bitling | all three types evenly, rarely an Advanced one |
| Logic, Memory or Link Bitling | mostly its own type, sometimes Advanced |
| Nibbling | only its own type, Advanced more often |
| Byteling | only its own type; a switch in the Workshop picks standard or Advanced |

- **Single** makes one chip every 10 seconds; **Batch** makes up to 8 at once, in 60 seconds. Every chip made costs the Bitling 2,000 FE, in either mode.
- **Training**: a typed Bitling or Nibbling learns from every chip it makes. With a full training bar, it can grow up into the next stage in a crafting grid. It keeps its charge.
- **Battery**: the Workshop itself uses no power, only the critter does. Power from a cable or a neighbouring generator goes straight into the critter's battery, and each chip uses some of it. With no power at all, the critter keeps working until its battery runs flat, then naps until it is charged again. You can also charge a critter in a Combustion Generator.
- A Basic Bitling becomes a Logic, Memory or Link Bitling when crafted with chips of that type.

## The Bitling Station

Put any Bitling, Nibbling or Byteling in a **Bitling Station** and a little living Bitling pops out and roams around it: walking, sprinting, hopping, looking about and lying down for a rest. Right-click it to pet it.

- The little Bitling is only a body. The **critter's own battery** pays for its walk (5 FE a tick while it is out and about).
- When the battery runs low (5%), it walks home and sits on the pad. The station charges it from its own power, 20 FE a tick, so connect the station to a Data Cable network or put a generator next to it.
- If it gets stuck on the way home or its battery is empty, it teleports back onto the pad.
- The station's screen shows what it is doing, its battery, and a slider for how far it may roam (4 to 16 blocks).
- If something knocks it out, it comes back after 30 seconds with full health. Take the critter out and the little Bitling is gone.
- Hoppers and pipes can't reach the critter slot. Only its owner and the players they trust can open the station.

## The Network Brain

A network can hold **4 machines** on its own. Machines joined by Data Cables form a network, and so do JASM machines that simply touch each other, with no cable at all. Add a fifth and the whole network stops: every machine on it works as if it had no power until you remove one or add a **Network Brain**. The machines' screens, Jade and a linked Deck's Network tab show when a network is full.

- **What counts**: Encoding Terminals, Recipe Racks, Crafting Servers, Access Ports and every other port, Archives, Chip Workshops, Crystal Foundries, Crystal Resonators and Bitling Stations, whether they are joined by cable or by touching each other. A row of five Archives side by side is a network of five. Cables, generators, Creative Batteries, brains and chambers don't count.
- **Learning**: the brain learns all the time while it has power, and each level lets the network hold more machines. Put Logic, Memory or Link Chips in its slot to learn faster: it eats one every half second, and each one fills 2% of the current level's bar. Advanced chips fill 10%. Right-click the brain with chips to feed it one, sneak-right-click to feed the whole stack, or let a hopper or pipe feed it from any side. A brain that has reached its top level takes no more chips.
- **Power**: if the brain runs out of power, the network falls back to 4 machines.
- **Mined**, a brain keeps its level and progress.

| Brain level | Machines |
| --- | --- |
| No brain | 4 |
| 1 | 8 |
| 2 | 12 |
| 3 | 16 |
| 4 | 24 |
| 5 | 32 |
| 6 | 48 |
| 7 | 64 |
| 8 | 96 |
| 9 | 128 |
| 10 | 256 |

### Network Chambers

A brain on its own stops at level 3. Build **Network Chambers** around it into a cube to go further and learn faster. The brain can sit anywhere in the cube, and cables can join any face of it. Right-click any chamber of the cube to open the brain's screen, or to feed it chips.

| Shape | Chambers | Top level | Learning speed | Power |
| --- | --- | --- | --- | --- |
| Single brain | none | 3 | normal | 8 FE/t |
| 2×2×2 cube | 7 | 6 | twice as fast | 16 FE/t |
| 3×3×3 cube | 26 | 10 | three times as fast | 32 FE/t |

A brain moved into a smaller cube keeps its level, but works at the smaller cube's top level until it has its big cube back.

### Two brains on one network

Brains don't add up. When a network holds more than one, only the best working brain leads: the highest level, then the most progress, then the one placed first. The others rest and don't learn until they lead again.

## How the tiers use the chips

| Tier | Needs |
| --- | --- |
| First tier | vanilla items only |
| Second (Basic Deck, 1k and 4k wafers, first crafting blocks) | hand-made Logic and Memory Chips |
| Third (Advanced Deck, 16k wafers, Access Port) | Link Chips |
| Top (Elite and Ultimate, 64k wafers) | Advanced chips |
