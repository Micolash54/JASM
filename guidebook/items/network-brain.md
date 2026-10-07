---
navigation:
  title: Network Brain
  parent: items/index.md
  position: 340
  icon: network_brain
item_ids:
- jasm:network_brain
---

# Network Brain

<BlockImage id="network_brain" scale="3.5" />

A network holds only <Rule name="brain.withoutBrain" /> machines on its own. A Network Brain lifts that to <Rule name="brain.machines.0" />, and with <ItemLink id="network_chamber" />s round it, a lot more.

| Brain | Machines | Uses each tick | Holds |
| --- | --- | --- | --- |
| No brain | <Rule name="brain.withoutBrain" /> | | |
| Lone brain | <Rule name="brain.machines.0" /> | 8 FE | 50,000 FE |
| 1 floor | <Rule name="brain.machines.1" /> | 24 FE | 145,000 FE |
| 2 floors | <Rule name="brain.machines.2" /> | 52 FE | 310,000 FE |
| 3 floors | <Rule name="brain.machines.3" /> | 110 FE | 660,000 FE |
| 4 floors | <Rule name="brain.machines.4" /> | 240 FE | 1,450,000 FE |
| 5 floors | <Rule name="brain.machines.5" /> | 510 FE | 3,050,000 FE |
| 6 floors | <Rule name="brain.machines.6" /> | 1,100 FE | 6,600,000 FE |
| 7 floors | <Rule name="brain.machines.7" /> | 2,300 FE | 13,800,000 FE |
| 8 floors | <Rule name="brain.machines.8" /> | 5,000 FE | 30,000,000 FE |

<br />
<br />
## The rules

* Add a machine past the limit and the whole network stops, until you remove one or add a brain.
* A brain uses power every tick, and a taller tower uses more. If it runs out, the network falls back to <Rule name="brain.withoutBrain" /> machines.
* A whole tower shares one pool of power. A cable on any of its floors or chambers fills it, and its screen shows the pool and what the tower uses each tick.
* Mined, a brain keeps its charge.
* Several brains on one network don't add up. Only the best one leads, the one with the most floors, then the one placed first.

The machines' screens, Jade and a linked Deck's Network tab say when a network is full. See [what counts as a machine](../mechanics/networks.md).

<br />
<br />
## Floors and towers

Put 8 Network Chambers round a brain on the same layer to make a floor. Stack floors into a tower. All of that is on the [Network Chamber](network-chamber.md) page.

<br />
<br />
## Recipe

The recipe needs a Basic Bitling. [Befriend a wild one](../mechanics/befriending-bitlings.md) first.

<RecipeFor id="network_brain" />
