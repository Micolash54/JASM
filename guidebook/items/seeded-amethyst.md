---
navigation:
  title: Seeded Amethyst
  parent: items/index.md
  position: 30
  icon: seeded_amethyst
item_ids:
- jasm:seeded_amethyst
- jasm:worn_seeded_amethyst
- jasm:cracked_seeded_amethyst
- jasm:small_data_crystal_bud
- jasm:medium_data_crystal_bud
- jasm:large_data_crystal_bud
- jasm:data_crystal_cluster
---

# Seeded Amethyst

<GameScene zoom="4" padding="2">
  <IsometricCamera yaw="20" pitch="30" />
  <Block id="jasm:seeded_amethyst" x="0" y="0" z="0" />
  <Block id="jasm:seeded_amethyst" x="2" y="0" z="0" />
  <Block id="jasm:small_data_crystal_bud" x="2" y="1" z="0" />
  <Block id="jasm:seeded_amethyst" x="4" y="0" z="0" />
  <Block id="jasm:medium_data_crystal_bud" x="4" y="1" z="0" />
  <Block id="jasm:seeded_amethyst" x="6" y="0" z="0" />
  <Block id="jasm:large_data_crystal_bud" x="6" y="1" z="0" />
  <Block id="jasm:seeded_amethyst" x="8" y="0" z="0" />
  <Block id="jasm:data_crystal_cluster" x="8" y="1" z="0" />
</GameScene>

Seeded Amethyst is a Block of Amethyst with a <ItemLink id="crystal_seed" /> in it. It grows Data Crystal buds on its open sides, like budding amethyst.

* Buds grow in four steps: <ItemLink id="small_data_crystal_bud" />, <ItemLink id="medium_data_crystal_bud" />, <ItemLink id="large_data_crystal_bud" />, then a full <ItemLink id="data_crystal_cluster" />.
* Mine the cluster for <ItemLink id="data_crystal" />. Fortune gives more. A bud broken early gives <ItemLink id="crystal_dust" /> instead.
* Each time a bud grows, the block may wear down: Seeded, <ItemLink id="worn_seeded_amethyst" />, <ItemLink id="cracked_seeded_amethyst" />, then a plain Block of Amethyst.
* Mined at any stage, it gives the Block of Amethyst back and the seed is lost.
* A powered [Crystal Resonator](crystal-resonator.md) makes it grow faster.
* Growing crystals draw wild Bitlings. See [befriending them](../mechanics/befriending-bitlings.md).

More in [Growing Data Crystals](../mechanics/growing-crystals.md).
