---
navigation:
  title: Wafer filters
  parent: mechanics/index.md
  position: 60
  icon: type_wafer_8
---

# Wafer filters

<ItemImage id="type_wafer_8" scale="4" />

Right-click a wafer in the [Deck](../items/decks.md) to choose what it accepts. A wafer with no filters accepts everything.

<br />
<br />
## Rules

Each filter is a row. A row can match a **Material**, a **Tag** or a **Mod ID**, and it can **Allow** or **Deny**. A Material row matches anything with that ID, so `mekanism:hydrogen` catches the fluid and the chemical alike.

* The first enabled row that matches decides.
* An item gets in only if that row says Allow.
* Rows can be switched off, moved up or down, or removed.
* With filters on, items that match no row stay out.

<br />
<br />
## Picking what to match

Click the slot with an item to fill in its ID. Shift-click to use what it holds instead, like the water in a bucket. And you can drag things straight in from JEI, chemicals too.

<br />
<br />
## Filling order

Wafers fill from left to right. When several kinds arrive together, the higher Allow rows take space first.

<br />
<br />
## Empty out

The same window has an **Empty out** button. It moves everything on that wafer onto the Deck's other wafers, and each item costs the usual charge.

Nothing is ever destroyed this way. What finds no room stays where it is.
