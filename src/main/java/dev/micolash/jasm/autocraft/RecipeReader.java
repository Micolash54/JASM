package dev.micolash.jasm.autocraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import dev.micolash.jasm.Jasm;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jspecify.annotations.Nullable;

/**
 * What other mods' recipes take and make, read from each recipe's own data: the shape a datapack writes it in. Only the
 * standard pieces are understood: an ingredient (a tag, an item, a list of items, or one of NeoForge's custom ones),
 * with a "count" beside it when it takes more than one, and a result stack ({@code {"id": ...}}). Anything else in a
 * recipe (energy, times, fluids, chances) is left alone. A kind of recipe with even one recipe that can't be written
 * out is not read at all, so nothing is ever decided on part of the picture.
 *
 * <p>A list of plain items can be one ingredient that takes any of them or several ingredients side by side, and the
 * data doesn't say which: such a recipe gets both readings.
 */
@EventBusSubscriber(modid = Jasm.MODID)
public final class RecipeReader {
    private RecipeReader() {}

    /** One item input of a recipe: what it takes, and how many each run. */
    record Input(Ingredient ingredient, int count) {}

    /** One reading of a recipe: its item inputs and the stacks it makes. */
    record Read(RecipeHolder<?> holder, List<Input> inputs, List<ItemStackTemplate> outputs) {}

    /** Fields every recipe may have that are never ingredients. */
    private static final Set<String> SKIP = Set.of("type", "group", "category", "show_notification");

    // The readings belong to one set of loaded recipes: a reload brings a new manager and they are read again.
    private static @Nullable RecipeManager manager;
    /** The readings of each kind of recipe read so far; empty for a kind that couldn't be read. */
    private static final Map<RecipeType<?>, Optional<List<Read>>> READ = new HashMap<>();
    /** The kinds of recipe each mod registered, by mod name. */
    private static final Map<String, List<RecipeType<?>>> TYPES = new HashMap<>();

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        forget();
    }

    private static void forget() {
        manager = null;
        READ.clear();
        TYPES.clear();
    }

    private static void current(ServerLevel level) {
        RecipeManager now = level.getServer().getRecipeManager();
        if (now != manager) {
            forget();
            manager = now;
        }
    }

    /** Every kind of recipe registered under {@code namespace}. */
    static List<RecipeType<?>> types(ServerLevel level, String namespace) {
        current(level);
        return TYPES.computeIfAbsent(namespace, ns -> BuiltInRegistries.RECIPE_TYPE.entrySet().stream()
                .filter(e -> e.getKey().identifier().getNamespace().equals(ns))
                .<RecipeType<?>>map(Map.Entry::getValue)
                .toList());
    }

    /** The readings of every recipe of {@code type}, or null when one of them couldn't be read. Read once per reload. */
    static @Nullable List<Read> of(ServerLevel level, RecipeType<?> type) {
        current(level);
        return READ.computeIfAbsent(type, t -> Optional.ofNullable(read(level, t))).orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static @Nullable List<Read> read(ServerLevel level, RecipeType<?> type) {
        RegistryOps<JsonElement> ops = level.registryAccess().createSerializationContext(JsonOps.INSTANCE);
        List<Read> reads = new ArrayList<>();
        Collection<RecipeHolder<?>> holders = (Collection<RecipeHolder<?>>) (Collection<?>) level.getServer().getRecipeManager().recipeMap()
                .byType((RecipeType) type);
        for (RecipeHolder<?> holder : holders) {
            JsonElement json;
            try {
                DataResult<JsonElement> written = Recipe.CODEC.encodeStart(ops, holder.value());
                if (written.isError()) {
                    return null;
                }
                json = written.getOrThrow();
            } catch (RuntimeException e) {
                return null;
            }
            Reading joined = new Reading(ops, false);
            joined.walk(json, true);
            reads.add(new Read(holder, List.copyOf(joined.inputs), List.copyOf(joined.outputs)));
            if (joined.lists) {
                Reading split = new Reading(ops, true);
                split.walk(json, true);
                reads.add(new Read(holder, List.copyOf(split.inputs), List.copyOf(split.outputs)));
            }
        }
        return List.copyOf(reads);
    }

    /** One pass over a recipe's data, taking a list of plain items as one ingredient or, with {@code split}, as one each. */
    private static final class Reading {
        private final RegistryOps<JsonElement> ops;
        private final boolean split;
        final List<Input> inputs = new ArrayList<>();
        final List<ItemStackTemplate> outputs = new ArrayList<>();
        /** Whether the recipe has a list of plain items, which could be read the other way too. */
        boolean lists;

        Reading(RegistryOps<JsonElement> ops, boolean split) {
            this.ops = ops;
            this.split = split;
        }

        void walk(JsonElement element, boolean top) {
            if (element instanceof JsonObject object) {
                if (object.has("id")) {
                    // a stack: something the recipe makes (or a fluid, which isn't an item and is left alone)
                    ItemStackTemplate.CODEC.parse(ops, object).result().ifPresent(outputs::add);
                    return;
                }
                List<Map.Entry<String, JsonElement>> entries = object.entrySet().stream()
                        .filter(e -> !(top && SKIP.contains(e.getKey())) && !e.getKey().equals("count"))
                        .toList();
                List<@Nullable Ingredient> found = new ArrayList<>(entries.size());
                int ingredients = 0;
                for (Map.Entry<String, JsonElement> entry : entries) {
                    Ingredient ingredient = ingredient(entry.getValue());
                    found.add(ingredient);
                    if (ingredient != null) ingredients++;
                }
                // a count beside a single ingredient is how many of it each run takes
                int count = ingredients == 1 ? count(object.get("count")) : 1;
                for (int i = 0; i < entries.size(); i++) {
                    Ingredient ingredient = found.get(i);
                    if (ingredient != null) {
                        add(ingredient, count);
                    } else {
                        walk(entries.get(i).getValue(), false);
                    }
                }
            } else if (element instanceof JsonArray array) {
                for (JsonElement item : array) {
                    Ingredient ingredient = ingredient(item);
                    if (ingredient != null) {
                        add(ingredient, 1);
                    } else {
                        walk(item, false);
                    }
                }
            }
        }

        /** An ingredient that takes nothing is no item input (a fluid tag written like an item tag, say). */
        private void add(Ingredient ingredient, int count) {
            if (count > 0 && !ingredient.isEmpty()) {
                inputs.add(new Input(ingredient, count));
            }
        }

        private static int count(@Nullable JsonElement element) {
            if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
                double value = primitive.getAsDouble();
                return value >= 1 && value == Math.floor(value) && value <= Integer.MAX_VALUE ? (int) value : 0;
            }
            return 1;
        }

        /** {@code element} as an ingredient, or null if it isn't one. */
        private @Nullable Ingredient ingredient(JsonElement element) {
            boolean candidate;
            if (element instanceof JsonPrimitive primitive) {
                candidate = primitive.isString();
            } else if (element instanceof JsonArray array) {
                candidate = !array.isEmpty() && array.asList().stream().allMatch(e -> e instanceof JsonPrimitive p && p.isString());
                if (candidate && array.size() > 1) {
                    lists = true;
                    if (split) {
                        // read as one ingredient per item: the walk takes them one by one
                        return null;
                    }
                }
            } else {
                candidate = element instanceof JsonObject object && object.has("neoforge:ingredient_type");
            }
            if (!candidate) {
                return null;
            }
            try {
                return Ingredient.CODEC.parse(ops, element).result().orElse(null);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }
}
