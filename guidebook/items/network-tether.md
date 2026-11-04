---
navigation:
  title: Network Tether
  parent: items/index.md
  position: 365
  icon: network_tether
item_ids:
- jasm:network_tether
---

# Network Tether

<BlockImage id="network_tether" scale="4" />

Keeps every chunk its network touches loaded, even with nobody near: every cable and machine, and both ends of a [Relay Chatterbox](relay-chatterbox.md) link. A Nibbling or Byteling keeps watch at its desk.

<br />
<br />
## At a glance (Default values)

| | |
| --- | --- |
| Cost with 1 chunk | 81 FE/t |
| Cost with 9 chunks | 125 FE/t |
| Cost with 25 chunks | 405 FE/t |
| Chunks kept after the power runs out | 10 seconds |

<br />
<br />
## Using it

* Join it to a network with a [Data Cable](data-cables.md). It doesn't count toward the machine limit.
* The Operator slot takes any [Nibbling or Byteling](nibblings-and-bytelings.md). Without one, nothing is kept loaded.
* The operator's battery pays. The network tops it up through the cable. Each chunk held costs a little more than the one before.
* With several Tethers on one network, the one placed first keeps watch and the others stand by. One standing by takes over if it stops.

<br />
<br />
## Good to know

* The chunks stay loaded while you are away, and after a restart. Mobs spawn in them as they do near a player.
* When the battery runs flat, the chunks are let go a few seconds later. Take the operator out and they are let go at once.
* A chunk is only known once a cable or machine in it has been loaded with the Tether in place.

<br />
<br />
## Breaking it

A broken Tether keeps its operator inside the item. Its tooltip names it.

<br />
<br />
## Recipe

<RecipeFor id="network_tether" />
