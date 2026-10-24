package dev.micolash.jasm.crafting;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/** A menu with a crafting grid whose result the player can pick, when more than one recipe fits. */
public interface RecipePicking {
    /** Client side: the results the server last listed for this screen. */
    View pickerView();

    /** Server side: the player clicked one of the listed results. Ignored unless it still fits the grid. */
    void pickRecipe(ServerPlayer player, ResourceKey<Recipe<?>> id);

    /** Client side: what the screen shows, as the server last sent it. */
    final class View {
        private List<PickerPayloads.Entry> entries = List.of();
        private int current = -1;

        public void set(List<PickerPayloads.Entry> entries, int current) {
            this.entries = List.copyOf(entries);
            this.current = current;
        }

        public List<PickerPayloads.Entry> entries() {
            return entries;
        }

        /** Index of the result in use, or -1. */
        public int current() {
            return current;
        }

        /** The key shows only when there is something to choose between. */
        public boolean choosable() {
            return entries.size() > 1;
        }
    }

    /** Server side, one per open screen: sends the list when it differs from what that screen last got. */
    final class Sync {
        private @Nullable List<PickerPayloads.Entry> sent;
        private int sentCurrent = -1;

        public void send(ServerPlayer player, int containerId, List<RecipeChoices.Choice> choices, int current) {
            List<PickerPayloads.Entry> now = choices.stream().map(c -> new PickerPayloads.Entry(c.id(), c.result())).toList();
            if (sent != null && current == sentCurrent && same(sent, now)) {
                return;
            }
            sent = now;
            sentCurrent = current;
            if (player.connection.hasChannel(PickerPayloads.Shown.TYPE)) {
                PacketDistributor.sendToPlayer(player, new PickerPayloads.Shown(containerId, now, current));
            }
        }

        private static boolean same(List<PickerPayloads.Entry> a, List<PickerPayloads.Entry> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!a.get(i).id().equals(b.get(i).id()) || !ItemStack.matches(a.get(i).result(), b.get(i).result())) {
                    return false;
                }
            }
            return true;
        }
    }
}
