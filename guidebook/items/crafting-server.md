---
navigation:
  title: Crafting Server
  parent: items/index.md
  position: 530
  icon: crafting_server
item_ids:
- jasm:crafting_server
---

# Crafting Server

<BlockImage id="crafting_server" scale="3.5" />

The Crafting Server does the work. It runs one crafting job at a time. [Processors](processors.md) decide how many crafts run at once, and [Storage Modules](storage-modules.md) decide how big a job fits.

| At a glance | |
| --- | --- |
| Slots | 4 for Processors, 4 for Storage Modules |
| Uses | 20 FE a tick, plus its Processors |
| Each craft takes | half a second, whatever the Processor |
| Counts toward | the [machine limit](network-brain.md) |

<br />
<br />
## Good to know

* Its parts are locked in while it works.
* Broken, it spills whatever its job held.
* With several servers on a network, a job goes to one that fits and is free.
* The screen shows what the job is waiting for: power, a card, a machine, space or the Deck.
* A job can be cancelled. What is left comes back.

<br />
<br />
## How a craft runs

See [autocrafting](../mechanics/autocrafting.md).

<br />
<br />
## Recipe

<RecipeFor id="crafting_server" />
