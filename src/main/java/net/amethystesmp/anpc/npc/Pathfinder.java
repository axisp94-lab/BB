package net.amethystesmp.anpc.npc;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class Pathfinder {
    private record Node(int x, int y, int z) { }
    private record QueueEntry(Node node, double cost, double estimate) { }

    private static final int[][] DIRECTIONS = {
            {-1, 0}, {0, -1}, {0, 1}, {1, 0},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };

    private Pathfinder() { }

    public static List<Location> findPath(Location start, Location target,
                                          int maxNodes, double maxDistance,
                                          double checkpointSpacing) {
        if (start == null || target == null || start.getWorld() == null
                || !start.getWorld().equals(target.getWorld())) return List.of();

        World world = start.getWorld();
        if (start.distance(target) > maxDistance) return List.of();

        Node startNode = findStandable(world, start.getBlockX(), start.getBlockY(), start.getBlockZ());
        Node targetNode = findStandable(world, target.getBlockX(), target.getBlockY(), target.getBlockZ());
        if (startNode == null || targetNode == null) return List.of();

        if (startNode.equals(targetNode)) return List.of(targetLocation(world, targetNode, target));

        PriorityQueue<QueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::estimate));
        Map<Node, Double> scores = new HashMap<>();
        Map<Node, Node> previous = new HashMap<>();
        Set<Node> closed = new HashSet<>();

        scores.put(startNode, 0.0);
        open.add(new QueueEntry(startNode, 0.0, heuristic(startNode, targetNode)));

        int visited = 0;
        boolean found = false;
        while (!open.isEmpty() && visited < Math.max(1, maxNodes)) {
            QueueEntry entry = open.poll();
            Node current = entry.node();
            if (closed.contains(current)) continue;

            double bestKnown = scores.getOrDefault(current, Double.POSITIVE_INFINITY);
            if (entry.cost() > bestKnown) continue;
            if (current.equals(targetNode)) {
                found = true;
                break;
            }

            closed.add(current);
            visited++;

            for (int[] direction : DIRECTIONS) {
                int dx = direction[0];
                int dz = direction[1];
                for (int dy = -1; dy <= 1; dy++) {
                    Node next = new Node(current.x() + dx, current.y() + dy, current.z() + dz);
                    if (closed.contains(next) || !isStandable(world, next)) continue;

                    if (!transitionIsClear(world, current, dx, dy, dz)) continue;

                    if (distance(startNode, next) > maxDistance) continue;
                    double stepCost = Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.25;
                    double tentative = bestKnown + stepCost;
                    if (tentative >= scores.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;

                    previous.put(next, current);
                    scores.put(next, tentative);
                    open.add(new QueueEntry(next, tentative, tentative + heuristic(next, targetNode)));
                }
            }
        }

        if (!found) return List.of();
        List<Node> nodes = reconstruct(previous, startNode, targetNode);
        return toCheckpoints(world, nodes, targetNode, target, Math.max(0.5, checkpointSpacing));
    }

    /**
     * Checks every intermediate cell of a diagonal/vertical transition.
     * This prevents a step that changes multiple axes from clipping through
     * a wall or the corner of a block.
     */
    private static boolean transitionIsClear(World world, Node current, int dx, int dy, int dz) {
        int[][] changes = new int[3][];
        int count = 0;
        if (dx != 0) changes[count++] = new int[]{dx, 0, 0};
        if (dy != 0) changes[count++] = new int[]{0, dy, 0};
        if (dz != 0) changes[count++] = new int[]{0, 0, dz};
        if (count <= 1) return true;

        int combinations = 1 << count;
        for (int mask = 1; mask < combinations - 1; mask++) {
            int x = current.x();
            int y = current.y();
            int z = current.z();
            for (int axis = 0; axis < count; axis++) {
                if ((mask & (1 << axis)) == 0) continue;
                x += changes[axis][0];
                y += changes[axis][1];
                z += changes[axis][2];
            }
            if (!isStandable(world, new Node(x, y, z))) return false;
        }
        return true;
    }

    private static Node findStandable(World world, int x, int y, int z) {
        int[] offsets = {0, -1, 1, -2, 2};
        for (int offset : offsets) {
            Node candidate = new Node(x, y + offset, z);
            if (isStandable(world, candidate)) return candidate;
        }
        return null;
    }

    private static boolean isStandable(World world, Node node) {
        int feetY = node.y();
        if (feetY <= world.getMinHeight() || feetY + 1 >= world.getMaxHeight()) return false;
        if (!world.isChunkLoaded(node.x() >> 4, node.z() >> 4)) return false;

        Block floor = world.getBlockAt(node.x(), feetY - 1, node.z());
        Block feet = world.getBlockAt(node.x(), feetY, node.z());
        Block head = world.getBlockAt(node.x(), feetY + 1, node.z());

        return floor.getType().isSolid()
                && feet.isPassable() && head.isPassable()
                && !feet.isLiquid() && !head.isLiquid();
    }

    private static double heuristic(Node a, Node b) {
        int dx = a.x() - b.x();
        int dz = a.z() - b.z();
        int dy = a.y() - b.y();
        // Lower bound matching the movement cost: horizontal Euclidean
        // distance plus 1.25 per vertical block. The previous 3D distance
        // plus 0.5 * dy overestimated purely vertical moves.
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 1.25;
    }

    private static double distance(Node a, Node b) {
        int dx = a.x() - b.x();
        int dy = a.y() - b.y();
        int dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static List<Node> reconstruct(Map<Node, Node> previous, Node start, Node target) {
        List<Node> path = new ArrayList<>();
        Node current = target;
        path.add(current);
        while (!current.equals(start)) {
            current = previous.get(current);
            if (current == null) return List.of();
            path.add(current);
        }
        Collections.reverse(path);
        return path;
    }

    private static List<Location> toCheckpoints(World world, List<Node> path, Node targetNode,
                                                Location target, double spacing) {
        if (path.isEmpty()) return List.of();
        List<Location> result = new ArrayList<>();
        Node lastAdded = path.get(0);

        for (int i = 1; i < path.size() - 1; i++) {
            Node before = path.get(i - 1);
            Node current = path.get(i);
            Node after = path.get(i + 1);

            int dxBefore = current.x() - before.x();
            int dyBefore = current.y() - before.y();
            int dzBefore = current.z() - before.z();
            int dxAfter = after.x() - current.x();
            int dyAfter = after.y() - current.y();
            int dzAfter = after.z() - current.z();

            boolean turn = dxBefore != dxAfter || dyBefore != dyAfter || dzBefore != dzAfter;
            if (turn || distance(lastAdded, current) >= spacing) {
                result.add(toLocation(world, current));
                lastAdded = current;
            }
        }

        result.add(targetLocation(world, targetNode, target));
        return result;
    }

    private static Location targetLocation(World world, Node targetNode, Location requested) {
        if (requested.getBlockX() == targetNode.x() && requested.getBlockY() == targetNode.y()
                && requested.getBlockZ() == targetNode.z()) return requested.clone();
        return toLocation(world, targetNode);
    }

    private static Location toLocation(World world, Node node) {
        return new Location(world, node.x() + 0.5, node.y(), node.z() + 0.5);
    }
}
