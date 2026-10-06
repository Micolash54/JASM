package dev.micolash.jasm.registry;

import dev.micolash.jasm.Jasm;
import java.util.function.Supplier;
import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.criterion.PlayerTrigger;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredRegister;

// achievement moments the mod reports itself, always to the player who caused them
public final class JasmTriggers {
    public static final DeferredRegister<CriterionTrigger<?>> TRIGGERS = DeferredRegister.create(Registries.TRIGGER_TYPE, Jasm.MODID);

    public static final Supplier<PlayerTrigger> DECK_DELIVERY = TRIGGERS.register("deck_delivery", PlayerTrigger::new);
    public static final Supplier<PlayerTrigger> PET_BITLING = TRIGGERS.register("pet_bitling", PlayerTrigger::new);
    // a placed machine stopped its network for being over the limit
    public static final Supplier<PlayerTrigger> NETWORK_OVERFULL = TRIGGERS.register("network_overfull", PlayerTrigger::new);
    public static final Supplier<PlayerTrigger> BRAIN_FLOOR = TRIGGERS.register("brain_floor", PlayerTrigger::new);
    public static final Supplier<PlayerTrigger> BRAIN_TOWER = TRIGGERS.register("brain_tower", PlayerTrigger::new);
    // all crafts done, not cancelled
    public static final Supplier<PlayerTrigger> AUTOCRAFT_DONE = TRIGGERS.register("autocraft_done", PlayerTrigger::new);

    private JasmTriggers() {}
}
