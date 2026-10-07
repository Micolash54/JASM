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

Ports mount on a [Data Cable](data-cables.md) and move items and fluids between the network and the block next to them. Chemicals and other mods' materials move too, into and out of the tanks behind a [Storage Port](storage-port.md).

| Port | Does |
| --- | --- |
| Input Port | pulls into a linked [Deck](decks.md) |
| Output Port | pushes out of the Deck |
| Input Output Port | both, with output first |

<br />
<br />
## Filters

Each direction has an ordered list of filters: Material, Tag and Mod ID. Empty input filters accept everything. Empty output filters send nothing.

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
</Column>
