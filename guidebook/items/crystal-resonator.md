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

* It runs on power from a [Data Cable](data-cables.md) network or a generator next to it.
* Everything touching it that can grow gets extra growth attempts.
* It works on Seeded Amethyst, budding blocks, crops, saplings, bamboo, sugar cane, vines, kelp, sweet berries, nether wart and cocoa.
* Several Resonators add up.
* Extra growth wears a Seeded Amethyst down less often than natural growth. A seed gives more crystals in total.
* It doesn't count toward a network's [machine limit](network-brain.md).

<br />
<br />
## At a glance (Default values)

| | |
| --- | --- |
| Power use | 10 FE/t |
| Time between extra growth tries | 0.75 seconds |
| Wear chance on Seeded Amethyst | 6% |

<br />
<br />
## Recipe

<RecipeFor id="crystal_resonator" />
