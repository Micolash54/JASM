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

Lets redstone switch a port on and off.

Install it in an Input, Output or Input Output Port. The mode button then picks one of three:

* Ignore redstone.
* Run while powered.
* Run while unpowered.

The mode controls both directions at once. Take the upgrade out and the port runs normally again.

<br />
<br />
## Good to know

* All ports in the same block space get the same redstone signal. Each one follows its own mode. A port with no upgrade keeps running.
* The [bays](deployment-bay.md) can use it to work once for each redstone pulse.
* Access Ports don't take this upgrade.

<br />
<br />
## Recipe

<RecipeFor id="redstone_upgrade" />
