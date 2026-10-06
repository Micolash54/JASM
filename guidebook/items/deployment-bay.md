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

<BlockImage id="deployment_bay" scale="3.5" />

A Deployment Bay places blocks, seeds and fluids from its own grid and tank into the space in front of it, as a player would. It can also throw items out. A Bitling in a hard hat does the work.

| At a glance | |
| --- | --- |
| Speed | 2 seconds, down to half a second with 3 [Speed Upgrades](speed-upgrade.md) |
| Cost | 20 FE for each block, fluid or stack |
| Holds | 10,000 FE and 16 buckets |

## Good to know

* An [Output Port](item-ports.md) fills it.
* It runs on cable power and doesn't take up a place on the machine limit.
* With a [Redstone Upgrade](redstone-upgrade.md) it can work once for each redstone pulse.
* In drop mode it pauses while 32 entities are near, so it can't flood a spot with items.

Its opposite is the [Demolition Bay](demolition-bay.md).

## Recipe

<RecipeFor id="deployment_bay" />
