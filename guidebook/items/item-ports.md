---
navigation:
  title: Item ports
  parent: items/index.md
  position: 410
  icon: input_port
item_ids:
- jasm:input_port
- jasm:output_port
- jasm:input_output_port
- jasm:full_input_port
- jasm:full_output_port
- jasm:full_input_output_port
---

# Item ports

<Row alignItems="center">
<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <ImportStructure src="../assets/input_port.snbt" />
</GameScene>
<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <ImportStructure src="../assets/output_port.snbt" />
</GameScene>
<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <ImportStructure src="../assets/input_output_port.snbt" />
</GameScene>
</Row>

Ports move items and fluids between the network and the blocks next to them.

| Port | Does |
| --- | --- |
| Input Port | pulls into a linked [Deck](decks.md) |
| Output Port | pushes out of the Deck |
| Input Output Port | both, with output first |

<br />
<br />
## Two shapes

<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <Block id="jasm:full_input_port" x="0" y="0" z="0" p:east="machine" p:west="link" />
  <Block id="minecraft:chest" x="1" y="0" z="0" />
  <Block id="jasm:data_cable" x="-1" y="0" z="0" />
</GameScene>

* **Thin:** sits on a [Data Cable](data-cables.md) face and works on the block in front of it.
* **Block:** joins the network like a machine and works on every block it touches, with one set of filters and one speed shared between them.
* On a JASM machine, a block port only moves what that face's I/O setting allows.
* A block Input or Input Output Port also takes items pushed into it by hoppers, pipes or machines.

Convert one shape into the other in a crafting grid.

<br />
<br />
## Filters

Each direction has its own [filter](../mechanics/type-filters.md). An empty output filter sends nothing.

<br />
<br />
## Speed

Ports run twice a second while they are busy, and check once a second after five idle seconds. Four slots take [Speed Upgrades](speed-upgrade.md).

| Upgrades | Items per operation | Items per second |
| --- | --- | --- |
| 0 | 1 | 2 |
| 1 | 4 | 8 |
| 2 | 16 | 32 |
| 3 | 48 | 96 |
| 4 | 96 | 192 |

Fluids share that speed with items.

<br />
<br />
## More slots

* A [Power Upgrade](power-upgrade.md) makes the port supply FE to the machine it faces.
* A [Redstone Upgrade](redstone-upgrade.md) makes it follow a redstone signal.

<br />
<br />
## Recipes

An Input or Output Port is an Access Port with a hopper or dropper, a Link Chip and a dye.

<Column>
  <Row>
    <RecipeFor id="input_port" />
    <RecipeFor id="output_port" />
  </Row>
  <Row>
    <RecipeFor id="input_output_port" />
  </Row>
  <Row>
    <RecipeFor id="full_input_port" />
    <RecipeFor id="full_output_port" />
    <RecipeFor id="full_input_output_port" />
  </Row>
</Column>
