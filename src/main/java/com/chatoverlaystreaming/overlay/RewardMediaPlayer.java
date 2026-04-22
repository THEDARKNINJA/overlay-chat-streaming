package com.chatoverlaystreaming.overlay;

import javafx.application.Platform;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.paint.Color;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.WritableImage;
import org.json.JSONObject;

import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class RewardMediaPlayer {

    private static final String[] AUDIO_EXTS = {".wav", ".mp3", ".aac", ".ogg", ".flac"};
    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".webm"};
    private static final Path REWARDS_DIR = Paths.get("rewards");

    // Archivos que han dado error en esta sesión, por rewardId
    private static final Map<String, Set<Path>> failedFiles = new ConcurrentHashMap<>();

    // Inicializar JavaFX, necesario todo esto y usar el ensureFX antes de play() porque si no, no carga los vídeos 2 o más
    private static boolean fxInitialized = false;
    private static void ensureFX() {
        if (!fxInitialized) {
            fxInitialized = true;
            SwingUtilities.invokeLater(() -> {
                new JFXPanel(); // inicializa JavaFX
                Platform.setImplicitExit(false); 
            });
        }
    }

    public static void play(String rewardId, JSONObject rewardConfig) {
        String type        = rewardConfig.optString("type", "audio");
        String path        = rewardConfig.optString("path", "");
        boolean recursive  = rewardConfig.optBoolean("recursive", false);
        String playMode    = rewardConfig.optString("playMode", "random");
        double volume      = rewardConfig.optDouble("volume", 1.0);
        System.out.println("[Media] Volumen: " + volume);
        int width          = rewardConfig.optInt("width", 480);
        int height         = rewardConfig.optInt("height", 270);
        int displayIndex   = rewardConfig.optInt("displayIndex", 0);
        int fps            = rewardConfig.optInt("fps", 30);
        String windowTitle = rewardConfig.optString("windowTitle", "VideoOverlay");
        boolean chromaEnabled   = rewardConfig.optBoolean("chromaEnabled", false);
        int     chromaColorRgb  = rewardConfig.optInt("chromaColor", 0x00FF00);
        int     chromaTolerance = rewardConfig.optInt("chromaTolerance", 40);
        java.awt.Color chromaColor     = new java.awt.Color(chromaColorRgb);
        int vidPosX        = rewardConfig.optInt("posX", 0);
        int vidPosY        = rewardConfig.optInt("posY", 0);
        boolean randomPos  = rewardConfig.optBoolean("randomPos", false);

        if (path.isBlank()) {
            System.err.println("[Media] Path vacío para recompensa: " + rewardId);
            return;
        }

        Path dir = Paths.get(path);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.err.println("[Media] Carpeta no encontrada: " + path);
            return;
        }

        try {
            String[] validExts = "audio".equals(type) ? AUDIO_EXTS : VIDEO_EXTS;
            List<Path> allFiles = listFiles(dir, recursive, validExts);

            if (allFiles.isEmpty()) {
                System.err.println("[Media] No hay archivos en: " + path);
                return;
            }

            // Intentar reproducir excluyendo los fallidos
            playWithFallback(rewardId, allFiles, playMode, type,
                             volume, width, height, displayIndex, fps, windowTitle,
                            chromaEnabled, chromaColor, chromaTolerance, vidPosX, vidPosY, randomPos);

        } catch (Exception e) {
            System.err.println("[Media] Error listando archivos: " + e.getMessage());
        }
    }

    /**
     * Intenta reproducir un archivo. Si falla, lo marca como fallido
     * y prueba con el siguiente candidato hasta agotar opciones.
     */
    private static void playWithFallback(String rewardId,
                                          List<Path> allFiles,
                                          String playMode,
                                          String type,
                                          double volume,
                                          int width, int height,
                                          int displayIndex, int fps, String windowTitle,
                                          boolean chromaEnabled, java.awt.Color chromaColor, int chromaTolerance, int vidPosX, int vidPosY, boolean randomPos) {
        Set<Path> failed = failedFiles.computeIfAbsent(
                rewardId, k -> ConcurrentHashMap.newKeySet());

        // Candidatos: todos menos los fallidos
        List<Path> candidates = allFiles.stream()
                .filter(f -> !failed.contains(f))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            System.err.println("[Media] No quedan archivos reproducibles para: "
                    + rewardId + ". Limpiando lista de fallidos para el próximo intento.");
            // Resetear para que la próxima vez lo intente de nuevo
            // (puede que los archivos se hayan reparado)
            failed.clear();
            return;
        }

        // Elegir según el modo, solo entre los candidatos
        Path chosen;
        try {
            chosen = switch (playMode) {
                case "sequential"        -> chooseSequential(rewardId, candidates);
                case "random_no_repeat"  -> chooseRandomNoRepeat(rewardId, candidates);
                default                  -> chooseRandom(candidates);
            };
        } catch (Exception e) {
            System.err.println("[Media] Error eligiendo archivo: " + e.getMessage());
            return;
        }

        if (chosen == null) {
            System.err.println("[Media] No se pudo elegir archivo.");
            return;
        }

        System.out.println("[Media] Intentando reproducir: " + chosen);

        if ("audio".equals(type)) {
            playAudioWithFallback(chosen, volume,
                    rewardId, allFiles, playMode, type,
                    volume, width, height, displayIndex, fps, windowTitle);
        } else {
            playVideoWithFallback(chosen, volume, width, height, displayIndex, fps,
                    rewardId, allFiles, playMode, type, windowTitle,
                    chromaEnabled, chromaColor, chromaTolerance, vidPosX, vidPosY, randomPos);
        }
    }

    private static void playAudioWithFallback(Path file, double volume,
                                               String rewardId,
                                               List<Path> allFiles,
                                               String playMode, String type,
                                               double vol,
                                               int width, int height,
                                               int displayIndex, int fps, String windowTitle) {
        Platform.runLater(() -> {
            try {
                Media media        = new Media(file.toUri().toString());
                MediaPlayer player = new MediaPlayer(media);
                player.setVolume(volume);

                player.setOnReady(() -> {
                    // Archivo OK, registrar reproducción
                    try { registerPlay(rewardId, file); }
                    catch (Exception e) {
                        System.err.println("[Media] Error registrando: " + e.getMessage());
                    }
                    player.play();
                });

                player.setOnEndOfMedia(player::dispose);

                player.setOnError(() -> {
                    String msg = player.getError() != null
                            ? player.getError().getMessage()
                            : "desconocido";
                    System.err.println("[Media] Error reproduciendo audio '"
                            + file.getFileName() + "': " + msg);
                    player.dispose();

                    // Marcar como fallido y reintentar
                    failedFiles.computeIfAbsent(rewardId,
                            k -> ConcurrentHashMap.newKeySet()).add(file);

                    // Reintentar en el hilo de JavaFX con un pequeño delay
                    // para no colapsar si todos fallan
                    new Thread(() -> {
                        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                        playWithFallback(rewardId, allFiles, playMode, type,
                                vol, width, height, displayIndex, fps, windowTitle,
                                false, null, 0, 0, 0, false);
                    }, "media-retry").start();
                });

            } catch (Exception e) {
                System.err.println("[Media] Excepción creando MediaPlayer para '"
                        + file.getFileName() + "': " + e.getMessage());

                failedFiles.computeIfAbsent(rewardId,
                        k -> ConcurrentHashMap.newKeySet()).add(file);

                new Thread(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    playWithFallback(rewardId, allFiles, playMode, type,
                            vol, width, height, displayIndex, fps, windowTitle,
                            false, null, 0, 0, 0, false);
                }, "media-retry").start();
            }
        });
    }

    private static void playVideoWithFallback(Path file, double volume,
                                               int width, int height,
                                               int displayIndex, int fps,
                                               String rewardId,
                                               List<Path> allFiles,
                                               String playMode, String type, String windowTitle,
                                               boolean chromaEnabled, java.awt.Color chromaColor, int chromaTolerance, 
                                               int vidPosX, int vidPosY, boolean randomPos) {
                         
        SwingUtilities.invokeLater(() -> {
            VideoPlayerWindow overlay;

            if (VlcjDetector.isAvailable()) {
                overlay = new VlcjVideoOverlay(file, volume, width, height,
                                                displayIndex, fps, windowTitle, vidPosX, vidPosY, randomPos);
            } else {
	            ensureFX();
                overlay = new VideoOverlay(file, volume, width, height,
                                            displayIndex, fps, windowTitle, vidPosX, vidPosY, randomPos);
            }

            if (chromaEnabled) {
                overlay.setChroma(true, chromaColor, chromaTolerance);
                System.out.println("[Media] Chroma activado: color=" + chromaColor
                        + " tolerancia=" + chromaTolerance);
            }

            // Callback que VideoOverlay llama si hay error de reproducción
            overlay.setOnError(errorMsg -> {
                System.err.println("[Media] Error reproduciendo vídeo '"
                        + file.getFileName() + "': " + errorMsg);

                failedFiles.computeIfAbsent(rewardId,
                        k -> ConcurrentHashMap.newKeySet()).add(file);

                new Thread(() -> {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                    playWithFallback(rewardId, allFiles, playMode, type,
                            volume, width, height, displayIndex, fps, windowTitle,
                            chromaEnabled, chromaColor, chromaTolerance, vidPosX, vidPosY, randomPos);
                }, "media-retry").start();
            });

            // Callback cuando el vídeo carga bien
            overlay.setOnReady(() -> {
                try { registerPlay(rewardId, file); }
                catch (Exception e) {
                    System.err.println("[Media] Error registrando: " + e.getMessage());
                }
            });

            overlay.setVisible(true);
            /*
            if (overlay instanceof VlcjVideoOverlay vlcjOverlay) {
                vlcjOverlay.applyWindowColorKey(chromaColor);
            } */
            overlay.play();
        });
    }

    // ── Listado y selección ──────────────────────────────────────────────────

    private static List<Path> listFiles(Path dir, boolean recursive,
                                         String[] extensions) throws IOException {
        var stream = recursive ? Files.walk(dir) : Files.list(dir);
        return stream
                .filter(p -> !Files.isDirectory(p))
                .filter(p -> hasExtension(p, extensions))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());
    }

    private static boolean hasExtension(Path p, String[] extensions) {
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : extensions) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private static Path chooseRandom(List<Path> files) {
        return files.get(new Random().nextInt(files.size()));
    }

    private static Path chooseSequential(String rewardId,
                                          List<Path> candidates) throws IOException {
        // Necesitamos los stats del universo completo para comparar,
        // pero solo elegimos entre los candidatos
        JSONObject stats = loadStats(rewardId, candidates);
        Path chosen  = null;
        int minPlays = Integer.MAX_VALUE;

        for (Path file : candidates) {
            int plays = stats.optInt(fileKey(file), 0);
            if (plays < minPlays) {
                minPlays = plays;
                chosen   = file;
            }
        }
        return chosen;
    }

    private static Path chooseRandomNoRepeat(String rewardId,
                                              List<Path> candidates) throws IOException {
        JSONObject stats = loadStats(rewardId, candidates);

        int minPlays = Integer.MAX_VALUE;
        for (Path file : candidates) {
            int plays = stats.optInt(fileKey(file), 0);
            if (plays < minPlays) minPlays = plays;
        }

        final int min = minPlays;
        List<Path> minCandidates = candidates.stream()
                .filter(f -> stats.optInt(fileKey(f), 0) == min)
                .collect(Collectors.toList());

        return minCandidates.get(new Random().nextInt(minCandidates.size()));
    }

    // ── Estadísticas ─────────────────────────────────────────────────────────

    private static String fileKey(Path file) {
        return file.getFileName().toString();
    }

    private static Path statsPath(String rewardId) {
        return REWARDS_DIR.resolve(rewardId + ".json");
    }

    private static JSONObject loadStats(String rewardId,
                                         List<Path> currentFiles) throws IOException {
        Path statsFile = statsPath(rewardId);
        JSONObject stats = Files.exists(statsFile)
                ? new JSONObject(Files.readString(statsFile, StandardCharsets.UTF_8))
                : new JSONObject();

        Set<String> currentKeys = new HashSet<>();
        for (Path f : currentFiles) currentKeys.add(fileKey(f));

        for (String key : currentKeys) {
            if (!stats.has(key)) stats.put(key, 0);
        }

        Set<String> toRemove = new HashSet<>();
        for (String key : stats.keySet()) {
            if (!currentKeys.contains(key)) toRemove.add(key);
        }
        toRemove.forEach(stats::remove);

        saveStats(rewardId, stats);
        return stats;
    }

    private static void registerPlay(String rewardId, Path file) throws IOException {
        Path statsFile = statsPath(rewardId);
        JSONObject stats = Files.exists(statsFile)
                ? new JSONObject(Files.readString(statsFile, StandardCharsets.UTF_8))
                : new JSONObject();

        String key = fileKey(file);
        stats.put(key, stats.optInt(key, 0) + 1);
        saveStats(rewardId, stats);
    }

    private static void saveStats(String rewardId, JSONObject stats) throws IOException {
        if (!Files.exists(REWARDS_DIR)) Files.createDirectories(REWARDS_DIR);
        Files.writeString(statsPath(rewardId), stats.toString(2), StandardCharsets.UTF_8);
    }
}