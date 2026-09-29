package dev.micolash.jasm.registry;

import com.mojang.serialization.Codec;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.autocraft.CraftRule;
import dev.micolash.jasm.autocraft.ProcessingCard;
import dev.micolash.jasm.autocraft.RecipeCard;
import dev.micolash.jasm.deck.DeckWafers;
import dev.micolash.jasm.network.MachineOwner;
import dev.micolash.jasm.network.TrustList;
import dev.micolash.jasm.wafer.WaferIdentity;
import dev.micolash.jasm.wafer.WaferMerge;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Jasm.MODID);

    /** Absent on blank (unformatted) wafers. */
    public static final Supplier<DataComponentType<WaferIdentity>> WAFER_IDENTITY = COMPONENTS.registerComponentType(
            "wafer_identity", b -> b.persistent(WaferIdentity.CODEC).networkSynchronized(WaferIdentity.STREAM_CODEC));

    /** On a wafer crafted from smaller ones whose items haven't moved over yet (see WaferMerge). */
    public static final Supplier<DataComponentType<WaferMerge>> WAFER_MERGE = COMPONENTS.registerComponentType(
            "wafer_merge", b -> b.persistent(WaferMerge.CODEC).networkSynchronized(WaferMerge.STREAM_CODEC));

    /** The wafers inside a Deck. */
    public static final Supplier<DataComponentType<DeckWafers>> DECK_WAFERS = COMPONENTS.registerComponentType(
            "deck_wafers", b -> b.persistent(DeckWafers.CODEC).networkSynchronized(DeckWafers.STREAM_CODEC));

    /** What sits in a Crafting Deck's 3×3 grid, saved with the Deck so a crash never loses it. */
    public static final Supplier<DataComponentType<ItemContainerContents>> DECK_GRID = COMPONENTS.registerComponentType(
            "deck_grid", b -> b.persistent(ItemContainerContents.CODEC).networkSynchronized(ItemContainerContents.STREAM_CODEC));

    /** A Filled Recipe Card's recipe. */
    public static final Supplier<DataComponentType<RecipeCard>> RECIPE_CARD = COMPONENTS.registerComponentType(
            "recipe_card", b -> b.persistent(RecipeCard.CODEC).networkSynchronized(RecipeCard.STREAM_CODEC));

    /** A Filled Recipe Card's recipe for a machine behind an Access Port. */
    public static final Supplier<DataComponentType<ProcessingCard>> PROCESSING_CARD = COMPONENTS.registerComponentType(
            "processing_card", b -> b.persistent(ProcessingCard.CODEC).networkSynchronized(ProcessingCard.STREAM_CODEC));

    /** An Encoding Terminal's identity: Crafting Decks paired with it remember it. */
    public static final Supplier<DataComponentType<UUID>> TERMINAL_ID = COMPONENTS.registerComponentType(
            "terminal_id", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** An Encoding Terminal's trusted players. */
    public static final Supplier<DataComponentType<TrustList>> TRUST = COMPONENTS.registerComponentType(
            "trust", b -> b.persistent(TrustList.CODEC).networkSynchronized(TrustList.STREAM_CODEC));

    /** A Crafting Deck's own identity, so finished jobs find their way back to it. Given when first paired. */
    public static final Supplier<DataComponentType<UUID>> DECK_ID = COMPONENTS.registerComponentType(
            "deck_id", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** The Encoding Terminal a Crafting Deck is paired with. */
    public static final Supplier<DataComponentType<UUID>> DECK_NETWORK = COMPONENTS.registerComponentType(
            "deck_network", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** A Crafting Deck's rules. */
    public static final Supplier<DataComponentType<List<CraftRule>>> DECK_RULES = COMPONENTS.registerComponentType(
            "deck_rules", b -> b.persistent(CraftRule.LIST_CODEC).networkSynchronized(CraftRule.LIST_STREAM_CODEC));

    /** Who a crafting-network block belongs to. */
    public static final Supplier<DataComponentType<MachineOwner>> OWNER = COMPONENTS.registerComponentType(
            "owner", b -> b.persistent(MachineOwner.CODEC).networkSynchronized(MachineOwner.STREAM_CODEC));

    /** Which Archive record a placed or carried Archive belongs to. Absent on a never-placed Archive. */
    public static final Supplier<DataComponentType<UUID>> ARCHIVE_IDENTITY = COMPONENTS.registerComponentType(
            "archive_identity", b -> b.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    /** Stored charge in FE, on Decks and on mined Archives. Integer because NeoForge's ItemAccessEnergyHandler requires it. */
    public static final Supplier<DataComponentType<Integer>> ENERGY = COMPONENTS.registerComponentType(
            "energy", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    /** Chips a critter has made towards its next stage. */
    public static final Supplier<DataComponentType<Integer>> TRAINING = COMPONENTS.registerComponentType(
            "training", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    private JasmComponents() {}
}
