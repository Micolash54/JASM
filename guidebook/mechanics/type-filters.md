---
navigation:
  title: Type Filters
  parent: mechanics/index.md
  position: 55
  icon: input_output_port
---

# Type Filters

Wafers, ports and bays share the same filter. It decides which items and fluids get through.

<br />
<br />
## Rows

A filter is a list of rows. Each row has a type and a mode.

| Type | Matches |
| --- | --- |
| Material | One item or fluid by its ID, such as `minecraft:stone` |
| Tag | Everything in a tag, such as `c:ores` |
| Mod ID | Everything from one mod, such as `minecraft` |

* **Allow** lets matches through, **Deny** keeps them out.
* The first enabled row that matches decides. The arrows change the order.
* Rows can be switched off without removing them.
* Item components, such as enchantments or names, are ignored.

<br />
<br />
## Unmatched items

* With at least one enabled Allow row, anything that matches no row is refused.
* With only Deny rows, everything else gets through.
* An empty filter lets everything through, except on an [Output Port](../items/item-ports.md), which then sends nothing.

<br />
<br />
## Adding a row

Click the slot with an item to fill in its ID. Shift-click to use what it holds instead, such as the water in a bucket. Items and fluids can also be dragged in from JEI.

In Tag mode, an item with several tags shows a list to pick from.
