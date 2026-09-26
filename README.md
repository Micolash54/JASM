<p align="center">
  <img src="src/main/resources/jasm_logo.png" alt="JASM" width="448">
</p>

<p align="center"><b>Just Another Storage Mod</b>: portable, recoverable digital storage for Minecraft.</p>

---

JASM stores your items as data on small chips called wafers. Carry them around in a handheld Deck, and back them up at an Archive, so a lost wafer can be rebuilt instead of gone for good.

## Features

- **Capacity Wafers**: storage chips in five sizes, from Basic up to 64K.
- **Type Wafers**: hold only a few kinds of items, but lots of each. Five sizes, from 4 types up to 64.
- **Decks**: handheld readers that hold several wafers at once, with a searchable storage screen. Five tiers, from Starter to Ultimate.
- **Wafer priorities**: choose which wafer in a Deck fills first, and which items each wafer is for.
- **Archives**: link your wafers to one, and if a wafer is ever lost, rebuild it onto a blank. Only the owner can use it, and the players an Encoding Terminal of theirs on the same network trusts.
- **Combustion Generators**: burn anything a furnace burns for power, in Basic, Advanced and Elite tiers. Each has a charging slot for Decks.
- **Crafting Decks**: Advanced, Elite and Ultimate Decks with a 3×3 crafting grid that takes ingredients straight from the wafers and refills itself.
- **Autocrafting**: write recipes onto Recipe Cards at an Encoding Terminal, keep them in Recipe Racks, and let Crafting Servers do the work. Ask for anything from your Crafting Deck, and the ingredients it needs are crafted first. Processors decide how many crafts run at once, Storage Modules how big a job fits. A job list on the Deck shows every job and opens its server from anywhere.
- **Crafting rules**: "when I have fewer than 16 torches, craft 32" or "every minute, craft 8 bread", set on the Crafting Deck, with the results sent to the Deck or straight into your inventory.
- **Data Cables**: join the crafting blocks and carry power between them. Dye them to keep networks apart.
- **Creative Battery**: unlimited power for creative worlds, with a charging slot.
- **Safe by design**: copied wafers stop working, a crash never duplicates items, and items from removed mods are kept until the mod comes back.

## Status

JASM is in early testing. Things will change between versions, so back up your worlds.

## Recipes

Every item except the Creative Battery is craftable in survival. The first tier uses vanilla items, and each higher tier is crafted from the one below it, keeping everything it holds. See [all recipes](wiki/recipes.md).

## Requirements

- Minecraft 26.3
- NeoForge 26.3.0.16-beta

Optional: with [JEI](https://www.curseforge.com/minecraft/mc-mods/jei) installed, you get all recipes, info pages and a fuel page for the generators, the Deck's grid works with JEI's recipe keys, and JEI's "+" fills the Crafting Deck's grid and the Encoding Terminal. With [Jade](https://www.curseforge.com/minecraft/mc-mods/jade), looking at an Archive, generator or crafting block shows its details.

## Installing

Download the latest jar from [Releases](https://github.com/Micolash54/JASM/releases) and put it in your `mods` folder.

## License

JASM is licensed under [CC BY-NC-SA 4.0](LICENSE): you may share it and make your own versions, as long as you credit it, don't make money from it, and release your version under the same license.

**Modpacks**: as an additional permission on top of the license, you may include JASM, unchanged, in modpacks that are free to download, including packs that earn rewards from the platform they're published on (such as CurseForge rewards or Modrinth payouts). Packs that are sold, or kept behind a paywall, are not covered by this permission.
