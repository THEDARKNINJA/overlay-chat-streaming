package com.chatoverlaystreaming.overlay;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.json.JSONObject;

import javax.swing.*;
import java.awt.Color;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Gestiona la reproducción de audio y vídeo asociados a recompensas de canal.
 *
 * Características principales:
 * - Soporta modos de reproducción: aleatorio, secuencial y aleatorio sin repetir.
 * - Mantiene estadísticas de reproducción por recompensa en archivos JSON en rewards/.
 * - Si un archivo falla, lo marca como fallido en sesión y prueba el siguiente.
 * - Para vídeo usa vlcj si VLC está instalado, y JavaFX como fallback.
 * - JavaFX se inicializa de forma lazy y una sola vez (Platform.setImplicitExit=false
 *   para que no destruya el toolkit al cerrar el primer reproductor).
 */
public class RewardMediaPlayer {

    // ── Constantes ────────────────────────────────────────────────────────────

    private static final String[] AUDIO_EXTS = {".wav", ".mp3", ".aac", ".ogg", ".flac"};
    private static final String[] VIDEO_EXTS = {".mp4", ".mkv", ".avi", ".mov", ".webm"};
    private static final Path     REWARDS_DIR = Paths.get("rewards");

    // ── Estado de sesión ──────────────────────────────────────────────────────

    /**
     * Archivos que han fallado durante esta sesión, agrupados por rewardId.
     * Al agotarse todos los candidatos se limpia para permitir reintentos.
     */
    private static final Map<String, Set<Path>> failedFiles = new ConcurrentHashMap<>();

    /** Flag de inicialización de JavaFX. Solo se inicializa una vez. */
    private static boolean fxInitialized = false;

    // ── Record de contexto de reproducción ───────────────────────────────────

    /**
     * Agrupa todos los parámetros de una reproducción para evitar
     * firmas de métodos con 16+ parámetros.
     */
    private record PlayContext(
            String   rewardId,
            String   type,
            String   playMode,
            double   volume,
            int      width,
            int      height,
            int      displayIndex,
            int      fps,
            String   windowTitle,
            boolean  chromaEnabled,
            Color    chromaColor,
            int      chromaTolerance,
            int      posX,
            int      posY,
            boolean  randomPos,
            boolean usedFxFailed   // true si JavaFX ya falló para este archivo
    ) {
        /** Construye un PlayContext desde la configuración JSON de una recompensa. */
        static PlayContext fromConfig(String rewardId, JSONObject cfg) {
            int chromaRgb = cfg.optInt("chromaColor", 0x00FF00);
            return new PlayContext(
                    rewardId,
                    cfg.optString("type",        "audio"),
                    cfg.optString("playMode",    "random"),
                    cfg.optDouble("volume",       1.0),
                    cfg.optInt("width",          480),
                    cfg.optInt("height",         270),
                    cfg.optInt("displayIndex",     0),
                    cfg.optInt("fps",             30),
                    cfg.optString("windowTitle", "VideoOverlay"),
                    cfg.optBoolean("chromaEnabled", false),
                    new Color(chromaRgb),
                    cfg.optInt("chromaTolerance", 40),
                    cfg.optInt("posX",             0),
                    cfg.optInt("posY",             0),
                    cfg.optBoolean("randomPos",  false),
                    false   // usedFxFailed: empieza como false
            );
        }
        /** Devuelve una copia del contexto marcando que JavaFX ya falló para este archivo. */
        PlayContext withFxFailed() {
            return new PlayContext(
                    rewardId(), type(), playMode(), volume(),
                    width(), height(), displayIndex(), fps(), windowTitle(),
                    chromaEnabled(), chromaColor(), chromaTolerance(),
                    posX(), posY(), randomPos(),
                    true  // usedFxFailed = true
            );
        }
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Punto de entrada principal. Lee la configuración, lista los archivos
     * disponibles y lanza la reproducción con fallback.
     *
     * @param rewardId    ID de la recompensa (para las estadísticas).
     * @param rewardConfig JSONObject con la configuración local de la recompensa.
     */
    public static void play(String rewardId, JSONObject rewardConfig) {
        PlayContext ctx = PlayContext.fromConfig(rewardId, rewardConfig);

        String path = rewardConfig.optString("path", "");
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
            String[]   exts     = "audio".equals(ctx.type()) ? AUDIO_EXTS : VIDEO_EXTS;
            List<Path> allFiles = listFiles(dir, rewardConfig.optBoolean("recursive", false), exts);

            if (allFiles.isEmpty()) {
                System.err.println("[Media] No hay archivos en: " + path);
                return;
            }

            playWithFallback(ctx, allFiles);

        } catch (Exception e) {
            System.err.println("[Media] Error listando archivos: " + e.getMessage());
        }
    }

