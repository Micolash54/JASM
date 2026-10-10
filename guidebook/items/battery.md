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

A Battery stores FE. It keeps the machines it touches, and the machines on any [Data Cable](data-cables.md) network it touches, topped up, so a [generator](combustion-generators.md) can run dry for a while and the machines keep going.

| At a glance | |
| --- | --- |
| Holds | 2,000,000 FE each block |
| Size bonus | 2% more room in every block for each block in a joined battery, by default |
| Largest | 64 blocks by default |

<br />
<br />
## Good to know

* Batteries that touch each other are one battery and share their power. Its screen shows the charge, the blocks and the power going in and out in FE/t.
* A [generator](combustion-generators.md) or a [Power Acceptor](power-acceptor.md) charges it.
* A Power Acceptor set to **Output only** gives a Battery's power to other mods' blocks.
* A block that would join a battery past the largest size can't be placed. A bigger battery keeps working and keeps its power.
* Settings allow 1–256 blocks and a 0–100% capacity bonus per block. A lone block has no bonus. Lowering the size limit keeps existing batteries together; lowering the bonus keeps their stored power, even above the new capacity.
* A broken Battery keeps its charge in the item.

<br />
<br />
## Recipe

<RecipeFor id="battery" />
