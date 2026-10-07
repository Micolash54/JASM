---
navigation:
  title: Access Port
  parent: items/index.md
  position: 400
  icon: access_port
item_ids:
- jasm:access_port
- jasm:thin_access_port
---

# Access Port

<Row alignItems="center">
<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <Block id="jasm:access_port" x="0" y="0" z="0" p:east="machine" />
  <Block id="minecraft:furnace" x="1" y="0" z="0" p:facing="south" />
</GameScene>
<GameScene zoom="3.5" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <ImportStructure src="../assets/thin_access_port.snbt" />
</GameScene>
</Row>

The Access Port is how your network talks to other machines. Put one next to a furnace, a modded machine or a multiblock, and write processing cards for it. Every block touching the port that takes items counts as a machine.

<br />
<br />
## Setting it up

1. Join the port to your network with a [Data Cable](data-cables.md).
2. Write a processing card for it at an [Encoding Terminal](encoding-terminal.md): what goes in, and up to three things that come back.
3. Make the machine give its results back into the port, or use a hopper or a pipe.

Fluids work too. Several machines share the work.

<br />
<br />
## Two shapes

* **Full block:** also takes items from any face, even without a crafting job. It has an eight-slot buffer. Ordinary items go to the owner's paired Deck, or to another paired Deck you pick in the port. Items wait if the Deck is full.
* **Thin:** a cable attachment, up to six on one cable, one per face. It can also sit straight on a machine with no cable. It stays off the network until you place a cable into its space.

Convert one into the other in a crafting grid. Both recipes are below.

<br />
<br />
## Good to know

* A thin port serves and accepts items only through its outward face, including from pipes and hoppers while idle.
* Blocking mode waits to send new ingredients while the machine still holds some for this recipe.
* Add a [Power Upgrade](power-upgrade.md) to supply FE to the machine it faces.
* It uses 2 FE a tick and counts toward the [machine limit](network-brain.md).

<br />
<br />
## Recipes

<RecipesFor id="access_port" />
<RecipesFor id="thin_access_port" />
