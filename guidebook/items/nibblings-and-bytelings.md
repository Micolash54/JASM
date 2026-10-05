---
navigation:
  title: Nibblings and Bytelings
  parent: items/index.md
  position: 130
  icon: logic_byteling
item_ids:
- jasm:logic_nibbling
- jasm:memory_nibbling
- jasm:link_nibbling
- jasm:logic_byteling
- jasm:memory_byteling
- jasm:link_byteling
---

# Nibblings and Bytelings

<GameScene zoom="6" padding="2">
  <IsometricCamera yaw="0" pitch="15" />
  <Entity id="jasm:wild_bitling" data="{kind:1,stage:1}" x="0.5" z="0.5" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:2,stage:1}" x="1.6" z="0.5" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:3,stage:1}" x="2.7" z="0.5" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:1,stage:2}" x="0.5" z="1.7" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:2,stage:2}" x="1.6" z="1.7" rotationY="0" />
  <Entity id="jasm:wild_bitling" data="{kind:3,stage:2}" x="2.7" z="1.7" rotationY="0" />
</GameScene>

The grown-up stages of a [Bitling](bitlings.md). Each one works the [Chip Workshop](chip-workshop.md) better than the last.

## Nibbling

* Makes only its own chip type, and Advanced chips more often.
* Battery of 200,000 FE.
* Needs 300 chips of [training](../mechanics/training-bitlings.md) to grow up.

## Byteling

* Makes only its own type. A switch in the Workshop picks standard or Advanced chips.
* Battery of 400,000 FE.
* The only critter that can build a <ItemLink id="synapse_core" />.

## Recipes

<RecipeFor id="logic_nibbling" />
<RecipeFor id="memory_nibbling" />
<RecipeFor id="link_nibbling" />
<RecipeFor id="logic_byteling" />
<RecipeFor id="memory_byteling" />
<RecipeFor id="link_byteling" />
