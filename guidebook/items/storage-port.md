---
navigation:
  title: Storage Port
  parent: items/index.md
  position: 420
  icon: storage_port
item_ids:
- jasm:storage_port
---

# Storage Port

<Row alignItems="center">
<ItemImage id="storage_port" scale="5" />
<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <ImportStructure src="../assets/storage_port.snbt" />
</GameScene>
</Row>

A Storage Port lends the chest, tank or cauldron in front of it to the whole network. Everything inside shows up in a linked [Deck](decks.md) next to the wafers, marked with a small chest. Take it out or put it in like wafer storage.

## Settings

* **Filter:** Item, Fluid, Tag and Mod ID.
* **Access:** read and write, read only or write only.
* **Priority:** higher priority takes things in first and the lowest is emptied first. Wafers go first on a tie.

Anyone allowed on the network can use it.

## Recipe

A Storage Port is an Access Port with a chest, a Link Chip and purple dye.

<RecipeFor id="storage_port" />
