---
navigation:
  title: Power Upgrade
  parent: items/index.md
  position: 450
  icon: power_upgrade
item_ids:
- jasm:power_upgrade
---

# Power Upgrade

<ItemImage id="power_upgrade" scale="4" />

A port with a Power Upgrade passes FE from your network to the machine it faces. It is how a modded machine gets power from your network.

* Every port has a slot for it: [Access Ports](access-port.md) and [item ports](item-ports.md).
* A full block Access Port powers only the machines of the type it works with, not every block touching it.
* The port stores no power. Power goes straight through to the machine, as much as it takes, with no limit per tick.
* When there isn't enough for every machine, each machine gets an equal share.
* The port's screen shows how many FE a tick it is passing on.
* The power comes from the network: [generators](combustion-generators.md), [Batteries](battery.md) or other mods through a [Power Interface](power-acceptor.md).

<br />
<br />
## Recipe

<RecipeFor id="power_upgrade" />
