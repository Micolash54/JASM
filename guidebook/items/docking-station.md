---
navigation:
  title: Docking Station
  parent: items/index.md
  position: 422
  icon: docking_station
item_ids:
- jasm:docking_station
---

# Docking Station

<BlockImage id="docking_station" scale="3.5" />

A Docking Station holds one [Deck](decks.md) for its network, joined by a [Data Cable](data-cables.md). While the Deck sits in it, ports, crafting jobs, rules and Deck to Deck deliveries use that Deck, whether its owner is online or not.

<br />
<br />
## Using it

* Put in a Deck linked to this network: click or shift-click it into the slot. Only Decks go in.
* What the docked Deck moves is paid from its own charge, as always. The station charges it from the network's power, so it doubles as a Deck charger. Without charge, the Deck stops until the station fills it again.
* The station costs nothing to run. It counts toward the [machine limit](network-brain.md).
* Deliveries sent to its owner go straight onto its wafers. What doesn't fit waits on its way until there is room.
* Rules that put their results in your inventory wait until you carry the Deck again.

<br />
<br />
## Lock

* **Locked:** only the Deck's owner and the network's owner can take it out. A Deck starts locked.
* **Unlocked:** anyone trusted on the network can take it out.
* Only the Deck's owner and the network's owner can lock or unlock it.
* Breaking the station follows the same rule. The broken station keeps the Deck inside, locked or not, until it is placed again. Its tooltip says whose Deck it holds.
* Explosions don't break it. Hoppers, pipes and ports can't take the Deck out.

<br />
<br />
## Recipe

<RecipeFor id="docking_station" />
