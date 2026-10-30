---
navigation:
  title: Chip Workshop
  parent: items/index.md
  position: 100
  icon: chip_workshop
item_ids:
- jasm:chip_workshop
---

# Chip Workshop

<GameScene zoom="6" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <Block id="jasm:chip_workshop" x="0" y="0" z="0" />
  <Entity id="jasm:wild_bitling" data="{kind:3,stage:2}" x="1.7" z="0.5" rotationY="0" />
</GameScene>

Put a Bitling in the Chip Workshop and feed it <ItemLink id="blank_chip" />s. It turns them into Logic, Memory and <ItemLink id="link_chip" />s, and now and then a rare <ItemLink id="advanced_logic_chip" />.

| At a glance | |
| --- | --- |
| Single mode | one chip every 10 seconds |
| Single mode with 4 Speed Upgrades | one chip every 0.65 seconds |
| Batch mode | up to 8 chips at once, in 60 seconds |
| Batch mode with 4 Speed Upgrades | up to 8 chips at once, in 3.75 seconds |
| Cost | 2,000 FE from the Bitling's battery for every chip |
| Cost with 4 Speed Upgrades | 16,000 FE for every chip |
| Input | a 2x2 grid |
| Upgrades | five slots: up to four [Speed Upgrades](speed-upgrade.md) and one [Redstone Upgrade](redstone-upgrade.md) |

<br />
<br />
## Who makes what

| Critter | Makes |
| --- | --- |
| <ItemLink id="basic_bitling" /> | all three types evenly, rarely an Advanced one |
| Logic, Memory or Link Bitling | mostly its own type, sometimes Advanced |
| <ItemLink id="logic_nibbling" /> | only its own type, Advanced more often |
| <ItemLink id="logic_byteling" /> | only its own type; a switch picks standard or Advanced |

<br />
<br />
## Power

The Workshop itself uses no power. Only the critter does. Power from a cable or a generator next to it goes straight into the critter's battery, as fast as they can give it.

A job starts once the battery holds enough for one chip. The critter pays when the job ends: it makes as many chips as its battery can pay for right then, and the other Blank Chips stay in the grid. Each Speed Upgrade doubles the speed, and every chip costs more.

With no power at all, the critter keeps going until it can't pay for another chip, then naps until it has enough for one. You can also charge a critter in a [Combustion Generator](combustion-generators.md).

A Redstone Upgrade adds a key under the upgrade slots to pause the Workshop. The critter still charges while it waits, and a half-made chip keeps its progress.

<br />
<br />
## Building things in the grid

Blank Chips can go in any of the four slots, and the critter keeps making chips even with other things beside them. A few things aren't chips: a Byteling builds the <ItemLink id="synapse_core" /> from what is in the grid.

Its Input / Output key sets each side and shows the block next to it. Input fills the grid. Output lets finished chips be pulled out there. The Blank Chips in the grid never leave through a side. Every side starts at Input.

With Auto Output on, the Workshop also sends finished chips out of its Output sides into whatever is there, an Access Port included. It starts off.

For Access Port jobs, set the side facing the port to Input/Output and turn Auto Output on.

A Chip Workshop counts toward a network's [machine limit](network-brain.md).

<br />
<br />
## Recipe

<RecipeFor id="chip_workshop" />
