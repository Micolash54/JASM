---
navigation:
  title: Redstone Upgrade
  parent: items/index.md
  position: 440
  icon: redstone_upgrade
item_ids:
- jasm:redstone_upgrade
---

# Redstone Upgrade

<ItemImage id="redstone_upgrade" scale="4" />

Lets redstone switch a machine on and off. Put it in one of the machine's upgrade slots. A machine takes one.

<br />
<br />
## Machines that take it

* Input, Output and Two-Way [item ports](item-ports.md), thin or block.
* [Access Ports](access-port.md), thin or block.
* The [Crystal Foundry](crystal-foundry.md).
* The [Chip Workshop](chip-workshop.md).
* The [Deployment Bay](deployment-bay.md) and the [Demolition Bay](demolition-bay.md).

<br />
<br />
## Modes

A redstone key shows up on the machine's screen once the upgrade is in. It picks one of three:

* Ignore redstone.
* Run while powered.
* Run while unpowered.

The bays have a fourth: work once for each redstone pulse.

<br />
<br />
## Good to know

* Take the upgrade out and the machine runs normally again.
* A paused machine keeps its progress. A Foundry keeps its crystal, a Workshop its half-made chips. The Workshop's critter still charges.
* A paused Access Port moves nothing to its machine or back, and crafting jobs wait for it.
* A port's mode controls both directions at once.
* All ports in the same block space get the same redstone signal. Each one follows its own mode. A port with no upgrade keeps running.

<br />
<br />
## Recipe

<RecipeFor id="redstone_upgrade" />
