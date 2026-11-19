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

/**
 * Pathfinding for movement modes that use a non-standard support surface.
 * All world reads happen on the calling thread; callers must invoke this from
 * the Bukkit main thread.
 */
public final class AdvancedPathfinder {
    public enum Mode {
        CLIMB,
        SWIM,
        AMPHIBIOUS
    }

    private record Node(int x, int y, int z) { }
    private record Entry(Node node, double cost, double estimate) { }

    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private AdvancedPathfinder() { }

    public static List<Location> findPath(Location start, Location target, Mode mode,
                                          int maxNodes, double maxDistance, double spacing) {
        if (start == null || target == null || mode == null || start.getWorld() == null
                || !start.getWorld().equals(target.getWorld())) return List.of();
        if (start.distance(target) > maxDistance) return List.of();

        World world = start.getWorld();
        Node startNode = findNearbyNode(world, start, mode);
        Node targetNode = findNearbyNode(world, target, mode);
        if (startNode == null || targetNode == null) return List.of();
        if (startNode.equals(targetNode)) return List.of(toLocation(world, targetNode, target));

        PriorityQueue<Entry> open = new PriorityQueue<>(Comparator.comparingDouble(Entry::estimate));
        Map<Node, Double> scores = new HashMap<>();
        Map<Node, Node> previous = new HashMap<>();
        Set<Node> closed = new HashSet<>();
        scores.put(startNode, 0.0);
        open.add(new Entry(startNode, 0.0, heuristic(startNode, targetNode)));

        boolean found = false;
        int visited = 0;
        int nodeLimit = Math.max(1, maxNodes);
        while (!open.isEmpty() && visited < nodeLimit) {
            Entry entry = open.poll();
            Node current = entry.node();
            if (closed.contains(current)
                    || entry.cost() > scores.getOrDefault(current, Double.POSITIVE_INFINITY)) continue;
            if (current.equals(targetNode)) {
                found = true;
                break;
            }

            closed.add(current);
            visited++;
            for (int[] direction : DIRECTIONS) {
                Node next = new Node(current.x() + direction[0],
                        current.y() + direction[1], current.z() + direction[2]);
                if (closed.contains(next) || !isTraversable(world, next, mode)) continue;
                if (distance(startNode, next) > maxDistance) continue;

                double stepCost = direction[1] == 0 ? 1.0 : 1.15;
                double candidate = scores.get(current) + stepCost;
                if (candidate >= scores.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;

                scores.put(next, candidate);
                previous.put(next, current);
                open.add(new Entry(next, candidate, candidate + heuristic(next, targetNode)));
            }
        }

        if (!found) return List.of();
        List<Node> path = reconstruct(previous, startNode, targetNode);
        return checkpoints(world, path, targetNode, target, Math.max(0.5, spacing));
    }

    private static Node findNearbyNode(World world, Location location, Mode mode) {
        int baseX = location.getBlockX();
        int baseY = location.getBlockY();
        int baseZ = location.getBlockZ();
        Node best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    Node candidate = new Node(baseX + dx, baseY + dy, baseZ + dz);
                    if (!isTraversable(world, candidate, mode)) continue;
                    double distance = distanceToBlockCenter(location, candidate);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    private static boolean isTraversable(World world, Node node, Mode mode) {
        if (node.y() <= world.getMinHeight() || node.y() + 1 >= world.getMaxHeight()) return false;
        if (!world.isChunkLoaded(node.x() >> 4, node.z() >> 4)) return false;

        Block feet = world.getBlockAt(node.x(), node.y(), node.z());
        Block head = world.getBlockAt(node.x(), node.y() + 1, node.z());
        boolean bodyClear = (feet.isPassable() || feet.isLiquid())
                && (head.isPassable() || head.isLiquid());

        boolean swimming = bodyClear && feet.isLiquid() && head.isLiquid();
        boolean climbing = bodyClear && hasClimbSurface(world, node);
        boolean walking = feet.isPassable() && head.isPassable()
                && !feet.isLiquid() && !head.isLiquid()
                && world.getBlockAt(node.x(), node.y() - 1, node.z()).getType().isSolid();

        return switch (mode) {
            case CLIMB -> climbing;
            case SWIM -> swimming;
            case AMPHIBIOUS -> walking || swimming || climbing;
        };
    }

    private static boolean hasClimbSurface(World world, Node node) {
        return isSolidLoaded(world, node.x() + 1, node.y(), node.z())
                || isSolidLoaded(world, node.x() - 1, node.y(), node.z())
                || isSolidLoaded(world, node.x(), node.y(), node.z() + 1)
                || isSolidLoaded(world, node.x(), node.y(), node.z() - 1);
    }

    private static boolean isSolidLoaded(World world, int x, int y, int z) {
        return world.isChunkLoaded(x >> 4, z >> 4)
                && world.getBlockAt(x, y, z).getType().isSolid();
    }

    private static double distanceToBlockCenter(Location location, Node node) {
        double dx = location.getX() - (node.x() + 0.5);
        double dy = location.getY() - node.y();
        double dz = location.getZ() - (node.z() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double heuristic(Node a, Node b) {
        return distance(a, b);
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
                                               Location requested, double spacing) {
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
                result.add(toLocation(world, current, null));
                lastAdded = current;
            }
        }
        result.add(toLocation(world, targetNode, requested));
        return result;
    }

    private static Location toLocation(World world, Node node, Location requested) {
        if (requested != null && requested.getBlockX() == node.x()
                && requested.getBlockY() == node.y() && requested.getBlockZ() == node.z()) {
            return requested.clone();
        }
        return new Location(world, node.x() + 0.5, node.y(), node.z() + 0.5);
    }
}
