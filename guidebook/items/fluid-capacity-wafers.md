---
navigation:
  title: Fluid Capacity Wafers
  parent: items/index.md
  position: 220
  icon: fluid_capacity_wafer_4k
item_ids:
- jasm:fluid_capacity_wafer_basic
- jasm:fluid_capacity_wafer_1k
- jasm:fluid_capacity_wafer_4k
- jasm:fluid_capacity_wafer_16k
- jasm:fluid_capacity_wafer_64k
- jasm:fluid_capacity_wafer_256k
- jasm:fluid_capacity_wafer_1m
---

# Fluid Capacity Wafers

<Row>
  <ItemImage id="fluid_capacity_wafer_basic" scale="4" />
  <ItemImage id="fluid_capacity_wafer_1k" scale="3.5" />
  <ItemImage id="fluid_capacity_wafer_4k" scale="3.5" />
  <ItemImage id="fluid_capacity_wafer_16k" scale="3.5" />
  <ItemImage id="fluid_capacity_wafer_64k" scale="3.5" />
  <ItemImage id="fluid_capacity_wafer_256k" scale="3.5" />
  <ItemImage id="fluid_capacity_wafer_1m" scale="3.5" />
</Row>

The same idea as a [Capacity Wafer](capacity-wafers.md), for fluids. It holds any mix of fluids, counted in buckets: one bucket takes the room of one item.

| Wafer | Holds, in buckets | Needs |
| --- | --- | --- |
| Basic | 256 | vanilla items only |
| 1K | 1,024 | Memory Chip |
| 4K | 4,096 | Memory Chip |
| 16K | 16,384 | Memory Chip and Link Chip |
| 64K | 65,536 | Advanced Memory Chip |
| 256K | 262,144 | Synapse Core |
| 1M | 1,048,576 | Synapse Core |

Every size is made from three wafers of the size below. They are used up, and the new wafer holds all of their fluid and starts unlinked. The 256K and 1M sizes need [Synapse Cores](synapse-core.md).

<br />
<br />
## Using them

* They go in any [Deck](decks.md) slot, next to item wafers.
* The Deck's grid shows items and fluids together. A button shows only one kind.
* Right-click the grid with a full bucket, or shift-right-click it in your inventory, to pour it in.
* Click a fluid with an empty bucket to fill it.
* Input and Output Ports move fluids too. See [item ports](item-ports.md).

<br />
<br />
## Recipes

<Column>
  <Row>
    <RecipeFor id="fluid_capacity_wafer_basic" />
    <RecipeFor id="fluid_capacity_wafer_1k" />
  </Row>
  <Row>
    <RecipeFor id="fluid_capacity_wafer_4k" />
    <RecipeFor id="fluid_capacity_wafer_16k" />
  </Row>
  <Row>
    <RecipeFor id="fluid_capacity_wafer_64k" />
    <RecipeFor id="fluid_capacity_wafer_256k" />
  </Row>
  <Row>
    <RecipeFor id="fluid_capacity_wafer_1m" />
  </Row>
</Column>
