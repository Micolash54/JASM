---
navigation:
  title: Power Acceptor
  parent: items/index.md
  position: 310
  icon: power_acceptor
item_ids:
- jasm:power_acceptor
- jasm:thin_power_acceptor
---

# Power Acceptor

<BlockImage id="power_acceptor" scale="3.5" />

JASM blocks only take power from JASM generators, batteries and cables. The Power Acceptor is the way in for power from other mods, and the way out for JASM power.

Put it between the other mod's power and a [Data Cable](data-cables.md) or machine. Right-click it to pick what it does:

* **Input only**: it accepts the FE other mods push into it and feeds the cables and machines it touches. It never pulls power out of anything, and only takes what the machines and Batteries on its network have room for. This is the default.
* **Output only**: it gives the power stored in Batteries to the other mods' blocks next to it, as fast as each one takes it: Batteries it touches, and Batteries on the cables of its network. It never takes the power inside machines.

Its screen shows the power going through it, in FE/t.

| At a glance | |
| --- | --- |
| Speed | set by the other mod: it holds no power and passes on whatever is pushed into it, as far as the network has room |
| Direction | set in its screen: Input only or Output only |

The Thin Power Acceptor does the same from a cable face, like a thin [Access Port](access-port.md): its plate faces out, and it only trades power with the block in front of it. Its mode is set the same way, in its screen.

If you only need to charge a [Deck](decks.md), a [Combustion Generator](combustion-generators.md) is simpler.

<br />
<br />
## Recipe

<Row>
  <RecipeFor id="power_acceptor" />
  <RecipeFor id="thin_power_acceptor" />
</Row>
