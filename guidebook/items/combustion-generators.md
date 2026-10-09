---
navigation:
  title: Combustion Generators
  parent: items/index.md
  position: 320
  icon: advanced_combustion_generator
item_ids:
- jasm:basic_combustion_generator
- jasm:advanced_combustion_generator
- jasm:elite_combustion_generator
---

# Combustion Generators

<WhenOff feature="generators">

**Turned off on this world.** Placed generators keep their fuel and charge but don't burn.

</WhenOff>

<Row>
  <BlockImage id="basic_combustion_generator" scale="3.5" />
  <BlockImage id="advanced_combustion_generator" scale="3.5" />
  <BlockImage id="elite_combustion_generator" scale="3.5" />
</Row>

Burn anything a furnace burns and get power. A generator charges Decks and other chargeable items in its charging slot first, then sends what is left into every block it touches.

| Tier | Burns | Makes | Holds | Gives per block |
| --- | --- | --- | --- | --- |
| Basic | 2x faster | 40 FE a tick | 100,000 FE | 1,000 FE a tick |
| Advanced | 4x faster | 160 FE a tick | 400,000 FE | 2,500 FE a tick |
| Elite | 8x faster | 640 FE a tick | 1,000,000 FE | 5,000 FE a tick |

A piece of coal gives 32,000 FE in a Basic generator over 40 seconds, 64,000 FE over 20 seconds in an Advanced one and 128,000 FE over 10 seconds in an Elite one.

<br />
<br />
## Tips

* Put one next to a [Chip Workshop](chip-workshop.md) or an [Archive](archives.md) to power it without any cable.
* Drop a [Deck](decks.md) in the charging slot to top up its battery.
* Jade shows how long the fuel has left.

<br />
<br />
## Upgrading

The old generator is used up. The new one keeps its stored power.

<br />
<br />
## Recipes

<Column>
  <Row>
    <RecipeFor id="basic_combustion_generator" />
    <RecipeFor id="advanced_combustion_generator" />
  </Row>
  <Row>
    <RecipeFor id="elite_combustion_generator" />
  </Row>
</Column>