    // ── Reproducción con fallback ─────────────────────────────────────────────

    /**
     * Elige un archivo según el modo de reproducción y lo lanza.
     * Si falla, lo marca como fallido y reintenta con el siguiente candidato.
     * Cuando se agotan todos los candidatos, limpia la lista para la próxima vez.
     *
     * @param ctx      Contexto con todos los parámetros de reproducción.
     * @param allFiles Lista completa de archivos de la carpeta.
     */
    private static void playWithFallback(PlayContext ctx, List<Path> allFiles) {
        Set<Path> failed = failedFiles.computeIfAbsent(
                ctx.rewardId(), k -> ConcurrentHashMap.newKeySet());

        List<Path> candidates = allFiles.stream()
                .filter(f -> !failed.contains(f))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) {
            System.err.println("[Media] No quedan archivos reproducibles para '"
                    + ctx.rewardId() + "'. Limpiando fallidos para el próximo intento.");
            failed.clear();
            return;
        }

        Path chosen = selectFile(ctx, candidates);
        if (chosen == null) {
            System.err.println("[Media] No se pudo elegir archivo para: " + ctx.rewardId());
            return;
        }

        System.out.println("[Media] Reproduciendo: " + chosen.getFileName());

        if ("audio".equals(ctx.type())) {
            playAudio(chosen, ctx, allFiles);
        } else {
            playVideo(chosen, ctx, allFiles);
        }
    }

    /**
     * Selecciona un archivo de la lista de candidatos según el modo de reproducción.
     *
     * @param ctx        Contexto con el playMode.
     * @param candidates Archivos disponibles (sin los fallidos).
     * @return El archivo elegido, o null si hay error.
     */
    private static Path selectFile(PlayContext ctx, List<Path> candidates) {
        try {
            return switch (ctx.playMode()) {
                case "sequential"       -> chooseSequential(ctx.rewardId(), candidates);
                case "random_no_repeat" -> chooseRandomNoRepeat(ctx.rewardId(), candidates);
                default                 -> chooseRandom(candidates);
            };
        } catch (Exception e) {
            System.err.println("[Media] Error eligiendo archivo: " + e.getMessage());
            return null;
        }
    }

    /**
     * Reproduce un archivo de audio con JavaFX MediaPlayer.
     * Si falla, marca el archivo como fallido y reintenta con el siguiente.
     */
    private static void playAudio(Path file, PlayContext ctx, List<Path> allFiles) {
        ensureFX();
        Platform.runLater(() -> {
            try {
                Media       media  = new Media(file.toUri().toString());
                MediaPlayer player = new MediaPlayer(media);
                player.setVolume(ctx.volume());

                player.setOnReady(() -> {
                    registerPlaySilently(ctx.rewardId(), file);
                    player.play();
                });

                player.setOnEndOfMedia(player::dispose);

                player.setOnError(() -> {
                    String msg = player.getError() != null
                            ? player.getError().getMessage() : "desconocido";
                    System.err.println("[Media] Error audio '"
                            + file.getFileName() + "': " + msg);
                    player.dispose();
                    markFailedAndRetry(file, ctx, allFiles);
                });

            } catch (Exception e) {
                System.err.println("[Media] Excepción creando MediaPlayer para '"
                        + file.getFileName() + "': " + e.getMessage());
                markFailedAndRetry(file, ctx, allFiles);
            }
        });
    }

    /**
     * Reproduce un archivo de vídeo en un VideoPlayerWindow.
     * Usa vlcj si está disponible, JavaFX como fallback.
     * Si falla, marca el archivo como fallido y reintenta con el siguiente.
     */
    private static void playVideo(Path file, PlayContext ctx, List<Path> allFiles) {
        SwingUtilities.invokeLater(() -> {
            VideoPlayerWindow overlay = createVideoOverlay(file, ctx);

            if (ctx.chromaEnabled()) {
                overlay.setChroma(true, ctx.chromaColor(), ctx.chromaTolerance());
                System.out.println("[Media] Chroma activado: color=" + ctx.chromaColor()
                        + " tolerancia=" + ctx.chromaTolerance());
            }

            overlay.setOnError(errorMsg -> {
                System.err.println("[Media] Error vídeo '"
                        + file.getFileName() + "': " + errorMsg);
                // Si era JavaFX con chroma y vlcj está disponible,
                // reintentar el MISMO archivo con vlcj antes de marcarlo como fallido
                if (ctx.chromaEnabled() && !ctx.usedFxFailed()
                        && VlcjDetector.isAvailable()) {
                    System.out.println("[Media] JavaFX falló con chroma, "
                            + "reintentando con vlcj: " + file.getFileName());
                    Thread.ofVirtual().name("media-retry-vlcj").start(() ->
                        playVideo(file, ctx.withFxFailed(), allFiles));
                } else {
                    // Error definitivo: marcar como fallido y probar otro archivo
                    markFailedAndRetry(file, ctx, allFiles);
                }
            });

            overlay.setOnReady(() -> registerPlaySilently(ctx.rewardId(), file));
            overlay.setVisible(true);
            overlay.play();
        });
    }

    /**
     * Instancia el reproductor de vídeo apropiado.
     *
     * Prioridad:
     *   - Si el chroma está activo: JavaFX primero (chroma de mayor calidad),
     *     vlcj como fallback si JavaFX no puede reproducir el archivo.
     *   - Si el chroma no está activo: vlcj primero (mayor compatibilidad
     *     de codecs), JavaFX como fallback si VLC no está instalado.
     */
    private static VideoPlayerWindow createVideoOverlay(Path file, PlayContext ctx) {
        if (ctx.chromaEnabled() && !ctx.usedFxFailed()) {
            // Con chroma: preferir JavaFX por calidad visual
            ensureFX();
            return new VideoOverlay(file, ctx.volume(), ctx.width(), ctx.height(),
                    ctx.displayIndex(), ctx.fps(), ctx.windowTitle(),
                    ctx.posX(), ctx.posY(), ctx.randomPos());
        } else if (VlcjDetector.isAvailable()) {
            // Sin chroma: preferir vlcj por compatibilidad de codecs
            return new VlcjVideoOverlay(file, ctx.volume(), ctx.width(), ctx.height(),
                    ctx.displayIndex(), ctx.fps(), ctx.windowTitle(),
                    ctx.posX(), ctx.posY(), ctx.randomPos());
        } else {
            ensureFX();
            return new VideoOverlay(file, ctx.volume(), ctx.width(), ctx.height(),
                    ctx.displayIndex(), ctx.fps(), ctx.windowTitle(),
                    ctx.posX(), ctx.posY(), ctx.randomPos());
        }
    }

    /**
     * Marca un archivo como fallido en sesión y lanza un reintento
     * con el siguiente candidato tras un pequeño delay.
     * El delay evita bucles de reintento demasiado rápidos si todos fallan.
     */
    private static void markFailedAndRetry(Path file, PlayContext ctx, List<Path> allFiles) {
        failedFiles.computeIfAbsent(ctx.rewardId(),
                k -> ConcurrentHashMap.newKeySet()).add(file);

        Thread.ofVirtual().name("media-retry").start(() -> {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            playWithFallback(ctx, allFiles);
        });
    }

    // ── Inicialización de JavaFX ──────────────────────────────────────────────

    /**
     * Inicializa el toolkit de JavaFX de forma lazy y thread-safe.
     * Platform.setImplicitExit(false) es necesario para que el toolkit
     * no se destruya al cerrar el primer MediaPlayer, lo que impediría
     * reproducir audio o vídeo más de una vez.
     */
    private static synchronized void ensureFX() {
        if (fxInitialized) return;
        fxInitialized = true;
        SwingUtilities.invokeLater(() -> {
            new JFXPanel();
            Platform.setImplicitExit(false);
        });
    }

    // ── Listado de archivos ───────────────────────────────────────────────────

    /**
     * Lista los archivos de un directorio con las extensiones dadas.
     * El resultado se ordena alfabéticamente por nombre de archivo,
     * lo que garantiza un orden determinista para el modo secuencial.
     *
     * @param dir        Directorio raíz.
     * @param recursive  Si true, incluye subdirectorios.
     * @param extensions Extensiones válidas (ej. ".mp3", ".wav").
     */
    private static List<Path> listFiles(Path dir, boolean recursive,
                                         String[] extensions) throws IOException {
        try (var stream = recursive ? Files.walk(dir) : Files.list(dir)) {
            return stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> hasExtension(p, extensions))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    /** Comprueba si un archivo tiene alguna de las extensiones dadas (case-insensitive). */
    private static boolean hasExtension(Path p, String[] extensions) {
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : extensions) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    // ── Selección de archivos ─────────────────────────────────────────────────

    /** Selección aleatoria pura. */
    private static Path chooseRandom(List<Path> files) {
        return files.get(new Random().nextInt(files.size()));
    }

    /**
     * Selección secuencial: elige el archivo con menos reproducciones.
     * En caso de empate, gana el primero por orden alfabético
     * (que es el orden en que llegan los candidatos).
     */
    private static Path chooseSequential(String rewardId,
                                          List<Path> candidates) throws IOException {
        JSONObject stats    = loadStats(rewardId, candidates);
        Path       chosen   = null;
        int        minPlays = Integer.MAX_VALUE;

        for (Path file : candidates) {
            int plays = stats.optInt(fileKey(file), 0);
            if (plays < minPlays) {
                minPlays = plays;
                chosen   = file;
            }
        }
        return chosen;
    }

    /**
     * Selección aleatoria sin repetir: elige aleatoriamente entre los archivos
     * con menos reproducciones, garantizando que todos se reproduzcan
     * antes de empezar a repetir.
     */
    private static Path chooseRandomNoRepeat(String rewardId,
                                              List<Path> candidates) throws IOException {
        JSONObject stats    = loadStats(rewardId, candidates);
        int        minPlays = candidates.stream()
                .mapToInt(f -> stats.optInt(fileKey(f), 0))
                .min()
                .orElse(0);

        List<Path> minCandidates = candidates.stream()
                .filter(f -> stats.optInt(fileKey(f), 0) == minPlays)
                .collect(Collectors.toList());

        return minCandidates.get(new Random().nextInt(minCandidates.size()));
    }

    // ── Estadísticas de reproducción ─────────────────────────────────────────

    /** Clave de un archivo en el JSON de estadísticas (nombre del archivo). */
    private static String fileKey(Path file) {
        return file.getFileName().toString();
    }

    /** Ruta del archivo JSON de estadísticas para una recompensa. */
    private static Path statsPath(String rewardId) {
        return REWARDS_DIR.resolve(rewardId + ".json");
    }

    /**
     * Carga el JSON de estadísticas de una recompensa y lo sincroniza
     * con los archivos actuales: añade los nuevos con contador 0 y
     * elimina los que ya no existen.
     *
     * @param rewardId     ID de la recompensa.
     * @param currentFiles Lista actual de archivos válidos.
     */
    private static JSONObject loadStats(String rewardId,
                                         List<Path> currentFiles) throws IOException {
        Path       statsFile = statsPath(rewardId);
        JSONObject stats     = Files.exists(statsFile)
                ? new JSONObject(Files.readString(statsFile, StandardCharsets.UTF_8))
                : new JSONObject();

        Set<String> currentKeys = new HashSet<>();
        for (Path f : currentFiles) currentKeys.add(fileKey(f));

        // Añadir archivos nuevos con contador 0
        for (String key : currentKeys) {
            if (!stats.has(key)) stats.put(key, 0);
        }

        // Eliminar archivos que ya no existen en el directorio
        stats.keySet().stream()
                .filter(k -> !currentKeys.contains(k))
                .collect(Collectors.toList())
                .forEach(stats::remove);

        saveStats(rewardId, stats);
        return stats;
    }

    /**
     * Incrementa el contador de reproducciones de un archivo en el JSON de estadísticas.
     * Llamado cuando el archivo se ha cargado correctamente (onReady).
     */
    private static void registerPlay(String rewardId, Path file) throws IOException {
        Path       statsFile = statsPath(rewardId);
        JSONObject stats     = Files.exists(statsFile)
                ? new JSONObject(Files.readString(statsFile, StandardCharsets.UTF_8))
                : new JSONObject();

        String key = fileKey(file);
        stats.put(key, stats.optInt(key, 0) + 1);
        saveStats(rewardId, stats);
    }

    /**
     * Variante de registerPlay que absorbe la IOException para usarse
     * en contextos donde no se puede lanzar excepción (lambdas de onReady).
     */
    private static void registerPlaySilently(String rewardId, Path file) {
        try {
            registerPlay(rewardId, file);
        } catch (IOException e) {
            System.err.println("[Media] Error registrando reproducción: " + e.getMessage());
        }
    }

    /**
     * Guarda el JSON de estadísticas en disco, creando el directorio si es necesario.
     */
    private static void saveStats(String rewardId, JSONObject stats) throws IOException {
        if (!Files.exists(REWARDS_DIR)) Files.createDirectories(REWARDS_DIR);
        Files.writeString(statsPath(rewardId), stats.toString(2), StandardCharsets.UTF_8);
    }
}