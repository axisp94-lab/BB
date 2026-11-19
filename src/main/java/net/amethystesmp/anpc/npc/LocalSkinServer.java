package net.amethystesmp.anpc.npc;

import kr.toxicity.model.api.profile.ModelProfile;
import kr.toxicity.model.api.profile.ModelProfileInfo;
import kr.toxicity.model.api.profile.ModelProfileSkin;
import net.amethystesmp.anpc.ANPCPlugin;

import com.sun.net.httpserver.HttpServer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocalSkinServer implements AutoCloseable {
    private static final long MAX_SKIN_BYTES = 1024L * 1024L;

    private final ANPCPlugin plugin;
    private final Path skinDirectory;
    private final ConcurrentHashMap<String, byte[]> skins = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    public LocalSkinServer(ANPCPlugin plugin) {
        this.plugin = plugin;
        this.skinDirectory = plugin.getDataFolder().toPath().resolve("npcs").toAbsolutePath().normalize();
    }

    public ModelProfile.Uncompleted profileFor(String requestedFile) {
        if (requestedFile == null || requestedFile.isBlank()) return null;

        try {
            Path directory = skinDirectory.toRealPath();
            Path candidate = directory.resolve(requestedFile).normalize();
            if (!candidate.startsWith(directory) || !candidate.getFileName().toString().equals(requestedFile)) {
                plugin.getLogger().warning("Chemin de skin refusé : " + requestedFile);
                return null;
            }

            Path file = candidate.toRealPath();
            if (!file.startsWith(directory) || !Files.isRegularFile(file)) {
                plugin.getLogger().warning("Fichier de skin introuvable : " + requestedFile);
                return null;
            }
            if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".png")) {
                plugin.getLogger().warning("Le skin local doit être un fichier PNG : " + requestedFile);
                return null;
            }

            long size = Files.size(file);
            if (size <= 0 || size > MAX_SKIN_BYTES) {
                plugin.getLogger().warning("Taille de skin PNG invalide : " + requestedFile);
                return null;
            }

            byte[] bytes = Files.readAllBytes(file);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != 64 || (image.getHeight() != 64 && image.getHeight() != 32)) {
                plugin.getLogger().warning("Dimensions invalides pour " + requestedFile + " : un skin 64x64 ou 64x32 est attendu.");
                return null;
            }

            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String url = registerSkin(digest, bytes);
            if (url == null) return null;

            boolean slim = requestedFile.toLowerCase(java.util.Locale.ROOT).matches(".*([-_]slim)\\.png$");
            UUID profileId = UUID.nameUUIDFromBytes((requestedFile + ":" + digest).getBytes(StandardCharsets.UTF_8));
            ModelProfileInfo info = new ModelProfileInfo(profileId, requestedFile);
            ModelProfileSkin skin = new ModelProfileSkin(URI.create(url), null, slim, "");
            return ModelProfile.of(info, skin).asUncompleted();
        } catch (Exception exception) {
            plugin.getLogger().warning("Impossible de charger le skin local '" + requestedFile + "' : " + exception.getMessage());
            return null;
        }
    }

    private synchronized String registerSkin(String digest, byte[] bytes) throws IOException {
        if (!skins.containsKey(digest)) skins.put(digest, bytes);
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            executor = Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "AmethyysNPCs-local-skins");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext("/skin/", exchange -> {
                String key = exchange.getRequestURI().getPath().substring("/skin/".length());
                byte[] skinBytes = skins.get(key);
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET");
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                    return;
                }
                if (skinBytes == null) {
                    exchange.sendResponseHeaders(404, -1);
                    exchange.close();
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(200, skinBytes.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(skinBytes);
                }
            });
            server.start();
            plugin.getLogger().info("Serveur local de skins démarré sur le port " + server.getAddress().getPort() + ".");
        }
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/skin/" + digest;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        skins.clear();
    }
}
