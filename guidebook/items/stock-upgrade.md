---
navigation:
  title: Stock Upgrade
  parent: items/index.md
  position: 444
  icon: stock_upgrade
item_ids:
- jasm:stock_upgrade
---

# Stock Upgrade

<ItemImage id="stock_upgrade" scale="4" />

Lets an Output Port keep a block stocked instead of filling it up.

* Only Output and Input Output Ports take it.
* Each output [filter](../mechanics/type-filters.md) row gets a **Stock** number. The port tops the block up to that number and stops there. Counted over everything the row matches, in items, or mB for fluids.
* With a [Crafting Upgrade](crafting-upgrade.md) as well, the port crafts what the blocks and your storage are short of.

<br />
<br />
## Recipe

<RecipeFor id="stock_upgrade" />
