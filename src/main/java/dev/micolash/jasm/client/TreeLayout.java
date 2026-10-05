package dev.micolash.jasm.client;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Where each box of the crafting tree goes: rows by how far it is below the finished item (the deepest user decides),
 * and, inside a row, ordered by where the boxes above it sit so lines cross as little as is cheap to find. Plain numbers
 * only; drawing is done elsewhere.
 */
final class TreeLayout {
    private final int rows;
    private final int[] row;
    private final double[] column;
    private final double width;

    private TreeLayout(int rows, int[] row, double[] column, double width) {
        this.rows = rows;
        this.row = row;
        this.column = column;
        this.width = width;
    }

    int rows() {
        return rows;
    }

    int row(int box) {
        return row[box];
    }

    /** From the left edge, in box widths; a row narrower than the widest is centred, so this can end in .5. */
    double column(int box) {
        return column[box];
    }

    /** The box count of the widest row. */
    double width() {
        return width;
    }

    /** {@code links} are {from, to}: box {@code from} takes from box {@code to}. */
    static TreeLayout of(int boxCount, int root, int[][] links) {
        if (boxCount <= 0 || root < 0 || root >= boxCount) {
            return new TreeLayout(0, new int[0], new double[0], 0);
        }
        List<List<Integer>> users = new ArrayList<>();
        List<List<Integer>> parts = new ArrayList<>();
        for (int i = 0; i < boxCount; i++) {
            users.add(new ArrayList<>());
            parts.add(new ArrayList<>());
        }
        for (int[] link : links) {
            if (link[0] >= 0 && link[0] < boxCount && link[1] >= 0 && link[1] < boxCount && link[0] != link[1]) {
                parts.get(link[0]).add(link[1]);
                users.get(link[1]).add(link[0]);
            }
        }
        int[] row = new int[boxCount];
        Arrays.fill(row, -1);
        row[root] = 0;
        // Longest distance from the root. Passes are bounded, so a loop in bad data ends instead of hanging.
        boolean changed = true;
        for (int pass = 0; changed && pass <= boxCount; pass++) {
            changed = false;
            for (int from = 0; from < boxCount; from++) {
                if (row[from] < 0) {
                    continue;
                }
                for (int to : parts.get(from)) {
                    if (to != root && row[to] < row[from] + 1 && row[from] + 1 <= boxCount) {
                        row[to] = row[from] + 1;
                        changed = true;
                    }
                }
            }
        }
        for (int i = 0; i < boxCount; i++) {
            if (row[i] < 0) {
                row[i] = Math.min(1, boxCount - 1);
            }
        }
        int rows = 0;
        for (int r : row) {
            rows = Math.max(rows, r + 1);
        }
        List<List<Integer>> inRow = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            inRow.add(new ArrayList<>());
        }
        for (int i = 0; i < boxCount; i++) {
            inRow.get(row[i]).add(i);
        }
        // Top row first; each later row is ordered by the average spot of the boxes above that use it.
        double[] spot = new double[boxCount];
        double widest = 0;
        for (int r = 0; r < rows; r++) {
            List<Integer> boxes = inRow.get(r);
            if (r > 0) {
                int below = r;
                boxes.sort(Comparator.<Integer>comparingDouble(b -> average(users.get(b), spot, row, below)).thenComparingInt(b -> b));
            }
            for (int i = 0; i < boxes.size(); i++) {
                spot[boxes.get(i)] = i;
            }
            widest = Math.max(widest, boxes.size());
        }
        double[] column = new double[boxCount];
        for (List<Integer> boxes : inRow) {
            double shift = (widest - boxes.size()) / 2.0;
            for (int i = 0; i < boxes.size(); i++) {
                column[boxes.get(i)] = i + shift;
            }
        }
        return new TreeLayout(rows, row, column, widest);
    }

    /** The average spot of the boxes in rows above {@code r} that use a box; a box with none sorts last. */
    private static double average(List<Integer> users, double[] spot, int[] row, int r) {
        double sum = 0;
        int count = 0;
        for (int user : users) {
            if (row[user] < r) {
                sum += spot[user];
                count++;
            }
        }
        return count == 0 ? Double.MAX_VALUE : sum / count;
    }
}
