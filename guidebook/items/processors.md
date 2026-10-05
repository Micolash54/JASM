---
navigation:
  title: Processors
  parent: items/index.md
  position: 540
  icon: advanced_processor
item_ids:
- jasm:basic_processor
- jasm:advanced_processor
- jasm:elite_processor
---

# Processors

<ItemImage id="advanced_processor" scale="8" />

Processors go in a [Crafting Server](crafting-server.md). Each one runs crafts side by side, so more Processors means a job finishes sooner.

| Processor | Crafts at once | Adds to the server's use |
| --- | --- | --- |
| Basic | 1 | 10 FE a tick |
| Advanced | 4 | 30 FE a tick |
| Elite | 16 | 80 FE a tick |

Every tier crafts at the same speed. A higher tier just does more of them at once. One Processor also handles a processing job: it keeps one set out in a machine until the results come back.

With all Processors busy, a job waits, even if other machines are free.

## Making one

Each tier is made from four of the tier below. The Advanced Processor also needs a Link Chip and the Elite an Advanced Logic Chip.

## Recipes

<RecipeFor id="basic_processor" />
<RecipeFor id="advanced_processor" />
<RecipeFor id="elite_processor" />
