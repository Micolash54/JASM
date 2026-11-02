---
navigation:
  title: Relay Chatterbox
  parent: items/index.md
  position: 360
  icon: relay_chatterbox
item_ids:
- jasm:relay_chatterbox
---

# Relay Chatterbox

<BlockImage id="relay_chatterbox" scale="3.5" />

Two Relay Chatterboxes on the same frequency join their networks like a wireless cable. Storage, crafting and power all pass through, at any distance in the same dimension. To link across dimensions, put a [Dimension Upgrade](dimension-upgrade.md) in one of the two. The dish on top turns toward its partner.

<br />
<br />
## Using it

* It is two blocks tall, so it needs room above. Join either half to a network with a [Data Cable](data-cables.md). It counts as one machine toward the [machine limit](network-brain.md).
* Right-click opens its screen. The frequency runs from 0 to 999: **-** and **+** change it, or type a number and press **Set**. **Clear** takes the frequency off.
* The Operator slot takes only a <ItemLink id="link_byteling" />. Without one, the Chatterbox does nothing.
* The other slot takes a Dimension Upgrade.

<br />
<br />
## Pairs

* Exactly two Chatterboxes share a frequency. Your frequencies are your own: only your networks link, and a stranger can never join yours by guessing a number.
* A third on the same frequency waits. When one of the pair is broken or retuned, the one that has waited longest takes its place.
* Both blocks must be in loaded chunks. If one isn't, the link drops and comes back when it loads again.

<br />
<br />
## Power

* Every linked Chatterbox uses 200 FE per tick, or 400 FE with a Dimension Upgrade in it, even if the link stays in one dimension.
* The Link Byteling's battery pays. The network tops it up through the cable.
* One charged end is enough: it pays for both, and the power crossing the link charges the other. The link stops only when both batteries are flat, and starts again as soon as either has charge.
* The screen shows, top right, how much FE per tick this block is paying right now.

<br />
<br />
## Front light

| Light | Meaning |
| --- | --- |
| Green | Linked, and both Bytelings have charge. |
| Yellow | Linked, but one battery is flat. The other end pays for both. |
| Red | Not linked: no partner yet, a third one waiting, the far end not loaded, another dimension without an upgrade, or both batteries flat. |
| Off, showing `---` | No frequency set. |

The screen says in words what the Byteling is up to.

<br />
<br />
## Breaking it

A broken Chatterbox keeps its frequency, Link Byteling and Dimension Upgrade inside the item. Its tooltip lists them.

<br />
<br />
## Recipe

<RecipeFor id="relay_chatterbox" />
