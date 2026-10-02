package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class JasmTags {
    /** Logic, Memory and Link Chips and their Advanced kinds: what Network Brains learn from and wild Bitlings accept. */
    public static final TagKey<Item> TYPED_CHIPS = TagKey.create(Registries.ITEM, Jasm.id("typed_chips"));
    public static final TagKey<Item> ADVANCED_CHIPS = TagKey.create(Registries.ITEM, Jasm.id("advanced_chips"));

    private JasmTags() {}
}
