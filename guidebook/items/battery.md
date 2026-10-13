---
navigation:
  title: Battery
  parent: items/index.md
  position: 330
  icon: battery
item_ids:
- jasm:battery
---

# Battery

<BlockImage id="battery" scale="3.5" />

A Battery stores FE. It connects to adjacent machines or to a JASM network via [Data Cables](data-cables.md). Multiple batteries connect with each other to form a multiblock, granting increasing amounts of storage.

<br />
<br />
## At a glance (Default values)

| | |
| --- | --- |
| Holds | 2,000,000 FE each block |
| Size bonus | 2% more FE for every battery in a multiblock |
| Largest multiblock size | 64 blocks |

<br />
<br />
## Joining up

* Batteries that touch each other join as one battery and share their power.
* Larger multiblock sizes grant increasing power storage bonuses.
* A block that would join a battery past the largest size can't be placed.

<br />
<br />
## Charging slot

* A battery has one charging slot. It takes any item that holds FE and charges it from the battery's power.
* A bigger battery charges faster, and never faster than it has power.

<br />
<br />
## Breaking

* A broken Battery keeps its charge in the item.
* Breaking a block trims the rest of the battery to its new, smaller capacity. Power above it is lost. The block you mined keeps its own share.

<br />
<br />
## Recipe

<RecipeFor id="battery" />
