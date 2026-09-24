package dev.micolash.jasm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GridEntriesTest {
    private static final GridEntries.Entry<String> OAK = new GridEntries.Entry<>("oak", "Oak Log", "minecraft", 300);
    private static final GridEntries.Entry<String> BIRCH = new GridEntries.Entry<>("birch", "Birch Log", "minecraft", 40);
    private static final GridEntries.Entry<String> RUBY = new GridEntries.Entry<>("ruby", "Ruby", "gemmod", 300);
    private static final List<GridEntries.Entry<String>> ALL = List.of(OAK, BIRCH, RUBY);

    private static List<String> keys(List<GridEntries.Entry<String>> entries) {
        return entries.stream().map(GridEntries.Entry::key).toList();
    }

    @Test
    void searchMatchesNameWordsAndModPrefixes() {
        assertTrue(SearchQuery.parse("").matches("Anything", "any"));
        assertTrue(SearchQuery.parse("log").matches("Oak Log", "minecraft"));
        assertTrue(SearchQuery.parse("OAK").matches("Oak Log", "minecraft"), "case does not matter");
        assertFalse(SearchQuery.parse("oak birch").matches("Oak Log", "minecraft"), "every word must match");
        assertTrue(SearchQuery.parse("@mine").matches("Oak Log", "minecraft"), "mod prefix");
        assertFalse(SearchQuery.parse("@gem").matches("Oak Log", "minecraft"));
        assertTrue(SearchQuery.parse("@minecraft oak").matches("Oak Log", "minecraft"), "both combine");
        assertFalse(SearchQuery.parse("@minecraft ruby").matches("Ruby", "gemmod"));
        assertTrue(SearchQuery.parse("@ ").matches("Ruby", "gemmod"), "a lone @ matches everything");
    }

    @Test
    void sortsByNameEitherWay() {
        assertEquals(List.of("birch", "oak", "ruby"), keys(GridEntries.view(ALL, SearchQuery.ALL, GridEntries.Sort.NAME, true)));
        assertEquals(List.of("ruby", "oak", "birch"), keys(GridEntries.view(ALL, SearchQuery.ALL, GridEntries.Sort.NAME, false)));
    }

    @Test
    void sortsByAmountWithStableTies() {
        assertEquals(List.of("birch", "oak", "ruby"), keys(GridEntries.view(ALL, SearchQuery.ALL, GridEntries.Sort.AMOUNT, true)));
        assertEquals(List.of("oak", "ruby", "birch"), keys(GridEntries.view(ALL, SearchQuery.ALL, GridEntries.Sort.AMOUNT, false)),
                "most first, equal amounts still by name");
    }

    @Test
    void filtersBeforeSorting() {
        assertEquals(List.of("oak"), keys(GridEntries.view(ALL, SearchQuery.parse("@minecraft oak"), GridEntries.Sort.NAME, true)));
        assertEquals(List.of("ruby"), keys(GridEntries.view(ALL, SearchQuery.parse("@gem"), GridEntries.Sort.AMOUNT, false)));
    }

    @Test
    void abbreviatesCounts() {
        assertEquals("0", GridEntries.abbreviate(0));
        assertEquals("999", GridEntries.abbreviate(999));
        assertEquals("1K", GridEntries.abbreviate(1_000));
        assertEquals("1.2K", GridEntries.abbreviate(1_250));
        assertEquals("9.9K", GridEntries.abbreviate(9_999), "never rounds up past what is stored");
        assertEquals("12K", GridEntries.abbreviate(12_345));
        assertEquals("999K", GridEntries.abbreviate(999_999));
        assertEquals("1M", GridEntries.abbreviate(1_000_000));
        assertEquals("65K", GridEntries.abbreviate(65_536));
        assertEquals("1.5B", GridEntries.abbreviate(1_500_000_000L));
    }
}
