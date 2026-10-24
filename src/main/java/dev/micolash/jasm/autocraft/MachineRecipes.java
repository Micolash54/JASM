package dev.micolash.jasm.autocraft;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * The real recipe behind a processing card, so each of its item slots may also take what that recipe takes there (any
 * log that burns for charcoal, any birch log for a Sawmill's birch planks). Never more than the recipe takes, and never
 * a guess: a card whose recipe can't be found for sure keeps to the items written on it.
 *
 * <ul>
 *   <li>A Furnace, Smoker or Blast Furnace runs its own kind of cooking recipe.</li>
 *   <li>Another mod's machine runs that mod's recipes: the card's recipe is the one whose item inputs pair off with the
 *       card's, each taking the card's item in the same amount (or the same whole multiple of it, for several runs at
 *       once), and which makes the card's first output. Several recipes may fit only if they take exactly the same
 *       items in every slot. When none of that mod's recipes fit, its machine may be a furnace of its own: vanilla
 *       smelting, blasting and smoking are tried the same way.</li>
 *   <li>Other vanilla blocks (a barrel standing in for a machine) run nothing JASM knows.</li>
 * </ul>
 *
 * An item takes a slot's place only if the recipe takes it there and in no other slot, it carries no extra data (a
 * named or enchanted item is never used up by accident), with it in that slot the machine has no other recipe of the
 * same kind it could run instead, and no machine of the card says it never holds it.
 */
public final class MachineRecipes {
    private MachineRecipes() {}

    private static final List<RecipeType<? extends AbstractCookingRecipe>> COOKING =
            List.of(RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING);

    /** Whether one item may take the card's own item's place in a slot. */
    @FunctionalInterface
    private interface Rule {
        boolean accepts(ServerLevel level, ItemResource candidate, ItemStack stack);
    }

    /** What each of a card's used input slots may take besides its own item. */
    public static final class Match {
        /** By used input slot; null where the slot takes only its own item. */
        private final List<@Nullable Rule> slots;
        private final ProcessingCard card;

        private Match(ProcessingCard card, List<@Nullable Rule> slots) {
            this.card = card;
            this.slots = slots;
        }

        /** Whether used input slot {@code slot} may take anything besides the card's own item. */
        public boolean opens(int slot) {
            return slot >= 0 && slot < slots.size() && slots.get(slot) != null;
        }

        /** Whether {@code candidate} may go in used input slot {@code slot} in place of the card's own item. */
        public boolean accepts(ServerLevel level, int slot, ItemResource candidate) {
            if (!opens(slot) || candidate.isEmpty()) {
                return false;
            }
            ItemStack stack = candidate.toStack(1);
            return stack.getComponentsPatch().equals(DataComponentPatch.EMPTY) && slots.get(slot).accepts(level, candidate, stack)
                    && !refused(level, card.usedInputs().get(slot).item(), candidate);
        }

