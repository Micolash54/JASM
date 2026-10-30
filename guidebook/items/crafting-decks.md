---
navigation:
  title: Crafting Decks
  parent: items/index.md
  position: 250
  icon: elite_crafting_deck
item_ids:
- jasm:advanced_crafting_deck
- jasm:elite_crafting_deck
- jasm:ultimate_crafting_deck
---

# Crafting Decks

<Row>
  <ItemImage id="advanced_crafting_deck" scale="4" />
  <ItemImage id="elite_crafting_deck" scale="4" />
  <ItemImage id="ultimate_crafting_deck" scale="4" />
</Row>

A [Deck](decks.md) with a 3x3 crafting grid. Ingredients come straight from its wafers, and the grid refills itself after every craft.

* Shift-click the result to craft up to one stack of it into your inventory, or as many as you have materials and room for if that is fewer.
* When more than one recipe fits the grid, a key above the result opens a row of every result: click one to make it the result. The pick stays until the grid fits something else.
* With JEI installed, its "+" fills the grid from your wafers and inventory, and fills what it can when something is missing. It also picks JEI's own recipe. Missing ingredients the network can craft show in blue; Ctrl-click asks for them.
* When you close it, the grid goes back into the wafers.
* It is also how you ask for [autocrafting](../mechanics/autocrafting.md), and where you set [crafting rules](../mechanics/crafting-rules.md).
* Only Advanced, Elite and Ultimate Decks come in this form.

<br />
<br />
## Deck as bucket

* Right-click a fluid on the Items tab with nothing in hand to pick it, and again to unpick it. A small bucket marks the picked fluid in the grid, and the Deck shows a drop of it in its corner.
* Sneak + right-click a block to place a bucket of the picked fluid from the Deck's [fluid wafers](fluid-capacity-wafers.md) or its network. It follows the vanilla bucket's rules: water fills waterloggable blocks and boils away in the Nether.
* Sneak + right-click a fluid source to collect a bucket of it. No pick is needed.

<br />
<br />
## Getting one

Craft a Deck of the same tier, or upgrade from the Crafting Deck below it. Either way it keeps its wafers, its charge and whatever is in its grid.

A new Crafting Deck links itself to the network of the [Network Encoder](encoding-terminal.md) you used last.

<br />
<br />
## Recipes

<Column>
  <Row>
    <RecipeFor id="advanced_crafting_deck" />
    <RecipesFor id="elite_crafting_deck" />
  </Row>
  <Row>
    <RecipesFor id="ultimate_crafting_deck" />
  </Row>
</Column>
