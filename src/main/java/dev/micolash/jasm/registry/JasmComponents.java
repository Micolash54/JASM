package dev.micolash.jasm.registry;

import com.mojang.serialization.Codec;
import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.deck.DeckWafers;
import dev.micolash.jasm.wafer.WaferIdentity;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class JasmComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Jasm.MODID);

    /** Absent on blank (unformatted) wafers. */
    public static final Supplier<DataComponentType<WaferIdentity>> WAFER_IDENTITY = COMPONENTS.registerComponentType(
            "wafer_identity", b -> b.persistent(WaferIdentity.CODEC).networkSynchronized(WaferIdentity.STREAM_CODEC));

    /** The wafers inside a Deck. */
    public static final Supplier<DataComponentType<DeckWafers>> DECK_WAFERS = COMPONENTS.registerComponentType(
            "deck_wafers", b -> b.persistent(DeckWafers.CODEC).networkSynchronized(DeckWafers.STREAM_CODEC));

    /** Deck battery charge in FE. Integer because NeoForge's ItemAccessEnergyHandler requires it. */
    public static final Supplier<DataComponentType<Integer>> ENERGY = COMPONENTS.registerComponentType(
            "energy", b -> b.persistent(Codec.INT).networkSynchronized(ByteBufCodecs.VAR_INT));

    private JasmComponents() {}
}
