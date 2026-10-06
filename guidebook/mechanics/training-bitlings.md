---
navigation:
  title: Training Bitlings
  parent: mechanics/index.md
  position: 40
  icon: logic_nibbling
---

# Training Bitlings

<Row>
  <GameScene zoom="6" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Entity id="jasm:wild_bitling" data="{kind:0,stage:0}" x="0.5" z="0.5" rotationY="0" />
  </GameScene>
  <GameScene zoom="6" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Entity id="jasm:wild_bitling" data="{kind:1,stage:0}" x="0.5" z="0.5" rotationY="0" />
  </GameScene>
</Row>

A Bitling that works in the [Chip Workshop](../items/chip-workshop.md) learns from every chip it makes. When its training bar is full, you can grow it up in a crafting grid.

## The ladder

| Stage | Chips to fill the bar | Grows up with |
| --- | --- | --- |
| Basic Bitling | none | 8 chips of one type |
| Logic, Memory or Link Bitling | 100 | 7 chips of its type and a Diamond |
| Nibbling | 300 | 7 Advanced chips of its type and a Netherite Scrap |
| Byteling | top of the ladder | nothing more |

Put the critter in the middle of the grid and the rest round it. It keeps its charge when it grows up. The real grids are on the pages for [Bitlings](../items/bitlings.md) and [Nibblings and Bytelings](../items/nibblings-and-bytelings.md).

## Why bother

* Bigger batteries: 50,000 FE for a Basic Bitling, 100,000 for a typed one, 200,000 for a Nibbling and 400,000 for a Byteling.
* Better odds of a rare Advanced chip.
* Only a Byteling can build a [Synapse Core](../items/synapse-core.md).

The chip counts are the defaults and the config can change them.
