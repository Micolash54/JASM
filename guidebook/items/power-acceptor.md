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

JASM blocks only take power from JASM generators, batteries and cables. The Power Acceptor is the way in for power from other mods.

Put it between the other mod's power and a [Data Cable](data-cables.md) or machine. It pulls FE from the power blocks next to it, takes FE that other mods push into it, and feeds the cables and machines it touches.

| At a glance | |
| --- | --- |
| Speed | 10,000 FE a tick by default |
| Direction | in only; it never gives power back |

If you only need to charge a [Deck](decks.md), a [Combustion Generator](combustion-generators.md) is simpler.

<br />
<br />
## Recipe

<RecipeFor id="power_acceptor" />
