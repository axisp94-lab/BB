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

public final class FlyingPathfinder {
    private record Node(int x, int y, int z) { }
    private record QueueEntry(Node node, double cost, double estimate) { }

    private FlyingPathfinder() { }

    public static List<Location> findPath(Location start, Location target,
                                          int maxNodes, double maxDistance,
                                          double checkpointSpacing) {
        if (start == null || target == null || start.getWorld() == null
                || !start.getWorld().equals(target.getWorld())) return List.of();

        World world = start.getWorld();
        if (start.distance(target) > maxDistance) return List.of();

        Node startNode = findFlyable(world, start.getBlockX(), start.getBlockY(), start.getBlockZ());
        Node targetNode = findFlyable(world, target.getBlockX(), target.getBlockY(), target.getBlockZ());
        if (startNode == null || targetNode == null) return List.of();
        if (startNode.equals(targetNode)) {
            if (target.getBlockX() == targetNode.x() && target.getBlockY() == targetNode.y()
                    && target.getBlockZ() == targetNode.z()) return List.of(target.clone());
            return List.of(toLocation(world, targetNode));
        }

        PriorityQueue<QueueEntry> open = new PriorityQueue<>(Comparator.comparingDouble(QueueEntry::estimate));
        Map<Node, Double> scores = new HashMap<>();
        Map<Node, Node> previous = new HashMap<>();
        Set<Node> closed = new HashSet<>();

        scores.put(startNode, 0.0);
        open.add(new QueueEntry(startNode, 0.0, distance(startNode, targetNode)));

        int visited = 0;
        boolean found = false;
        while (!open.isEmpty() && visited < Math.max(1, maxNodes)) {
            QueueEntry entry = open.poll();
            Node current = entry.node();
            if (closed.contains(current) || entry.cost() > scores.getOrDefault(current, Double.POSITIVE_INFINITY)) continue;
            if (current.equals(targetNode)) {
                found = true;
                break;
            }

            closed.add(current);
            visited++;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Node next = new Node(current.x() + dx, current.y() + dy, current.z() + dz);
                        if (closed.contains(next) || !isFlyable(world, next)) continue;
                        if (!diagonalIsClear(world, current, dx, dy, dz)) continue;
                        if (distance(startNode, next) > maxDistance) continue;

                        double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        double tentative = scores.get(current) + step;
                        if (tentative >= scores.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;

                        previous.put(next, current);
                        scores.put(next, tentative);
                        open.add(new QueueEntry(next, tentative, tentative + distance(next, targetNode)));
                    }
                }
            }
        }

        if (!found) return List.of();
        List<Node> path = reconstruct(previous, startNode, targetNode);
        return checkpoints(world, path, targetNode, target, Math.max(0.5, checkpointSpacing));
    }

    private static Node findFlyable(World world, int x, int y, int z) {
        int[][] offsets = {
                {0, 0, 0}, {0, 1, 0}, {0, -1, 0},
                {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
        };
        for (int[] offset : offsets) {
            Node candidate = new Node(x + offset[0], y + offset[1], z + offset[2]);
            if (isFlyable(world, candidate)) return candidate;
        }
        return null;
    }

    private static boolean isFlyable(World world, Node node) {
        if (node.y() < world.getMinHeight() || node.y() + 1 >= world.getMaxHeight()) return false;
        if (!world.isChunkLoaded(node.x() >> 4, node.z() >> 4)) return false;
        Block feet = world.getBlockAt(node.x(), node.y(), node.z());
        Block head = world.getBlockAt(node.x(), node.y() + 1, node.z());
        return feet.isPassable() && head.isPassable() && !feet.isLiquid() && !head.isLiquid();
    }

    /**
     * Prevents a diagonal step from cutting through a block corner.
     * Every intermediate cell formed by a non-empty subset of the changed
     * axes must be flyable. For a 3-axis diagonal this checks all six
     * intermediate cells, not only the three axis-aligned neighbours.
     */
    private static boolean diagonalIsClear(World world, Node current, int dx, int dy, int dz) {
        int[] offsets = {dx, dy, dz};
        int changedAxes = 0;
        for (int offset : offsets) {
            if (offset != 0) changedAxes++;
        }
        if (changedAxes <= 1) return true;

        int combinations = 1 << 3;
        for (int mask = 1; mask < combinations - 1; mask++) {
            int x = current.x();
            int y = current.y();
            int z = current.z();
            if ((mask & 1) != 0) x += dx;
            if ((mask & 2) != 0) y += dy;
            if ((mask & 4) != 0) z += dz;

            // Ignore masks that do not select any axis that actually changes.
            if (((mask & 1) == 0 || dx == 0)
                    && ((mask & 2) == 0 || dy == 0)
                    && ((mask & 4) == 0 || dz == 0)) continue;
            if (!isFlyable(world, new Node(x, y, z))) return false;
        }
        return true;
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

    private static List<Location> checkpoints(World world, List<Node> path, Node targetNode,
                                               Location target, double spacing) {
        if (path.isEmpty()) return List.of();
        List<Location> result = new ArrayList<>();
        Node lastAdded = path.get(0);

        for (int i = 1; i < path.size() - 1; i++) {
            Node before = path.get(i - 1);
            Node current = path.get(i);
            Node after = path.get(i + 1);
            boolean turn = current.x() - before.x() != after.x() - current.x()
                    || current.y() - before.y() != after.y() - current.y()
                    || current.z() - before.z() != after.z() - current.z();
            if (turn || distance(lastAdded, current) >= spacing) {
                result.add(toLocation(world, current));
                lastAdded = current;
            }
        }

        if (target.getBlockX() == targetNode.x() && target.getBlockY() == targetNode.y()
                && target.getBlockZ() == targetNode.z()) result.add(target.clone());
        else result.add(toLocation(world, targetNode));
        return result;
    }

    private static Location toLocation(World world, Node node) {
        return new Location(world, node.x() + 0.5, node.y(), node.z() + 0.5);
    }
}
