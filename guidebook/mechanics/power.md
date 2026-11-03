---
navigation:
  title: Power
  parent: mechanics/index.md
  position: 100
  icon: basic_combustion_generator
---

# Power

<BlockImage id="basic_combustion_generator" scale="3.5" />

Most JASM blocks run on FE.

<br />
<br />
## Where it comes from

* [Combustion Generators](../items/combustion-generators.md) burn fuel. They are the simple answer.
* Other mods, through the [Power Interface](../items/power-acceptor.md). JASM blocks never take power straight from another mod.
* [Batteries](../items/battery.md) store it for later, and charge items in their charging slot.

<br />
<br />
## How it travels

* A generator pushes power into every block it touches.
* [Data Cables](../items/data-cables.md) carry it through a network, as fast as it comes in.
* Machines and Archives that touch each other pass power on like a cable. A tower of them needs one cable, or one generator, at any block.
* Breaking a machine in the middle of a tower cuts off the ones above it, unless a cable reaches them another way.
* Ports use no power and pass none on to the machines they touch. A port with a [Power Upgrade](../items/power-upgrade.md) passes power straight on to its machines.

<br />
<br />
## What uses it

| Block | Uses |
| --- | --- |
| Network Encoder | 5 FE a tick |
| Recipe Rack | 2 FE a tick |
| Crafting Server | what its Processors use, only while it has a job |
| Advanced Crafting Server | what its Processors use, only while it has a job |
| Network Brain | 8 FE a tick alone, 24 with one floor, up to 5,000 with 8 floors |
| Crystal Resonator | 10 FE a tick |
| Crystal Foundry | 40 FE a tick while it grows |
| Archive | 5, 10 or 20 FE a tick by tier |

Decks and Bitlings have their own batteries. Moving an item through a Deck costs 1 FE.
