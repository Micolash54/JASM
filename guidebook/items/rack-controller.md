---
navigation:
  title: Rack Controller
  parent: items/index.md
  position: 525
  icon: rack_controller
item_ids:
- jasm:rack_controller
---

# Rack Controller

<BlockImage id="rack_controller" scale="3.5" />

A Rack Controller shows every [Recipe Rack](recipe-rack.md) on its network in one panel. It joins the network like any other crafting block.

<br />
<br />
## At a glance (Default values)

| | |
| --- | --- |
| Uses | 2 FE a tick |

<br />
<br />
## The panel

* Each rack has a section with its name and its 16 slots. A rack with no name shows as Recipe Rack. Hover a name to see where the rack stands.
* Click a card to take it. Click an empty slot to put the card you hold in. Shift-click a card to send it to your inventory, or a written card in your inventory to put it in the first free slot.
* The search box finds cards by what they make. Racks with no match fold up to their name.
* Racks in other dimensions joined by a far link are listed too. Racks in unloaded chunks are not.
* You only see the racks you may use.

<br />
<br />
## Power

* Without power the panel lists no racks. The controller counts toward the [machine limit](network-brain.md).

<br />
<br />
## Recipe

<RecipeFor id="rack_controller" />
