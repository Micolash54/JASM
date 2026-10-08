---
navigation:
  title: Crystal Foundry
  parent: items/index.md
  position: 70
  icon: crystal_foundry
item_ids:
- jasm:crystal_foundry
---

# Crystal Foundry

<BlockImage id="crystal_foundry" scale="3.5" />

The Crystal Foundry grows Data Crystals in bulk and cuts each one into a <ItemLink id="blank_chip" />.

* One <ItemLink id="crystal_seed" /> makes 16 Blank Chips by default, one every 10 seconds.
* It runs on power like any other machine, from a [Data Cable](data-cables.md) network or a generator next to it. It uses 40 FE each tick while it grows.
* The seed is used up as soon as it starts growing.
* Its Input / Output key sets each side. Input lets hoppers and pipes put seeds in. Output sends the chips out into whatever is on that side, and lets them be pulled out there too. Every side starts closed.
* It counts toward a network's machine limit. See [the Network Brain](network-brain.md).

<br />
<br />
## Recipe

<RecipeFor id="crystal_foundry" />
