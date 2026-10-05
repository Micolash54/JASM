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

<ItemImage id="input_output_port" scale="8" />

Ports mount on a [Data Cable](data-cables.md) and move items and fluids between the network and the block next to them.

| Port | Does |
| --- | --- |
| Input Port | pulls into a linked [Deck](decks.md) |
| Output Port | pushes out of the Deck |
| Input Output Port | both, with output first |

## Filters

Each direction has an ordered list of filters: Item, Fluid, Tag and Mod ID. Empty input filters accept everything. Empty output filters send nothing.

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

## More slots

* A [Power Upgrade](power-upgrade.md) makes the port supply FE to the machine it faces.
* A [Redstone Upgrade](redstone-upgrade.md) makes it follow a redstone signal.

## Recipes

An Input or Output Port is an Access Port with a hopper or dropper, a Link Chip and a dye.

<RecipeFor id="input_port" />
<RecipeFor id="output_port" />
<RecipeFor id="input_output_port" />
