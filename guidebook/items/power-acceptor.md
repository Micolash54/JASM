---
navigation:
  title: Power Acceptor
  parent: items/index.md
  position: 310
  icon: power_acceptor
item_ids:
- jasm:power_acceptor
---

# Power Acceptor

<BlockImage id="power_acceptor" scale="3.5" />

JASM blocks only take power from JASM generators, batteries and cables. The Power Acceptor is the way in for power from other mods, and the way out for JASM power.

Put it between the other mod's power and a [Data Cable](data-cables.md) or machine. Right-click it to pick what it does:

* **Input only**: it pulls FE from the power blocks next to it, takes FE that other mods push into it, and feeds the cables and machines it touches. This is the default.
* **Output only**: it gives the power held by the machines on its network to the other mods' blocks next to it.
* **Input Output**: both.

| At a glance | |
| --- | --- |
| Speed | no limit: it holds no power and passes on whatever the cables and machines beside it can take |
| Direction | set in its screen: Input only, Output only or Input Output |

The Thin Power Acceptor does the same in the thin port shape. It sits on the face of the block it was placed against.

If you only need to charge a [Deck](decks.md), a [Combustion Generator](combustion-generators.md) is simpler.

<br />
<br />
## Recipe

<RecipeFor id="power_acceptor" />