        /** Whether a loaded machine says no slot of it ever holds {@code candidate}, though one holds the card's {@code own}. */
        private boolean refused(ServerLevel level, ItemResource own, ItemResource candidate) {
            for (ProcessingCard.Machine machine : card.machines()) {
                BlockPos pos = machine.pos().relative(machine.side());
                ResourceHandler<ItemResource> inlet = level.isLoaded(pos) ? Machines.inlet(level, pos, machine.side().getOpposite()) : null;
                if (inlet == null) {
                    continue;
                }
                boolean ownFits = false;
                boolean fits = false;
                for (int i = 0; i < inlet.size(); i++) {
                    ownFits |= inlet.isValid(i, own);
                    fits |= inlet.isValid(i, candidate);
                }
                if (ownFits && !fits) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One item slot of the card: its place among the used inputs, its item and how many. */
    private record Want(int slot, ItemResource item, int count) {}

    /** A reading that fits the card: which of its inputs each of the card's item slots pairs with. */
    private record Hit(RecipeType<?> type, RecipeReader.Read read, int[] pairs) {}

    /** A cooking recipe that fits the card, with its kind. */
    private record Cooked(RecipeType<? extends AbstractCookingRecipe> type, RecipeHolder<? extends AbstractCookingRecipe> recipe) {}

    /**
     * What {@code card}'s slots may take, or null when nothing is known for sure: a machine can't be looked at now, the
     * machines are of different kinds, or no recipe (or no single set of items) fits.
     */
    public static @Nullable Match of(ServerLevel level, ProcessingCard card) {
        ProcessingCard.Amount main = card.main();
        if (card.machines().isEmpty() || main.isEmpty() || main.isFluid() || main.isMaterial()) {
            return null;
        }
        RecipeType<? extends AbstractCookingRecipe> cooking = null;
        String namespace = null;
        for (ProcessingCard.Machine machine : card.machines()) {
            BlockPos pos = machine.pos().relative(machine.side());
            if (!level.isLoaded(machine.pos()) || !level.isLoaded(pos)) {
                return null;
            }
            RecipeType<? extends AbstractCookingRecipe> here = cookingAt(level.getBlockEntity(pos));
            String mod = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getNamespace();
            String kind = here != null ? "" : mod;
            if (namespace != null && !namespace.equals(kind) || here != null && cooking != null && here != cooking) {
                return null;
            }
            namespace = kind;
            cooking = here;
        }
        if (cooking != null) {
            return vanilla(level, card, cooking);
        }
        if (Identifier.DEFAULT_NAMESPACE.equals(namespace)) {
            return null;
        }
        List<Want> wants = wants(card);
        if (wants.isEmpty()) {
            return null;
        }
        ItemStack made = main.item().toStack(1);
        List<Hit> hits = new ArrayList<>();
        for (RecipeType<?> type : RecipeReader.types(level, namespace)) {
            List<RecipeReader.Read> reads = RecipeReader.of(level, type);
            if (reads == null) {
                // a recipe of this mod couldn't be read, and it might be the card's
                return null;
            }
            for (RecipeReader.Read read : reads) {
                if (makes(read, made)) {
                    int[] pairs = pair(wants, read.inputs());
                    if (pairs != null) {
                        hits.add(new Hit(type, read, pairs));
                    }
                }
            }
        }
        return hits.isEmpty() ? furnace(level, card, wants, made) : modded(card, wants, hits);
    }

    /** The kind of recipe a vanilla cooking block cooks, or null for any other block. */
    private static @Nullable RecipeType<? extends AbstractCookingRecipe> cookingAt(@Nullable BlockEntity block) {
        if (block instanceof FurnaceBlockEntity) return RecipeType.SMELTING;
        if (block instanceof BlastFurnaceBlockEntity) return RecipeType.BLASTING;
        if (block instanceof SmokerBlockEntity) return RecipeType.SMOKING;
        return null;
    }

    /** A vanilla cooking block: the first slot whose item it cooks into the card's result, by that one recipe. */
    private static @Nullable Match vanilla(ServerLevel level, ProcessingCard card, RecipeType<? extends AbstractCookingRecipe> type) {
        ItemStack made = card.main().item().toStack(1);
        List<ProcessingCard.Amount> inputs = card.usedInputs();
        for (int i = 0; i < inputs.size(); i++) {
            ProcessingCard.Amount input = inputs.get(i);
            if (input.isFluid() || input.isMaterial()) {
                continue;
            }
            SingleRecipeInput single = new SingleRecipeInput(input.item().toStack(1));
            Optional<? extends RecipeHolder<? extends AbstractCookingRecipe>> found = level.getServer().getRecipeManager().getRecipeFor(type, single, level);
            if (found.isPresent() && ItemStack.isSameItemSameComponents(found.get().value().assemble(single), made)) {
                List<@Nullable Rule> slots = new ArrayList<>();
                for (int j = 0; j < inputs.size(); j++) {
                    slots.add(null);
                }
                Cooked cooked = new Cooked(type, found.get());
                slots.set(i, (lvl, candidate, stack) -> cooks(lvl, cooked, stack));
                return new Match(card, slots);
            }
        }
        return null;
    }

    /**
     * Another mod's machine that none of its mod's recipes fit: a furnace of its own if a vanilla cooking recipe turns the
     * card's one item into its result. Several kinds may (smelting and blasting both make iron from raw iron); they must
     * take the same items.
     */
    private static @Nullable Match furnace(ServerLevel level, ProcessingCard card, List<Want> wants, ItemStack made) {
        if (wants.size() != 1) {
            return null;
        }
        Want want = wants.getFirst();
        SingleRecipeInput single = new SingleRecipeInput(want.item().toStack(1));
        List<Cooked> found = new ArrayList<>();
        for (RecipeType<? extends AbstractCookingRecipe> type : COOKING) {
            level.getServer().getRecipeManager().recipeMap().getRecipesFor(type, single, level)
                    .filter(holder -> ItemStack.isSameItemSameComponents(holder.value().assemble(single), made))
                    .forEach(holder -> found.add(new Cooked(type, holder)));
        }
        if (found.isEmpty()) {
            return null;
        }
        Ingredient first = found.getFirst().recipe().value().input();
        if (!found.stream().allMatch(c -> sameItems(first, c.recipe().value().input()))) {
            return null;
        }
        List<@Nullable Rule> slots = emptySlots(card);
        slots.set(want.slot(), (lvl, candidate, stack) -> found.stream().allMatch(c -> cooks(lvl, c, stack)));
        return new Match(card, slots);
    }

    /** Whether {@code cooked} takes {@code stack}, and its machine would cook it with that recipe and no other. */
    private static boolean cooks(ServerLevel level, Cooked cooked, ItemStack stack) {
        if (!cooked.recipe().value().input().test(stack)) {
            return false;
        }
        List<? extends RecipeHolder<? extends AbstractCookingRecipe>> recipes = level.getServer().getRecipeManager().recipeMap()
                .getRecipesFor(cooked.type(), new SingleRecipeInput(stack), level).limit(2).toList();
        return recipes.size() == 1 && recipes.getFirst().id().equals(cooked.recipe().id());
    }

    /** Another mod's recipes that fit the card: the slots take more only when every one of them takes the same items. */
    private static @Nullable Match modded(ProcessingCard card, List<Want> wants, List<Hit> hits) {
        for (int w = 0; w < wants.size(); w++) {
            Ingredient first = input(hits.getFirst(), w).ingredient();
            for (Hit hit : hits) {
                if (!sameItems(first, input(hit, w).ingredient())) {
                    return null;
                }
            }
        }
        List<@Nullable Rule> slots = emptySlots(card);
        for (int w = 0; w < wants.size(); w++) {
            int index = w;
            slots.set(wants.get(w).slot(), (level, candidate, stack) -> hits.stream().allMatch(hit -> takes(level, hit, wants, index, candidate, stack)));
        }
        return new Match(card, slots);
    }

    /**
     * Whether {@code hit}'s recipe takes {@code candidate} in place of the card's item {@code w}: its input there takes
     * it, none of its other inputs does, and no other recipe of its kind fits the card with it there.
     */
    private static boolean takes(ServerLevel level, Hit hit, List<Want> wants, int w, ItemResource candidate, ItemStack stack) {
        List<RecipeReader.Input> inputs = hit.read().inputs();
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).ingredient().test(stack) != (i == hit.pairs()[w])) {
                return false;
            }
        }
        List<RecipeReader.Read> reads = RecipeReader.of(level, hit.type());
        if (reads == null) {
            return false;
        }
        List<Want> swapped = new ArrayList<>(wants);
        Want want = wants.get(w);
        swapped.set(w, new Want(want.slot(), candidate, want.count()));
        ResourceKey<Recipe<?>> id = hit.read().holder().id();
        for (RecipeReader.Read read : reads) {
            if (!read.holder().id().equals(id) && pair(swapped, read.inputs()) != null) {
                return false;
            }
        }
        return true;
    }

