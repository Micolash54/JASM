---
navigation:
  title: Bitling Station
  parent: items/index.md
  position: 110
  icon: bitling_station
item_ids:
- jasm:bitling_station
---

# Bitling Station

<GameScene zoom="6" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <Block id="jasm:bitling_station" x="0" y="0" z="0" />
  <Entity id="jasm:wild_bitling" data="{kind:1,stage:0}" x="1.7" z="0.5" rotationY="0" />
</GameScene>

Put a Bitling, Nibbling or Byteling in a Bitling Station and a little living copy pops out. It walks, sprints, hops, looks about and lies down for a rest. Right-click it to pet it.

## Power

* The little Bitling is only a body. The critter's own battery pays for the walk: 5 FE a tick while it is out and about.
* When the battery drops to 5%, it walks home and sits on the pad.
* The station passes the power it gets straight into the critter's battery. Connect the station to a [Data Cable](data-cables.md) network or put a generator next to it.
* Stuck on the way home, or out of charge? It teleports back onto the pad.

## Good to know

* The screen shows what it is doing, its battery and a slider for how far it may roam (4 to 16 blocks).
* If something knocks it out, it comes back after 30 seconds with full health.
* Take the critter out and the little Bitling is gone.
* Hoppers and pipes can't reach the critter slot. Only the owner and the players they trust can open the station.

A Bitling Station counts toward a network's [machine limit](network-brain.md).

## Recipe

<RecipeFor id="bitling_station" />
