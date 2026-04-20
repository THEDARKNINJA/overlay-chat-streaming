package com.chatoverlaystreaming;

import com.chatoverlaystreaming.emotes.ImageCache;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.overlay.ChatOverlay;
import com.chatoverlaystreaming.overlay.Config;
import com.chatoverlaystreaming.overlay.TwitchAuth;
import com.chatoverlaystreaming.readers.TwitchChatReader;
import com.chatoverlaystreaming.readers.TwitchEventSub;
import com.chatoverlaystreaming.readers.YouTubeChatReader;

import javafx.embed.swing.JFXPanel;
import javafx.scene.paint.Color;

import javax.swing.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Main {
    public static void main(String[] args) {
        Logger.init();
        Runtime.getRuntime().addShutdownHook(new Thread(Logger::close));

        // Inicializar JavaFX antes de todo
        CountDownLatch fxLatch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            new javax.swing.JPanel(); // asegurar EDT
            new javafx.embed.swing.JFXPanel(); // inicializar toolkit JavaFX
            fxLatch.countDown();
        });
        try { fxLatch.await(); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Cargar config — si falla, usar config vacío con defaults
        Config config;
        boolean configOk = true;
        try {
            config = new Config();
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

        final Config finalConfig = config;
        final boolean finalConfigOk = configOk;

        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(500);
        ImageCache sharedImageCache = new ImageCache(config.getIconSize());

        // Crear EventSub antes del overlay para poder pasarlo
        TwitchEventSub[] eventSubHolder = {null};

        // Lanzar UI primero
        SwingUtilities.invokeLater(() -> {
            ChatOverlay overlay = new ChatOverlay(
                    queue,
                    finalConfig.getTwitchChannelId(),
                    finalConfig.getTwitchClientId(),
                    finalConfig.getTwitchClientSecret(),
                    finalConfig, sharedImageCache);

            overlay.setVisible(true);
            overlay.initNativeFeatures();
            overlay.setIconImage(
                        new ImageIcon("icon.png").getImage()
                    );

            boolean twitchWasOk  = finalConfig.getLastConnectionSuccess("twitch");
            boolean youtubeWasOk = finalConfig.getLastConnectionSuccess("youtube");

            // Configurar los botones de reconexión manual
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

                /*
            // Conexión directa al abrir
            if (!twitchWasOk && !youtubeWasOk) {
                // Primera vez o ambas fallaron — mostrar botones y mensaje
                overlay.showTwitchButton();
                overlay.showYoutubeButton();
                overlay.appendSystemMessage(
                    "⚙ Primera conexión o conexión previa fallida. " +
                    "Configura tus datos con el botón ⚙ y pulsa los " +
                    "botones de Twitch y YouTube para conectar.");
            } else {
                // Al menos una fue exitosa — intentar reconectar automáticamente
                overlay.appendSystemMessage("⚡ Intentando reconectar...");

                if (twitchWasOk) {
                    connectTwitch(finalConfig, queue, sharedImageCache,
                                overlay, eventSubHolder);
                } else {
                    overlay.showTwitchButton();
                    overlay.appendSystemMessage(
                        "⚠ La última conexión de Twitch falló. " +
                        "Pulsa el botón de Twitch para reintentar.");
                }

                if (youtubeWasOk) {
                    connectYoutube(finalConfig, queue, sharedImageCache, overlay);
                } else {
                    overlay.showYoutubeButton();
                    overlay.appendSystemMessage(
                        "⚠ La última conexión de YouTube falló. " +
                        "Pulsa el botón de YouTube para reintentar.");
                }
            }
            */
        });
    }

    private static void connectTwitch(Config config,
                                   BlockingQueue<ChatMessage> queue,
                                   ImageCache sharedImageCache,
                                   ChatOverlay overlay,
                                   TwitchEventSub[] eventSubHolder) {
        Thread.ofVirtual().name("twitch-connect").start(() -> {
            String accessToken = null;
            String twitchLogin = null;

            // Intentar OAuth
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

            // Lanzar reader (con token si lo hay, anónimo si no)
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
                // OAuth OK: mostrar viewers normales
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage("✔ Conectado al chat de Twitch.");
                    overlay.showTwitchViewers();
                });
            } else {
                // Modo anónimo: mostrar botón naranja para reintentar OAuth
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage(
                        "✔ Conectado al chat de Twitch en modo anónimo.");
                    overlay.showTwitchButtonAnon();
                });
            }

            // EventSub solo si hay OAuth
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
                        overlay.appendSystemMessage(
                            "✔ EventSub conectado. Recompensas activas.");
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
     * Lanza un TwitchChatReader y espera confirmación de JOIN.
     * Devuelve null si conectó bien, o el mensaje de error si falló.
     */
    private static String launchTwitchReader(Config config,
                                            BlockingQueue<ChatMessage> queue,
                                            String token, String login,
                                            java.util.concurrent.atomic.AtomicReference<Thread> threadRef) 
            throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(1);
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

        return connectError.get(); // null si fue bien, mensaje si falló
}

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
                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline) {
                    if (reader.isConnected()) {
                        SwingUtilities.invokeLater(() -> {
                            overlay.appendSystemMessage("✔ Conectado al chat de YouTube.");
                            overlay.showYoutubeViewers();
                        });
                        return;
                    }
                    Thread.sleep(500);
                }

                // 15 segundos sin conectar ni error fatal:
                // puede ser que no haya directo pero el reader sigue en bucle
                // Si initialConnectionDone sigue false, el onFatalError ya habrá saltado
                // Si no saltó es porque está en sleep(RETRY_INTERVAL) — no puede pasar
                // en la primera conexión con el nuevo código, así que no hacemos nada más

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
}