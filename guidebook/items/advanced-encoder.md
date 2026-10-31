---
navigation:
  title: Advanced Encoder
  parent: items/index.md
  position: 505
  icon: advanced_encoder
item_ids:
- jasm:advanced_encoder
---

# Advanced Encoder

<BlockImage id="advanced_encoder" scale="3.5" />

A [Network Encoder](encoding-terminal.md) that also picks which face of a machine each ingredient goes in through. For machines that only take some ingredients from the top, the bottom or one side.

Leave your machine's own side settings at their defaults, and let the recipe card's faces decide where each ingredient goes.

<br />
<br />
## Picking faces

1. Press the furnace key beside **Encode** for Processing.
2. Open the Faces window with the side key under Deck Link and Access.
3. Click a face key, then click the ingredient slots that go in through it. Right-click or Esc stops.
4. Press **Encode** as usual.

A slot shows its face as a coloured tint with a letter. The faces are Top, Bottom, North, South, East and West, the same whichever way the machine is turned. Default is the face the [Access Port](access-port.md) touches, as on any card.

The Faces window can be dragged anywhere and stays where you left it.

<br />
<br />
## Good to know

* Faces are for ingredients only. Results still come back through the port.
* A set goes in whole or not at all. If one face says no, nothing goes in through the others.
* With JEI's "+", ingredients that are the same item get a slot each, so each can have its own face.
* A machine counts even when the face the port touches takes nothing, as long as another face does.
* A card's tooltip names the face for each ingredient that doesn't use Default.
* The Network Encoder shows a card's faces and keeps them when it writes the card again, but can't change them.

<br />
<br />
## Recipe

<RecipeFor id="advanced_encoder" />
