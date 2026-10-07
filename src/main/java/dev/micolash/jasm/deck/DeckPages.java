package dev.micolash.jasm.deck;

/**
 * Where each wafer slot sits in the Deck's side panel. Up to 24 slots make columns of up to 8 rows; past that the panel
 * shows 24 at a time and the rest go on further pages. Slots fill left to right, then down.
 */
public record DeckPages(int total) {
    public static final int PER_PAGE = DeckMenu.SIDE_ROWS * 3;

    public int pages() {
        return Math.max(1, (total + PER_PAGE - 1) / PER_PAGE);
    }

    public int pageOf(int slot) {
        return slot / PER_PAGE;
    }

    public int columns() {
        return total > PER_PAGE ? 3 : Math.max(1, (total + DeckMenu.SIDE_ROWS - 1) / DeckMenu.SIDE_ROWS);
    }

    public int rows() {
        return total > PER_PAGE ? DeckMenu.SIDE_ROWS : Math.max(1, (total + columns() - 1) / columns());
    }

    /** Slots fill each row from the left, then the next row down. */
    public int column(int slot) {
        return slot % PER_PAGE % columns();
    }

    public int row(int slot) {
        return slot % PER_PAGE / columns();
    }
}