    private static RecipeReader.Input input(Hit hit, int w) {
        return hit.read().inputs().get(hit.pairs()[w]);
    }

    /** The card's item slots, by their place among the used inputs. */
    private static List<Want> wants(ProcessingCard card) {
        List<Want> wants = new ArrayList<>();
        List<ProcessingCard.Amount> inputs = card.usedInputs();
        for (int i = 0; i < inputs.size(); i++) {
            ProcessingCard.Amount input = inputs.get(i);
            if (!input.isFluid() && !input.isMaterial()) {
                wants.add(new Want(i, input.item(), input.count()));
            }
        }
        return wants;
    }

    private static List<@Nullable Rule> emptySlots(ProcessingCard card) {
        List<@Nullable Rule> slots = new ArrayList<>();
        for (int i = 0; i < card.usedInputs().size(); i++) {
            slots.add(null);
        }
        return slots;
    }

    /** Whether one of the reading's stacks is {@code made}, the card's first output (its amount may vary by chance). */
    private static boolean makes(RecipeReader.Read read, ItemStack made) {
        return read.outputs().stream().anyMatch(output -> ItemStack.isSameItemSameComponents(output.create(), made));
    }

    /**
     * Pairs each of the card's item slots with a different input of the recipe that takes its item, each slot holding the
     * same whole number of runs' worth. Returns the input each slot pairs with, or null if they don't pair off.
     */
    private static int @Nullable [] pair(List<Want> wants, List<RecipeReader.Input> inputs) {
        if (wants.size() != inputs.size()) {
            return null;
        }
        int[] pairs = new int[wants.size()];
        Arrays.fill(pairs, -1);
        List<ItemStack> stacks = wants.stream().map(w -> w.item().toStack(1)).toList();
        return pair(wants, stacks, inputs, 0, new boolean[inputs.size()], pairs, 0) ? pairs : null;
    }

    private static boolean pair(List<Want> wants, List<ItemStack> stacks, List<RecipeReader.Input> inputs, int w, boolean[] used,
            int[] pairs, int runs) {
        if (w == wants.size()) {
            return true;
        }
        Want want = wants.get(w);
        for (int i = 0; i < inputs.size(); i++) {
            RecipeReader.Input input = inputs.get(i);
            if (used[i] || want.count() % input.count() != 0) {
                continue;
            }
            int these = want.count() / input.count();
            if (runs != 0 && these != runs || !input.ingredient().test(stacks.get(w))) {
                continue;
            }
            used[i] = true;
            pairs[w] = i;
            if (pair(wants, stacks, inputs, w + 1, used, pairs, these)) {
                return true;
            }
            used[i] = false;
        }
        pairs[w] = -1;
        return false;
    }

    /** Whether two ingredients take exactly the same items. */
    private static boolean sameItems(Ingredient a, Ingredient b) {
        if (a.equals(b)) {
            return true;
        }
        return a.isSimple() && b.isSimple() && items(a).equals(items(b));
    }

    private static Set<Item> items(Ingredient ingredient) {
        return ingredient.items().map(Holder::value).collect(Collectors.toCollection(HashSet::new));
    }
}
