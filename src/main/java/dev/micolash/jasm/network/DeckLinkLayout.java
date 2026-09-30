package dev.micolash.jasm.network;

/** Slot coordinates shared by the Deck linking side panels. */
public record DeckLinkLayout(int width, int height) {
    public static final DeckLinkLayout TERMINAL = new DeckLinkLayout(72, 124);
    public static final DeckLinkLayout ARCHIVE = new DeckLinkLayout(100, 148);
    public static final DeckLinkLayout PORT = new DeckLinkLayout(82, 148);
    public static final int INPUT_Y = 32;
    public static final int OUTPUT_Y = 68;
    public static final int MESSAGE_Y = 92;
    public static final int RESET_Y = 122;

    public int panelX() { return -width - 2; }
    public int slotX() { return panelX() + (width - 16) / 2; }
}
