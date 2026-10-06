---
navigation:
  title: Autocrafting
  parent: mechanics/index.md
  position: 110
  icon: crafting_server
---

# Autocrafting

<BlockImage id="crafting_server" scale="3.5" />

Ask for anything from your Crafting Deck, and the network crafts what it needs first. Here is the chain, start to finish.

## What you need

1. A [Crafting Deck](../items/crafting-decks.md), paired at an [Encoding Terminal](../items/encoding-terminal.md).
2. [Recipe Cards](../items/recipe-cards.md) for the recipes, kept in a [Recipe Rack](../items/recipe-rack.md).
3. A [Crafting Server](../items/crafting-server.md) with a Processor and a Storage Module.
4. A [Data Cable](../items/data-cables.md) network that joins them, with power.

For a machine instead of a crafting table, add an [Access Port](../items/access-port.md).

## Asking for something

Open the Craft tab on the Deck, pick an item and an amount, and press **Craft**. The Deck works out the steps and shows:

* **Makes:** what you get.
* **Crafts first:** the steps in between, as a list or a tree.
* **Missing:** anything it can't find.

A hammer in the Items tab marks items with a known recipe, even when some are already stored.

## While it runs

* Requested items come back as each batch finishes.
* Items made along the way stay with the job until it ends.
* A job list on the Deck shows every job. Click one to open its server from anywhere.
* Cancel a job and what is left comes back.

## When it says no

The Deck tells you why:

* Missing ingredients.
* No Recipe Card makes that.
* No server with a Processor, or every fitting server is busy.
* The job is too big for the Storage Modules.
* The network is full. Add a [Network Brain](../items/network-brain.md).

The biggest request is 100,000 items.

See also [crafting rules](crafting-rules.md).
