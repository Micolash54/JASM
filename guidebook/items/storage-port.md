---
navigation:
  title: Storage Port
  parent: items/index.md
  position: 420
  icon: storage_port
item_ids:
- jasm:storage_port
- jasm:full_storage_port
---

# Storage Port

<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <ImportStructure src="../assets/storage_port.snbt" />
</GameScene>

A Storage Port lends the chest, tank or cauldron in front of it to the whole network. Everything inside shows up in a linked [Deck](decks.md) next to the wafers, marked with a small chest. Take it out or put it in like wafer storage.

<br />
<br />
## Two shapes

* **Thin:** sits on a [Data Cable](data-cables.md) face and lends the block in front of it.
* **Block:** joins the network like a machine and lends every chest, tank or cauldron it touches, all with the same settings.

Convert one shape into the other in a crafting grid.

<br />
<br />
## Settings

* **Filter:** see [Type Filters](../mechanics/type-filters.md).
* **Access:** read and write, read only or write only.
* **Priority:** higher priority takes things in first and the lowest is emptied first. Wafers go first on a tie.

Anyone allowed on the network can use it.

<br />
<br />
## Recipe

A Storage Port is an Access Port with a chest, a Link Chip and purple dye.

<RecipeFor id="storage_port" />
<RecipeFor id="full_storage_port" />
