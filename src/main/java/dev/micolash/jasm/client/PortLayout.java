package dev.micolash.jasm.client;

import dev.micolash.jasm.transfer.TransferPortMenu;
import net.minecraft.client.Minecraft;

/**
 * The one height every port screen shares, picked by the GUI scale the game uses. At scale 4 and up the filter lists
 * show a row and a bit of the next, so the screen fits a maximised 1080p window; below that they show two whole rows.
 * Only the client decides this, the server never looks at where slots sit.
 */
record PortLayout(int listHeight, int inventoryY) {
    /** The smallest GUI scale that shows the short list. */
    static final int SHORT_FROM_SCALE = 4;
    /** How much of the second row shows under the first one. */
    private static final int PEEK = 21;

    static PortLayout current() {
        boolean shortList = Minecraft.getInstance().getWindow().getGuiScale() >= SHORT_FROM_SCALE;
        int list = shortList ? TransferPortMenu.FILTER_ROW + PEEK : 2 * TransferPortMenu.FILTER_ROW;
        return new PortLayout(list, TransferPortMenu.FILTER_TOP + TransferPortMenu.filterFrame(list) + 12);
    }

    int height() { return inventoryY + 84; }
}
