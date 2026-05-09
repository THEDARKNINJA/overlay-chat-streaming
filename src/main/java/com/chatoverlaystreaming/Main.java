package com.chatoverlaystreaming;

import com.chatoverlaystreaming.emotes.ImageCache;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.overlay.ChatOverlay;
import com.chatoverlaystreaming.overlay.Config;
import com.chatoverlaystreaming.overlay.ObsAwareDialog;
import com.chatoverlaystreaming.overlay.TwitchAuth;
import com.chatoverlaystreaming.readers.TwitchChatReader;
import com.chatoverlaystreaming.readers.TwitchEventSub;
import com.chatoverlaystreaming.readers.YouTubeChatReader;
import com.chatoverlaystreaming.service.ViewerCountService;

import javax.swing.*;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Punto de entrada principal de Chat Overlay.
 *
 * <p>Secuencia de arranque:
 * <ol>
 *   <li>Inicializa el {@link Logger} para capturar toda la salida desde el inicio.</li>
 *   <li>Procesa archivos pendientes del Launcher ({@code --pending-launcher-file=})
 *       si los hay, copiándolos en background.</li>
 *   <li>Registra un shutdown hook para cerrar el logger al salir.</li>
 *   <li>Inicializa el toolkit de JavaFX (necesario antes de cualquier componente FX).</li>
 *   <li>Carga el {@link Config}. Si no existe, crea uno por defecto.
 *       Si el log está desactivado en config, lo cierra aquí.</li>
 *   <li>Lanza la UI del overlay y, si están habilitadas, muestra los botones
 *       de conexión a Twitch y YouTube.</li>
 * </ol>
 */
public class Main {

