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

<Row>
  <GameScene zoom="6" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Entity id="jasm:wild_bitling" data="{kind:0,stage:0}" x="0.5" z="0.5" rotationY="0" />
  </GameScene>
  <GameScene zoom="6" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Entity id="jasm:wild_bitling" data="{kind:1,stage:0}" x="0.5" z="0.5" rotationY="0" />
  </GameScene>
  <GameScene zoom="6" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Entity id="jasm:wild_bitling" data="{kind:2,stage:0}" x="0.5" z="0.5" rotationY="0" />
  </GameScene>
  <GameScene zoom="6" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Entity id="jasm:wild_bitling" data="{kind:3,stage:0}" x="0.5" z="0.5" rotationY="0" />
  </GameScene>
</Row>

Bitlings are small robot helpers that work the [Chip Workshop](chip-workshop.md). Without one, a Workshop does nothing.

<br />
<br />
## Wild Bitlings

* Hand one a typed chip to get a Basic Bitling item.
* Hand one a Data Crystal and it follows you for a while.
* Hand one a diamond and it leaves a Block of Amethyst, unless diamond trades are disabled in the settings.
* Knocked out, it leaves one or two <ItemLink id="crystal_dust" />.

<br />
<br />
## Basic Bitling

You can't craft one. [Find a wild Bitling](../mechanics/befriending-bitlings.md) and hand it a chip.

* Makes Logic, Memory and Link Chips evenly, and rarely an Advanced one.
* Battery of 50,000 FE.
* The Network Brain recipe needs one.

<br />
<br />
## Typed Bitlings

Surround a Basic Bitling with 8 chips of one type in a crafting grid to get a Logic, Memory or Link Bitling.

* Mostly makes its own type, sometimes Advanced.
* Battery of 100,000 FE.
* [Trains](../mechanics/training-bitlings.md) on every chip it makes, then grows into a Nibbling.

<br />
<br />
## Recipes

<Column>
  <Row>
    <RecipeFor id="logic_bitling" />
    <RecipeFor id="memory_bitling" />
  </Row>
  <Row>
    <RecipeFor id="link_bitling" />
  </Row>
</Column>
