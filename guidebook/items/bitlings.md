---
navigation:
  title: Bitlings
  parent: items/index.md
  position: 120
  icon: logic_bitling
item_ids:
- jasm:basic_bitling
- jasm:logic_bitling
- jasm:memory_bitling
- jasm:link_bitling
---

# Bitlings

<GameScene zoom="6" padding="2">
  <IsometricCamera yaw="0" pitch="15" />
  <Entity id="jasm:wild_bitling" data="{kind:0,stage:0}" x="0.5" z="0.5" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:1,stage:0}" x="1.6" z="0.5" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:2,stage:0}" x="2.7" z="0.5" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:3,stage:0}" x="3.8" z="0.5" rotationY="0" />
</GameScene>

Bitlings are small robot helpers that work the [Chip Workshop](chip-workshop.md). Without one, a Workshop does nothing.

## Basic Bitling

You can't craft one. [Find a wild Bitling](../mechanics/befriending-bitlings.md) and hand it a chip.

* Makes Logic, Memory and Link Chips evenly, and rarely an Advanced one.
* Battery of 50,000 FE.
* The Network Brain recipe needs one.

## Typed Bitlings

Surround a Basic Bitling with 8 chips of one type in a crafting grid to get a Logic, Memory or Link Bitling.

* Mostly makes its own type, sometimes Advanced.
* Battery of 100,000 FE.
* [Trains](../mechanics/training-bitlings.md) on every chip it makes, then grows into a Nibbling.

## Recipes

<RecipeFor id="logic_bitling" />
<RecipeFor id="memory_bitling" />
<RecipeFor id="link_bitling" />
