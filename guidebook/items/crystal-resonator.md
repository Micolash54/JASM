---
navigation:
  title: Crystal Resonator
  parent: items/index.md
  position: 60
  icon: crystal_resonator
item_ids:
- jasm:crystal_resonator
---

# Crystal Resonator

<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <Block id="jasm:seeded_amethyst" x="0" y="0" z="0" />
  <Block id="jasm:data_crystal_cluster" x="0" y="1" z="0" />
  <Block id="jasm:crystal_resonator" x="1" y="0" z="0" />
</GameScene>

A powered Crystal Resonator makes the plants and budding blocks next to it grow faster, <ItemLink id="seeded_amethyst" /> included.

* It runs on power like any other machine, from a [Data Cable](data-cables.md) network or a generator next to it. It uses 10 FE each tick by default.
* Everything touching it that can grow gets one extra growth attempt every 0.75 seconds (15 ticks) by default.
* It works on Seeded Amethyst, budding blocks, crops, saplings, bamboo, sugar cane, vines, kelp, sweet berries, nether wart and cocoa.
* Several Resonators add up.
* On Seeded Amethyst, each extra growth has a 6% chance by default of wearing the block down, half the natural 12%. A seed gives more crystals in total.
* It doesn't count toward a network's [machine limit](network-brain.md).

<br />
<br />
## Recipe

<RecipeFor id="crystal_resonator" />
