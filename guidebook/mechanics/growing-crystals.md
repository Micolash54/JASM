---
navigation:
  title: Growing Data Crystals
  parent: mechanics/index.md
  position: 10
  icon: seeded_amethyst
---

# Growing Data Crystals

<Row alignItems="end">
  <GameScene zoom="3.5" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Block id="jasm:seeded_amethyst" />
  </GameScene>
  <GameScene zoom="3.5" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Block id="jasm:seeded_amethyst" />
    <Block id="jasm:small_data_crystal_bud" y="1" />
  </GameScene>
  <GameScene zoom="3.5" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Block id="jasm:seeded_amethyst" />
    <Block id="jasm:medium_data_crystal_bud" y="1" />
  </GameScene>
  <GameScene zoom="3.5" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Block id="jasm:seeded_amethyst" />
    <Block id="jasm:large_data_crystal_bud" y="1" />
  </GameScene>
  <GameScene zoom="3.5" padding="2">
    <IsometricCamera yaw="20" pitch="30" />
    <Block id="jasm:seeded_amethyst" />
    <Block id="jasm:data_crystal_cluster" y="1" />
  </GameScene>
</Row>

Data Crystals are the raw material for everything in JASM. You grow them on a block of amethyst.
<br />
<br />
## Step by step

1. Craft a <ItemLink id="crystal_seed" /> and use it on a **Block of Amethyst**. It becomes <ItemLink id="seeded_amethyst" />.
2. Buds grow on its open sides, like budding amethyst: small, medium, large, then a full <ItemLink id="data_crystal_cluster" />.
3. Mine the full cluster for <ItemLink id="data_crystal" />, with your hand or any tool. Fortune gives more.
4. A bud broken early gives <ItemLink id="crystal_dust" /> instead. Four dust and a redstone make a new seed.

<br />
<br />
## The seed wears out

Each time a bud grows, the block may wear down one stage: Seeded, Worn, Cracked, then back to a plain Block of Amethyst. By default the chance is 12% for each bud.

Mined at any stage, the block gives the Block of Amethyst back and the seed is lost.

<br />
<br />
## Growing faster

A powered [Crystal Resonator](../items/crystal-resonator.md) makes a block grow faster. For bulk, the [Crystal Foundry](../items/crystal-foundry.md) turns each Crystal Seed straight into Blank Chips, with no Block of Amethyst to wear out.

<br />
<br />
## Visitors

Growing crystals draw wild Bitlings. [Find out more](befriending-bitlings.md).
