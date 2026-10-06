---
navigation:
  title: Network Chamber
  parent: items/index.md
  position: 350
  icon: network_chamber
item_ids:
- jasm:network_chamber
---

# Network Chamber

<Row gap="10">
  <GameScene zoom="3" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Block id="jasm:network_chamber" x="0" y="0" z="0" />
    <Block id="jasm:network_chamber" x="0" y="0" z="1" />
    <Block id="jasm:network_chamber" x="0" y="0" z="2" />
    <Block id="jasm:network_chamber" x="1" y="0" z="0" />
    <Block id="jasm:network_brain" x="1" y="0" z="1" />
    <Block id="jasm:network_chamber" x="1" y="0" z="2" />
    <Block id="jasm:network_chamber" x="2" y="0" z="0" />
    <Block id="jasm:network_chamber" x="2" y="0" z="1" />
    <Block id="jasm:network_chamber" x="2" y="0" z="2" />
  </GameScene>
  <GameScene zoom="3" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Block id="jasm:network_chamber" x="0" y="0" z="0" p:formed="true" />
    <Block id="jasm:network_chamber" x="0" y="0" z="1" p:formed="true" />
    <Block id="jasm:network_chamber" x="0" y="0" z="2" p:formed="true" />
    <Block id="jasm:network_chamber" x="1" y="0" z="0" p:formed="true" />
    <Block id="jasm:network_brain" x="1" y="0" z="1" p:floor="true" p:awake="true" />
    <Block id="jasm:network_chamber" x="1" y="0" z="2" p:formed="true" />
    <Block id="jasm:network_chamber" x="2" y="0" z="0" p:formed="true" />
    <Block id="jasm:network_chamber" x="2" y="0" z="1" p:formed="true" />
    <Block id="jasm:network_chamber" x="2" y="0" z="2" p:formed="true" />
  </GameScene>
</Row>

Put 8 Network Chambers round a [Network Brain](network-brain.md), on the same layer, and it becomes a brain floor: a little glass office where 8 Bitlings work round a glowing core. Each floor adds 12 machines.

<br />
<br />
## Towers

Stack floors straight on top of each other to make a tower.

* Every floor needs its own brain in the middle and all 8 of its chambers.
* A floor with a gap doesn't count, and splits the tower in two.
* A tower can be 8 floors tall. Floors stacked higher make a tower of their own.
* Each floor uses 8 FE every tick, and without power the Bitlings nap.

<br />
<br />
## Using a tower

* Cables can join any block of a tower, and a cable on any block powers the whole tower.
* Right-click any chamber or brain of a tower to open its screen.
* Chambers don't count toward the machine limit.

<br />
<br />
## Recipe

One craft makes 4 chambers.

<RecipeFor id="network_chamber" />
