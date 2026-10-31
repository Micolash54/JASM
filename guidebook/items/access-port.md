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

The Access Port is how your network talks to other machines. Put one next to a furnace, a modded machine or a multiblock, then pick which processing recipes it makes there.

<br />
<br />
## Setting it up

1. Join the port to your network with a [Data Cable](data-cables.md).
2. Write a processing recipe at a [Network Encoder](encoding-terminal.md): what goes in, and up to three things that come back. Keep the card in a [Recipe Rack](recipe-rack.md).
3. Right-click the port and pick the recipe in its screen.
4. Make the machine give its results back into the port, or use a hopper or a pipe. A JASM machine with an Input / Output key works with the port only through the side facing it: Input takes the ingredients, Output sends the results back into the port.

Fluids work too. Several ports with the same recipe share the work.

<br />
<br />
## The port screen

* **Recipe list** on the left: every processing recipe in the Recipe Racks of your network. Search it by name, hover a recipe for its details, and fold the list away with the key on its left edge.
* **Active recipes** on the main panel: the ones this port makes, up to 16. Click a recipe in the list to add it, click it in the active bar to remove it. A tick in the list marks recipes the port already serves.
* **Machine** row: the machine the port works with, and what it is doing. Hover its icon for the name.
* A recipe whose card has left every rack is dimmed and marked as missing. It comes back when the card is in a rack again.
* The pencil key opens a small window to rename the port. Press **Rename** to set the name.
* **Copy** and **Paste** keys on the Active recipes row remember a port's name and recipes until the game closes. Click Paste to replace this port's name and recipes, or Shift+click to add its recipes (up to 16).

Editing a card makes a new recipe: pick the new one again.

<br />
<br />
## One machine type

A full block port works with one kind of machine, even if several touch it. The first one it finds sets the type. When more kinds are touching, click another icon in the Machine row to switch. If the machine is swapped for another kind, the port follows once the old kind is gone.

A thin port has one machine, the one it faces.

<br />
<br />
## Two shapes

* **Full block:** also takes items from any face, even without a crafting job. It has an eight-slot buffer. Ordinary items go to the owner's paired Deck, or to another paired Deck you pick in the port. Items wait if the Deck is full.
* **Thin:** a cable attachment, up to six on one cable, one per face. It can also sit straight on a machine with no cable. It stays off the network until you place a cable into its space, and it stays in place if the cable is broken.

Convert one into the other in a crafting grid. Both recipes are below.

<br />
<br />
## Good to know

* Machines and pipes can push items and fluids into it, and they go into the network's storage when there's room.
* A thin port serves and accepts items only through its outward face, including from pipes and hoppers while idle.
* Blocking mode waits to send new ingredients while the machine still holds some for this recipe.
* Mined or picked up with a [Wrench](wrench.md), the port keeps its name, machine type, recipes, Blocking Mode and redstone mode, and has them again when placed.
* A job whose recipe no port serves waits and says so, and carries on once a port serves it again. Ingredients already sent to a port that is then mined are no longer waited on.
* Add a [Power Upgrade](power-upgrade.md) to supply FE to the machines of the chosen type, or the machine a thin port faces.
* Five upgrade slots take up to four [Speed Upgrades](speed-upgrade.md) and one [Redstone Upgrade](redstone-upgrade.md). A paused port moves nothing and shows Paused, and crafting jobs wait for it.
* It uses no power and counts toward the [machine limit](network-brain.md).

<br />
<br />
## Recipes

<RecipesFor id="access_port" />
<RecipesFor id="thin_access_port" />
