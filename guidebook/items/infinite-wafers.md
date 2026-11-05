---
navigation:
  title: Infinite Wafers
  parent: items/index.md
  position: 235
  icon: infinite_water_wafer
item_ids:
- jasm:infinite_water_wafer
- jasm:infinite_lava_wafer
---

# Infinite Wafers

<Row>
  <ItemImage id="infinite_water_wafer" scale="4" />
  <ItemImage id="infinite_lava_wafer" scale="4" />
</Row>

An Infinite Water Wafer or Infinite Lava Wafer goes in a [Deck](decks.md) like any fluid wafer and holds only its own fluid, without end.

<br />
<br />
## At a glance (Default values)

| | |
| --- | --- |
| Amount shown | 1,000,000,000 buckets |
| Power per bucket moved | 8 FE |

<br />
<br />
## Using one

* The Deck always shows the same amount of its fluid. Taking some out never lowers it.
* Buckets, tanks, ports, pipes and crafting jobs can all take from it.
* Water or lava put into the Deck goes to its infinite wafer first and is deleted. This costs charge like any other move.
* Water or lava already on other wafers, or in tanks lent by a [Storage Port](storage-port.md), stays where it is.
* It has no filters and needs no Archive backup.

<br />
<br />
## Pull all

Right-click an infinite wafer in the Deck's wafer panel to open its small window.

* **Pull all** deletes all of its fluid stored on the Deck's other wafers. It costs charge like moving that fluid, 8 FE per bucket.
* Without charge for all of it, it pulls as many whole buckets as the charge pays for and says **Out of charge**.
* The emptied wafers keep their settings and name.
* Tanks lent by a [Storage Port](storage-port.md) are left alone.
* The window shows how much was pulled.

<br />
<br />
## Recipes

<Column>
  <Row>
    <RecipeFor id="infinite_water_wafer" />
  </Row>
  <Row>
    <RecipeFor id="infinite_lava_wafer" />
  </Row>
</Column>
