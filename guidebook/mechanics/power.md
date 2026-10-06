---
navigation:
  title: Power
  parent: mechanics/index.md
  position: 100
  icon: basic_combustion_generator
---

# Power

<BlockImage id="basic_combustion_generator" scale="3.5" />

Most JASM blocks run on FE. Here is where it comes from and how it travels.

<br />
<br />
## Where it comes from

* [Combustion Generators](../items/combustion-generators.md) burn fuel. They are the simple answer.
* Other mods, through the [Power Acceptor](../items/power-acceptor.md). JASM blocks never take power straight from another mod.

<br />
<br />
## How it travels

* A generator pushes power into every block it touches.
* [Data Cables](../items/data-cables.md) carry it through a network, each at its own speed. A slower cable slows what passes through it.
* A machine that touches a generator needs no cable.

<br />
<br />
## What uses it

| Block | Uses |
| --- | --- |
| Encoding Terminal | 5 FE a tick |
| Recipe Rack | 2 FE a tick |
| Crafting Server | 20 FE a tick, plus its Processors |
| Access Port | 2 FE a tick |
| Network Brain | 8 FE a tick alone, 24 with one floor, up to 5,000 with 8 floors |
| Crystal Resonator | 10 FE a tick |
| Crystal Foundry | 40 FE a tick while it grows |
| Archive | 5, 10 or 20 FE a tick by tier |

Decks and Bitlings have their own batteries. Moving an item through a Deck costs 1 FE.

All of these are the defaults, and the config can change them.
