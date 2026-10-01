package dev.micolash.jasm.network;

import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * Client side: where the Deck Link window's two slots lie over a screen, relative to the screen's corner, or nothing
 * while the window is shut. Those slots work only while it is open, and a slot of the screen right under one of them
 * stops taking clicks so the click reaches the window's slot. Menus on the server never set it, so there every slot works.
 */
public final class LinkWindowCover {
    private int @Nullable [] slots;

    /** {inX, inY, outX, outY}, or null when the window shuts. */
    public void set(int @Nullable [] slots) {
        this.slots = slots;
    }

    public boolean open() {
        return slots != null;
    }

    /** Whether one of the window's slots lies over any part of {@code slot}. */
    public boolean covers(Slot slot) {
        if (slots == null) return false;
        for (int i = 0; i < 4; i += 2) {
            if (slot.x + 16 > slots[i] && slot.x < slots[i] + 16 && slot.y + 16 > slots[i + 1] && slot.y < slots[i + 1] + 16) return true;
        }
        return false;
    }
}
