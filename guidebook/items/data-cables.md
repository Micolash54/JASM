---
navigation:
  title: Data Cables
  parent: items/index.md
  position: 300
  icon: advanced_data_cable
item_ids:
- jasm:data_cable
- jasm:advanced_data_cable
- jasm:elite_data_cable
---

# Data Cables

<GameScene zoom="6" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <Block id="jasm:data_cable" x="0" y="0" z="0" />
  <Block id="jasm:data_cable" x="1" y="0" z="0" />
  <Block id="jasm:advanced_data_cable" x="2" y="0" z="0" />
  <Block id="jasm:advanced_data_cable" x="3" y="0" z="0" />
  <Block id="jasm:elite_data_cable" x="4" y="0" z="0" />
  <Block id="jasm:elite_data_cable" x="5" y="0" z="0" />
</GameScene>

Data Cables join your crafting blocks into one network and carry power between them. Any two cables join, whatever the tier.

| Cable | Power it moves | Made from |
| --- | --- | --- |
| Data Cable | 1,000 FE a tick | iron, redstone and an amethyst shard |
| Advanced | 10,000 FE a tick | 8 Data Cables round a Link Chip |
| Elite | 50,000 FE a tick | 8 Advanced round an Advanced Link Chip |

Each recipe makes 8 cables.

## Good to know

* Every cable passes power on at its own speed, so a slower cable only slows the power that goes through it.
* Machines that simply touch each other form a network too, with no cable at all.
* Different players' networks never join.
* Cables don't count toward the [machine limit](network-brain.md).
* A cable can carry up to six thin [Access Ports](access-port.md), one on each face.

See [networks](../mechanics/networks.md) for the rules in full.

## Recipes

<RecipeFor id="data_cable" />
<RecipeFor id="advanced_data_cable" />
<RecipeFor id="elite_data_cable" />
