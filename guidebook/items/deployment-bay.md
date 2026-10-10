---
navigation:
  title: Deployment Bay
  parent: items/index.md
  position: 460
  icon: deployment_bay
item_ids:
- jasm:deployment_bay
---

# Deployment Bay

<WhenOff feature="bays">

**Turned off on this world.** Placed bays keep their contents but don't work.

</WhenOff>

<BlockImage id="deployment_bay" scale="3.5" />

A Deployment Bay places blocks, seeds and fluids from its own grid and tank into the space in front of it, as a player would. It can also throw items out.

| At a glance | |
| --- | --- |
| Speed | 2 seconds, down to a quarter of a second with 4 [Speed Upgrades](speed-upgrade.md) |
| Cost | 20 FE for each block, fluid or stack |
| Holds | 10,000 FE and 16 buckets |

<br />
<br />
## Good to know

* Its Input / Output key opens sides for items and fluids separately, each Input or closed. An [Output Port](item-ports.md), hopper or pipe fills it through a side set to Input. Every side starts closed.
* It runs on cable power and doesn't take up a place on the machine limit.
* Speed Upgrades raise the power per placement or thrown stack as well as the speed. Four upgrades cost eight times the power for each action.
* With a [Redstone Upgrade](redstone-upgrade.md) it can work once for each redstone pulse.
* In drop mode items land just in front of the bay. When a solid block is in front, they appear on top of it or beside it instead.
* In drop mode it pauses while 32 entities are near, so it can't flood a spot with items.
* You can add [filters](../mechanics/type-filters.md) to decide what it may or may not place/drop. Works for items and fluids.

Its opposite is the [Demolition Bay](demolition-bay.md).

<br />
<br />
## Recipe

<RecipeFor id="deployment_bay" />
