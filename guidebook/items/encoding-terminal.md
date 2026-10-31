---
navigation:
  title: Network Encoder
  parent: items/index.md
  position: 500
  icon: encoding_terminal
item_ids:
- jasm:encoding_terminal
---

# Network Encoder

<BlockImage id="encoding_terminal" scale="3.5" />

The Network Encoder writes recipes onto [Recipe Cards](recipe-cards.md). It is also where a Deck gets paired with a network, which unlocks autocrafting, ports and the Network tab.

<br />
<br />
## Writing a recipe

1. Click items into the grid. They are only examples and are never used up.
2. Put an empty or filled Recipe Card in.
3. Press **Encode**. Take the finished card out first, or it can't write the next one.

When more than one recipe fits the grid, the key above the result picks which one the card gets. JEI's "+" picks its own recipe.

Two keys beside **Encode** set the kind of card: the crafting table for **Crafting**, the furnace for **Processing**. Crafting is for anything with a crafting grid.

For a machine, press Processing and fill the right-hand column with what the machine gives back. The card names no machine: pick the recipe in the screen of the [Access Port](access-port.md) that should make it. Scroll over a slot to change its amount, or middle-click it and type the amount (fluids in buckets, up to three decimals: 1.25 is 1,250 mB). Right-click with a full bucket or tank to set a fluid.

For a machine that only takes some ingredients through its top, bottom or one side, use the [Advanced Encoder](advanced-encoder.md).

The Deck tab shows what is on your Deck. Click an item there for a copy to put in the grid. Nothing leaves the Deck.

<br />
<br />
## Pairing a Deck

Open the Deck Link window with the side key. Put a [Deck](decks.md) in its left slot and take it from the right. Pairing another Deck replaces that player's previous one.

Placing the first Network Encoder on a network of your own pairs a Deck you carry, unless it is already paired somewhere.

<br />
<br />
## Who may use it

The Access window lets the owner trust other players by name, up to 32. Trusted players share recipes but use their own stored items.

| At a glance | |
| --- | --- |
| Uses | 5 FE a tick |
| Counts toward | the [machine limit](network-brain.md) |

<br />
<br />
## Recipe

<RecipeFor id="encoding_terminal" />
