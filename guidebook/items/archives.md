---
navigation:
  title: Archives
  parent: items/index.md
  position: 270
  icon: advanced_archive
item_ids:
- jasm:basic_archive
- jasm:advanced_archive
- jasm:ultimate_archive
---

# Archives

<Row>
  <BlockImage id="basic_archive" scale="3.5" />
  <BlockImage id="advanced_archive" scale="3.5" />
  <BlockImage id="ultimate_archive" scale="3.5" />
</Row>

An Archive keeps a backup of your wafers. If one is ever lost or destroyed, the Archive rebuilds it onto a blank wafer, items and all.

| Tier | Wafers it protects | Charge | Uses |
| --- | --- | --- | --- |
| Basic | 3 | 50,000 FE | 5 FE a tick |
| Advanced | 6 | 100,000 FE | 10 FE a tick |
| Ultimate | 12 | 200,000 FE | 20 FE a tick |

An Archive needs power to link or recover, so give it a cable or a generator next to it. Linking a wafer costs 1,000 FE and a recovery 10,000 FE by default.

<br />
<br />
## Using one

* Put a wafer in the link slot and press **Link**.
* Shift-right-click with the linked Deck to [back up](../mechanics/backups-and-recovery.md) all its wafers in slot order.
* To recover a lost wafer, put a blank one of the same kind in the recover slot and press **Recover**.

The blank wafer has to be the same size or bigger than the one it replaces.

<br />
<br />
## Upgrading

The old Archive is used up. The new one keeps its links, its owner and its charge.

<br />
<br />
## Recipes

<Column>
  <Row>
    <RecipeFor id="basic_archive" />
    <RecipeFor id="advanced_archive" />
  </Row>
  <Row>
    <RecipeFor id="ultimate_archive" />
  </Row>
</Column>
