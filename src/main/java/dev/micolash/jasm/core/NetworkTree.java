package dev.micolash.jasm.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The network as a tree, for the Deck's Network tab. A cable that splits towards several machines shows as a junction.
 * The machines along one cable run sit side by side under that run's junction, and a row of touching machines stays at
 * one depth too; only a real split, of a cable or at a machine with two or more ways on, goes a step deeper. Plain
 * cables are left out.
 */
public final class NetworkTree {
    private NetworkTree() {}

    public interface Graph {
        /** Cells joined to this one, always in the same order. */
        List<Integer> neighbours(int cell);

        boolean machine(int cell);
    }

    /** One row: its cell, the row it hangs under (-1 for the root), how deep it is, and whether it is a junction. */
    public record Row(int cell, int parent, int depth, boolean junction) {}

    public static List<Row> build(int root, Graph graph) {
        Map<Integer, List<Integer>> children = new HashMap<>();
        Set<Integer> seen = new HashSet<>();
        List<Integer> order = new ArrayList<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        seen.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            int at = queue.poll();
            order.add(at);
            for (int next : graph.neighbours(at)) {
                if (seen.add(next)) {
                    children.computeIfAbsent(at, k -> new ArrayList<>()).add(next);
                    queue.add(next);
                }
            }
        }
        // Which cells have a machine at or beyond them.
        Set<Integer> leads = new HashSet<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            int cell = order.get(i);
            if (graph.machine(cell) || children.getOrDefault(cell, List.of()).stream().anyMatch(leads::contains)) {
                leads.add(cell);
            }
        }
        List<Row> rows = new ArrayList<>();
        // Depth-first, in order, without recursion: each entry is a cell, the row it hangs under, that row's depth + 1,
        // and 1 when that row is a cable run's junction, which takes in the machines further along the same run.
        ArrayDeque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] {root, -1, 0, 0});
        while (!stack.isEmpty()) {
            int[] at = stack.pop();
            int cell = at[0];
            int parent = at[1];
            int depth = at[2];
            boolean run = at[3] == 1;
            List<Integer> below = children.getOrDefault(cell, List.of()).stream().filter(leads::contains).toList();
            long paths = below.stream().filter(next -> !graph.machine(next)).count();
            if (cell == root || graph.machine(cell)) {
                rows.add(new Row(cell, parent, depth, false));
                // A row of touching machines carries on at the same depth; only a machine the row splits at goes deeper.
                if (cell == root || below.size() >= 2) {
                    parent = rows.size() - 1;
                    depth++;
                    run = false;
                }
            } else if (paths >= 2) {
                // The cable really splits: each way goes one step deeper.
                rows.add(new Row(cell, parent, depth, true));
                parent = rows.size() - 1;
                depth++;
                run = false;
            } else if (below.size() >= 2) {
                // Machines along one cable run sit side by side under the run's junction, nearest first.
                below = below.stream().sorted(Comparator.comparing(next -> !graph.machine(next))).toList();
                if (!run) {
                    rows.add(new Row(cell, parent, depth, true));
                    parent = rows.size() - 1;
                    depth++;
                    run = true;
                }
            }
            for (int i = below.size() - 1; i >= 0; i--) {
                stack.push(new int[] {below.get(i), parent, depth, run ? 1 : 0});
            }
        }
        return rows;
    }
}
