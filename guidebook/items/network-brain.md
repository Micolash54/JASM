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

A network holds only 4 machines on its own. A Network Brain lifts that to 12, and with <ItemLink id="network_chamber" />s round it, a lot more.

| Brain | Machines |
| --- | --- |
| No brain | 4 |
| Lone brain | 12 |
| 1 floor | 24 |
| 2 floors | 36 |
| 3 floors | 48 |
| Each further floor | 12 more |

## The rules

* Add a machine past the limit and the whole network stops, until you remove one or add a brain.
* A brain uses 8 FE every tick. If it runs out of power, the network falls back to 4 machines.
* Mined, a brain keeps its charge.
* Several brains on one network don't add up. Only the best one leads, the one with the most floors, then the one placed first.

The machines' screens, Jade and a linked Deck's Network tab say when a network is full. See [what counts as a machine](../mechanics/networks.md).

## Floors and towers

Put 8 Network Chambers round a brain on the same layer to make a floor. Stack floors into a tower. All of that is on the [Network Chamber](network-chamber.md) page.

## Recipe

The recipe needs a Basic Bitling. [Befriend a wild one](../mechanics/befriending-bitlings.md) first.

<RecipeFor id="network_brain" />
