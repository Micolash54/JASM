---
navigation:
  title: Demolition Bay
  parent: items/index.md
  position: 470
  icon: demolition_bay
item_ids:
- jasm:demolition_bay
---

# Demolition Bay

<WhenOff feature="bays">

**Turned off on this world.** Placed bays keep their contents but don't work.

</WhenOff>

<BlockImage id="demolition_bay" scale="3.5" />

A Demolition Bay breaks the block in front of it into its own grid, scoops up fluids and picks up items that land on its face.

Enchant it like a pickaxe for Fortune, Silk Touch and Efficiency.

| At a glance | |
| --- | --- |
| Speed | 2 seconds, down to half a second with 3 [Speed Upgrades](speed-upgrade.md) |
| Breaking | 6 FE for each point of cost: 1, plus the block's hardness and drops, times enchantments |
| Scooping | 50 FE a bucket |
| Holds | 10,000 FE and 16 buckets |

<br />
<br />
## Good to know

* Its Input / Output key opens sides for items and fluids separately, each Output or closed. A side set to Output sends what it broke or scooped into whatever is there, and an [Input Port](item-ports.md), hopper or pipe can empty it there. Every side starts closed.
* It runs on cable power and doesn't take up a place on the machine limit.
* With a [Redstone Upgrade](redstone-upgrade.md) it can work once for each redstone pulse.
* You can add [filters](../mechanics/type-filters.md) to decide what it may or may not break. Works for items and fluids.

Its opposite is the [Deployment Bay](deployment-bay.md).

<br />
<br />
## Recipe

<RecipeFor id="demolition_bay" />
