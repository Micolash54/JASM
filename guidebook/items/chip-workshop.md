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
| Batch mode | up to 8 chips at once, in 60 seconds |
| Cost | 2,000 FE from the Bitling's battery for every chip |
| Input | a 2x2 grid |

## Who makes what

| Critter | Makes |
| --- | --- |
| <ItemLink id="basic_bitling" /> | all three types evenly, rarely an Advanced one |
| Logic, Memory or Link Bitling | mostly its own type, sometimes Advanced |
| <ItemLink id="logic_nibbling" /> | only its own type, Advanced more often |
| <ItemLink id="logic_byteling" /> | only its own type; a switch picks standard or Advanced |

## Power

The Workshop itself uses no power. Only the critter does. Power from a cable or a generator next to it goes straight into the critter's battery, and each chip takes some out.

With no power at all, the critter keeps going until its battery runs flat, then naps until it is charged again. You can also charge a critter in a [Combustion Generator](combustion-generators.md).

## Building things in the grid

Blank Chips can go in any of the four slots, and the critter keeps making chips even with other things beside them. A few things aren't chips: a Byteling builds the <ItemLink id="synapse_core" /> from what is in the grid.

A Chip Workshop counts toward a network's [machine limit](network-brain.md).

## Recipe

<RecipeFor id="chip_workshop" />
