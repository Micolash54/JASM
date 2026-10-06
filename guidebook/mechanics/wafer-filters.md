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

## Rules

Each filter is a row. A row can match an **Item**, a **Tag** or a **Mod ID** (a **Fluid** on a fluid wafer), and it can **Allow** or **Deny**.

* The first enabled row that matches decides.
* An item gets in only if that row says Allow.
* Rows can be switched off, moved up or down, or removed.
* With filters on, items that match no row stay out.

## Filling order

Wafers fill from left to right. When several kinds arrive together, the higher Allow rows take space first.

## Empty out

The same window has an **Empty out** button. It moves everything on that wafer onto the Deck's other wafers, and each item costs the usual charge.

Nothing is ever destroyed this way. What finds no room stays where it is.
