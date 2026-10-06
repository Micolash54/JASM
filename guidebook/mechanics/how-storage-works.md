---
navigation:
  title: How storage works
  parent: mechanics/index.md
  position: 50
  icon: capacity_wafer_1k
---

# How storage works

<ItemImage id="capacity_wafer_1k" scale="4" />

Your items don't sit inside a wafer. The wafer is a key, and the items live in the world's records under that wafer's number. That is why a wafer is safe to carry, and why it can be rebuilt.

## The pieces

1. **Wafers** hold the items. See [Capacity](../items/capacity-wafers.md) and [Type](../items/type-wafers.md) Wafers.
2. **Decks** carry the wafers and give you a screen to use them. See [Decks](../items/decks.md).
3. **Archives** keep backups, so a lost wafer can be recovered. See [Archives](../items/archives.md).

## Safe by design

* A copied wafer stops working. It is wiped blank, and the original is untouched.
* A wafer that is older than the records say, or from another world, is locked. It keeps its contents and does nothing until it can be matched up again.
* A crash never duplicates items.
* Items from a mod you removed are kept until the mod comes back.

## What a wafer won't take

* Items that hold other items, like a full shulker box.
* Items that carry too much data to save.
* Items that can't be saved at all.

The Deck's screen says why when it turns something away.
