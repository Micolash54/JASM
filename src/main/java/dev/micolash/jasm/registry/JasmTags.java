package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public final class JasmTags {
    /** Logic, Memory and Link Chips and their Advanced kinds: what Network Brains learn from and wild Bitlings accept. */
    public static final TagKey<Item> TYPED_CHIPS = TagKey.create(Registries.ITEM, Jasm.id("typed_chips"));
    public static final TagKey<Item> ADVANCED_CHIPS = TagKey.create(Registries.ITEM, Jasm.id("advanced_chips"));
    /** JASM blocks a wrench turns: those with a front, except the Brain tower. */
    public static final TagKey<Block> WRENCH_TURNABLE = TagKey.create(Registries.BLOCK, Jasm.id("wrench_turnable"));
    /** JASM blocks a wrench picks up on sneak: every machine, cable and power block, not the growing crystals. */
    public static final TagKey<Block> WRENCH_PICKUP = TagKey.create(Registries.BLOCK, Jasm.id("wrench_pickup"));

    private JasmTags() {}
}
