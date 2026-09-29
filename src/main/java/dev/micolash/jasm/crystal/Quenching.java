package dev.micolash.jasm.crystal;

import dev.micolash.jasm.Jasm;
import dev.micolash.jasm.config.JasmConfig;
import dev.micolash.jasm.registry.JasmRecipes;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Items with a quenching recipe that lie in water, source or flowing, turn into the result after a short while. */
@EventBusSubscriber(modid = Jasm.MODID)
public final class Quenching {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Jasm.MODID);

    /** Ticks an item entity has been in water. Not saved: a reload starts the count again. */
    private static final Supplier<AttachmentType<Integer>> QUENCH_TICKS = ATTACHMENTS.register("quench_ticks",
            () -> AttachmentType.builder(() -> 0).build());

    private Quenching() {}

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemEntity item) || !(item.level() instanceof ServerLevel level) || item.isRemoved()) {
            return;
        }
        if (!item.isInWater()) {
            if (item.hasData(QUENCH_TICKS)) {
                item.removeData(QUENCH_TICKS);
            }
            return;
        }
        ItemStack stack = item.getItem();
        Optional<RecipeHolder<QuenchingRecipe>> recipe = level.getServer().getRecipeManager()
                .getRecipeFor(JasmRecipes.QUENCHING_TYPE.get(), new SingleRecipeInput(stack), level);
        if (recipe.isEmpty()) {
            return;
        }
        int ticks = item.getData(QUENCH_TICKS) + 1;
        if (ticks < JasmConfig.QUENCH_TICKS.getAsInt()) {
            item.setData(QUENCH_TICKS, ticks);
            if (ticks % 4 == 0) {
                simmer(level, item);
            }
            return;
        }
        item.removeData(QUENCH_TICKS);
        item.setItem(recipe.get().value().result(stack.getCount()));
        hiss(level, item);
    }

    /** While it cools: bubbles in the water and a thin wisp of vapour above it. */
    private static void simmer(ServerLevel level, ItemEntity item) {
        level.sendParticles(ParticleTypes.BUBBLE_COLUMN_UP, item.getX(), item.getY(), item.getZ(), 3, 0.12, 0.05, 0.12, 0.05);
        level.sendParticles(ParticleTypes.WHITE_SMOKE, item.getX(), item.getY() + 0.4, item.getZ(), 1, 0.08, 0.05, 0.08, 0.01);
    }

    /** Done: a hiss, a splash and a burst of steam. */
    private static void hiss(ServerLevel level, ItemEntity item) {
        double x = item.getX();
        double y = item.getY();
        double z = item.getZ();
        level.playSound(null, x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.6F, 1.4F);
        level.sendParticles(ParticleTypes.SPLASH, x, y + 0.3, z, 12, 0.2, 0.05, 0.2, 0.1);
        level.sendParticles(ParticleTypes.CLOUD, x, y + 0.4, z, 10, 0.15, 0.1, 0.15, 0.03);
        level.sendParticles(ParticleTypes.WHITE_SMOKE, x, y + 0.5, z, 14, 0.2, 0.2, 0.2, 0.04);
    }
}
