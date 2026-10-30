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

* A <ItemLink id="crystal_seed" /> grows into Blank Chips. The seed is used up as soon as it starts growing.
* It runs on power from a [Data Cable](data-cables.md) network or a generator next to it.
* Five upgrade slots take up to four [Speed Upgrades](speed-upgrade.md) and one [Redstone Upgrade](redstone-upgrade.md).
* It waits when its outputs are full. A Redstone Upgrade adds a key under the slots to pause it, and the crystal keeps its progress.
* Its Input / Output key sets each side and shows the block next to it. Input lets hoppers and pipes put seeds in. Output lets the chips be pulled out there. Every side starts at Input.
* With Auto Output on, it also sends the chips out of its Output sides into whatever is there, an Access Port included. It starts off.
* For Access Port jobs, set the side facing the port to Input/Output and turn Auto Output on.
* It counts toward a network's machine limit. See [the Network Brain](network-brain.md).

<br />
<br />
## At a glance (Default values)

| | |
| --- | --- |
| Chips per seed | 16 |
| Time per chip | 10 seconds |
| Power use | 40 FE/t |

<br />
<br />
## Recipe

<RecipeFor id="crystal_foundry" />