    /**
     * Punto de entrada de la aplicación.
     *
     * @param args Argumentos de línea de comandos. Se procesan los de la forma
     *             {@code --pending-launcher-file=src|dest}, pasados por el Launcher
     *             para indicar archivos que deben copiarse una vez que éste haya cerrado.
     */
    public static void main(String[] args) {

        // ── Logger ────────────────────────────────────────────────────────────
        // Se inicializa antes de todo para capturar también los errores de arranque
        Logger.init();

        // ── Archivos pendientes del Launcher ──────────────────────────────────
        // Si el Launcher no pudo sobreescribirse a sí mismo durante una actualización,
        // nos pasa los archivos como argumentos para que los copiemos nosotros
        // una vez que él haya cerrado
        for (String arg : args) {
            if (arg.startsWith("--pending-launcher-file=")) {
                String   entry = arg.substring("--pending-launcher-file=".length());
                String[] parts = entry.split("\\|", 2);
                if (parts.length == 2) {
                    applyPendingLauncherFile(Path.of(parts[0]), Path.of(parts[1]));
                }
            }
        }

        // ── Shutdown hook ─────────────────────────────────────────────────────
        Runtime.getRuntime().addShutdownHook(new Thread(Logger::close));

        // ── Inicialización de JavaFX ──────────────────────────────────────────
        // El toolkit de JavaFX debe inicializarse antes de crear cualquier componente FX.
        // Se hace desde el EDT con un latch para garantizar que termina antes de continuar.
        CountDownLatch fxLatch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            new javax.swing.JPanel();                  // asegurar que el EDT está activo
            new javafx.embed.swing.JFXPanel();         // inicializar toolkit JavaFX
            fxLatch.countDown();
        });
        try {
            fxLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // ── Carga del config ──────────────────────────────────────────────────
        Config  config   = null;
        boolean configOk = true;
        try {
            config = new Config();
            // Si el log está desactivado en config, cerrar la escritura en disco
            if (!config.getLogActivity()) Logger.close();
        } catch (Exception e) {
            System.err.println("[Main] Error cargando config: " + e.getMessage());
            try {
                config = Config.createDefault();
            } catch (Exception ex) {
                System.err.println("[Main] No se pudo crear config por defecto: "
                        + ex.getMessage());
                return;
            }
            configOk = false;
        }

        final Config  finalConfig   = config;
        final boolean finalConfigOk = configOk;

        // ── Recursos compartidos ──────────────────────────────────────────────
        BlockingQueue<ChatMessage> queue           = new LinkedBlockingQueue<>(500);
        ImageCache                 sharedImageCache = new ImageCache(config.getIconSize());

        // Holder para el EventSub de Twitch, creado durante connectTwitch() pero
        // necesario en el overlay para habilitar el panel de recompensas
        TwitchEventSub[] eventSubHolder = {null};

        // ── UI ────────────────────────────────────────────────────────────────
        SwingUtilities.invokeLater(() -> {
            ChatOverlay overlay = new ChatOverlay(
                    queue,
                    finalConfig.getTwitchChannelId(),
                    finalConfig.getTwitchClientId(),
                    finalConfig.getTwitchClientSecret(),
                    finalConfig, sharedImageCache);

            overlay.setVisible(true);
            overlay.initNativeFeatures();
            overlay.setIconImage(new ImageIcon("icon.png").getImage());

            // ── Servicio de espectadores ──────────────────────────────────────
            if (finalConfig.getShowViewerCount()) {
                ViewerCountService viewerService = new ViewerCountService(
                        finalConfig,
                        count -> SwingUtilities.invokeLater(() ->
                                overlay.setTwitchViewersLabel(count)),
                        count -> SwingUtilities.invokeLater(() ->
                                overlay.setYoutubeViewersLabel(count))
                );
                overlay.setViewerCountService(viewerService);
            }

            // ── Botones de conexión ───────────────────────────────────────────
            // Cada plataforma habilitada muestra su botón; el usuario decide cuándo conectar
            if (finalConfig.isTwitchEnabled()) {
                overlay.showTwitchButton();
                overlay.setTwitchConnectAction(() ->
                        connectTwitch(finalConfig, queue, sharedImageCache,
                                overlay, eventSubHolder));
            }

            if (finalConfig.isYoutubeEnabled()) {
                overlay.showYoutubeButton();
                overlay.setYoutubeConnectAction(() ->
                        connectYoutube(finalConfig, queue, sharedImageCache, overlay));
            }

            if (!finalConfig.isTwitchEnabled() && !finalConfig.isYoutubeEnabled()) {
                overlay.appendSystemMessage(
                        "⚠ Ninguna plataforma habilitada. Actívala alguna en ⚙ Configuración.");
            }
        });
    }

    // ── Conexión a Twitch ─────────────────────────────────────────────────────

    /**
     * Inicia la conexión a Twitch en un hilo virtual.
     *
     * <p>Intenta obtener un token OAuth. Si falla, conecta en modo anónimo
     * (sin recompensas ni EventSub). Si la conexión tiene éxito, arranca también
     * el cliente de EventSub para recibir recompensas de canal.
     *
     * @param config         Configuración de la aplicación.
     * @param queue          Cola de mensajes compartida.
     * @param sharedImageCache Cache de imágenes compartida.
     * @param overlay        Ventana del overlay para actualizar la UI.
     * @param eventSubHolder Array de un elemento donde guardar la referencia al EventSub.
     */
    private static void connectTwitch(Config config,
                                      BlockingQueue<ChatMessage> queue,
                                      ImageCache sharedImageCache,
                                      ChatOverlay overlay,
                                      TwitchEventSub[] eventSubHolder) {
        Thread.ofVirtual().name("twitch-connect").start(() -> {
            String accessToken = null;
            String twitchLogin = null;

            // Intentar OAuth; si falla, continuar en modo anónimo
            try {
                TwitchAuth auth = new TwitchAuth(config.getTwitchClientId(), config);
                accessToken = auth.getValidToken();
                twitchLogin = auth.getLoginFromToken(accessToken);
                final String login = twitchLogin;
                SwingUtilities.invokeLater(() ->
                        overlay.appendSystemMessage(
                                "✔ Twitch OAuth OK — conectado como " + login));
            } catch (Exception e) {
                System.err.println("[Auth] Error OAuth: " + e.getMessage());
                SwingUtilities.invokeLater(() ->
                        overlay.appendSystemMessage(
                                "⚠ OAuth falló (" + e.getMessage() + ") — " +
                                "conectando en modo anónimo con funcionalidad limitada. " +
                                "Pulsa el botón de Twitch para reintentar OAuth."));
            }

            // Lanzar el reader (con token OAuth si está disponible, anónimo si no)
            AtomicReference<Thread> readerThread = new AtomicReference<>();
            try {
                String error = launchTwitchReader(config, queue,
                        accessToken, twitchLogin, readerThread);

                if (error != null) {
                    SwingUtilities.invokeLater(() -> {
                        overlay.appendSystemMessage("✖ " + error);
                        overlay.showTwitchButton();
                        overlay.enableTwitchButton();
                    });
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Conexión confirmada
            if (accessToken != null) {
                // Con OAuth: mostrar contador de espectadores
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage("✔ Conectado al chat de Twitch.");
                    overlay.showTwitchViewers();
                    overlay.startTwitchViewers();
                });
            } else {
                // Modo anónimo: botón naranja para que el usuario reintente OAuth
                SwingUtilities.invokeLater(() ->
                        overlay.appendSystemMessage(
                                "✔ Conectado al chat de Twitch en modo anónimo."));
                SwingUtilities.invokeLater(overlay::showTwitchButtonAnon);
            }

            // EventSub solo disponible con OAuth
            if (accessToken != null) {
                try {
                    TwitchEventSub eventSub = new TwitchEventSub(
                            accessToken, config.getTwitchClientId(),
                            config.getTwitchChannelId(), queue);
                    eventSubHolder[0] = eventSub;
                    Thread t = new Thread(eventSub, "twitch-eventsub");
                    t.setDaemon(true);
                    t.start();
                    SwingUtilities.invokeLater(() -> {
                        overlay.setEventSub(eventSub);
                        overlay.enableRewardsButton();
                        overlay.appendSystemMessage("✔ EventSub conectado. Recompensas activas.");
                    });
                } catch (Exception e) {
                    System.err.println("[Main] Error EventSub: " + e.getMessage());
                    SwingUtilities.invokeLater(() ->
                            overlay.appendSystemMessage(
                                    "⚠ EventSub no disponible: " + e.getMessage()));
                }
            }
        });
    }

    /**
     * Crea y arranca un {@link TwitchChatReader} y espera hasta 10 segundos
     * a que confirme la conexión al canal (JOIN recibido).
     *
     * @param config    Configuración de la aplicación.
     * @param queue     Cola de mensajes compartida.
     * @param token     Token OAuth de Twitch, o {@code null} para modo anónimo.
     * @param login     Nombre de usuario del token, o {@code null} en modo anónimo.
     * @param threadRef Referencia donde guardar el hilo del reader.
     * @return {@code null} si la conexión fue exitosa, o el mensaje de error si falló.
     * @throws InterruptedException Si el hilo es interrumpido mientras espera.
     */
    private static String launchTwitchReader(Config config,
                                             BlockingQueue<ChatMessage> queue,
                                             String token, String login,
                                             AtomicReference<Thread> threadRef)
            throws InterruptedException {

        CountDownLatch        latch        = new CountDownLatch(1);
        AtomicReference<String> connectError = new AtomicReference<>();

        TwitchChatReader reader = new TwitchChatReader(
                config.getTwitchChannel(), queue, token, login);

        reader.setOnConnected(ch -> latch.countDown());
        reader.setOnFatalError(error -> {
            connectError.set(error);
            latch.countDown();
        });

        Thread t = new Thread(reader, token != null ? "twitch-reader" : "twitch-reader-anon");
        t.setDaemon(true);
        t.start();
        threadRef.set(t);

        boolean connected = latch.await(10, TimeUnit.SECONDS);
        if (!connected) {
            t.interrupt();
            return "Timeout conectando a Twitch — revisa el nombre del canal.";
        }

        return connectError.get(); // null si fue bien, mensaje de error si falló
    }

    // ── Conexión a YouTube ────────────────────────────────────────────────────

    /**
     * Inicia la conexión a YouTube en un hilo virtual.
     *
     * <p>Espera hasta 15 segundos a que el {@link YouTubeChatReader} resuelva
     * el {@code liveChatId}. Si lo consigue, muestra el contador de espectadores.
     * Si no hay directo activo, el reader invoca {@code onFatalError} y se muestran
     * los botones para reintentar.
     *
     * @param config           Configuración de la aplicación.
     * @param queue            Cola de mensajes compartida.
     * @param sharedImageCache Cache de imágenes compartida.
     * @param overlay          Ventana del overlay para actualizar la UI.
     */
    private static void connectYoutube(Config config,
                                       BlockingQueue<ChatMessage> queue,
                                       ImageCache sharedImageCache,
                                       ChatOverlay overlay) {
        Thread.ofVirtual().name("youtube-connect").start(() -> {
            try {
                YouTubeChatReader reader = new YouTubeChatReader(
                        config.getYoutubeChannelId(),
                        config.getYoutubeVideoId(),
                        config.getYoutubeApiKeys(),
                        queue, config, sharedImageCache);

                reader.setOnFatalError(errorMsg -> SwingUtilities.invokeLater(() -> {
                    overlay.showYoutubeButton();
                    overlay.enableYoutubeButton();
                    overlay.appendSystemMessage(
                            "✖ " + errorMsg + " — pulsa el botón para reintentar.");
                }));

                Thread t = new Thread(reader, "youtube-reader");
                t.setDaemon(true);
                t.start();

                // Esperar hasta 15 segundos a que resuelva el liveChatId
                long deadline = System.currentTimeMillis() + 15_000;
                while (System.currentTimeMillis() < deadline) {
                    if (reader.isConnected()) {
                        SwingUtilities.invokeLater(() -> {
                            overlay.appendSystemMessage("✔ Conectado al chat de YouTube.");
                            overlay.showYoutubeViewers();
                            overlay.startYoutubeViewers();
                        });
                        return;
                    }
                    Thread.sleep(500);
                }

                // Si tras 15 s no hay conexión ni error fatal, el reader sigue en bucle
                // esperando un directo — onFatalError ya habrá saltado si procede

            } catch (Exception e) {
                System.err.println("[Main] Error iniciando YouTube: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage(
                            "✖ Error conectando YouTube: " + e.getMessage());
                    overlay.showYoutubeButton();
                    overlay.enableYoutubeButton();
                });
            }
        });
    }

    // ── Archivos pendientes del Launcher ──────────────────────────────────────

    /**
     * Copia un archivo pendiente del Launcher en un hilo virtual que reintenta
     * indefinidamente hasta conseguirlo.
     *
     * <p>El Launcher no puede sobreescribirse a sí mismo mientras está en ejecución.
     * Le pasa esos archivos a {@code ChatOverlay} como argumentos, y este método
     * los copia en background una vez que el Launcher ha cerrado.
     *
     * @param src  Ruta del archivo nuevo (en la carpeta temporal de la actualización).
     * @param dest Ruta destino en el directorio de instalación.
     */
    private static void applyPendingLauncherFile(Path src, Path dest) {
        Thread.ofVirtual().name("pending-launcher-copy-" + dest.getFileName()).start(() -> {
            System.out.println("[Main] Intentando copiar archivo pendiente del Launcher: "
                    + dest.getFileName());
            while (true) {
                try {
                    java.nio.file.Files.copy(src, dest,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[Main] Archivo del Launcher actualizado: "
                            + dest.getFileName());
                    return;
                } catch (java.io.IOException e) {
                    System.out.println("[Main] Reintentando copiar "
                            + dest.getFileName() + " (en uso)...");
                    try { Thread.sleep(2_000); } catch (InterruptedException ignored) {}
                }
            }
        });
    }
}