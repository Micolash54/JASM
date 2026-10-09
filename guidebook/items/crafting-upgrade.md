---
navigation:
  title: Crafting Upgrade
  parent: items/index.md
  position: 442
  icon: crafting_upgrade
item_ids:
- jasm:crafting_upgrade
---

# Crafting Upgrade

<ItemImage id="crafting_upgrade" scale="4" />

A port with a Crafting Upgrade asks your [Crafting Deck](crafting-decks.md) to craft what it is sending out.

* Only Output and Input Output Ports take it.
* It crafts what the port's output [filter](../mechanics/type-filters.md) allows, when you have a [recipe card](recipe-cards.md) for it.
* It looks once a second and runs one job at a time.
* With a [Stock Upgrade](stock-upgrade.md) it only crafts what the blocks and your storage are short of. Without one it keeps crafting a stack at a time while the ingredients last.

<br />
<br />
## Recipe

<RecipeFor id="crafting_upgrade" />
