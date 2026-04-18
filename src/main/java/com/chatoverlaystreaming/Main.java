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

import javax.swing.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;

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
            overlay.setTwitchConnectAction(() ->
                connectTwitch(finalConfig, queue, sharedImageCache,
                            overlay, eventSubHolder));

            overlay.setYoutubeConnectAction(() ->
                connectYoutube(finalConfig, queue, sharedImageCache, overlay));

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
        });
    }

    private static void connectTwitch(Config config,
                                    BlockingQueue<ChatMessage> queue,
                                    ImageCache sharedImageCache,
                                    ChatOverlay overlay,
                                    TwitchEventSub[] eventSubHolder) {
        Thread.ofVirtual().name("twitch-connect").start(() -> {
            // OAuth
            String accessToken = null;
            String twitchLogin = null;
            try {
                TwitchAuth auth = new TwitchAuth(
                        config.getTwitchClientId(), config);
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
                        "⚠ Twitch OAuth falló, conectando en modo anónimo."));
            }

            // IRC
            final String finalToken = accessToken;
            final String finalLogin = twitchLogin;
            try {
                Thread t = new Thread(
                        new TwitchChatReader(config.getTwitchChannel(),
                                            queue, finalToken, finalLogin),
                        "twitch-reader");
                t.setDaemon(true);
                t.start();

                // Verificar que conecta esperando un poco
                Thread.sleep(3000);

                // Si llegamos aquí sin excepción, consideramos éxito
                config.saveLastConnectionSuccess("twitch", true);
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage("✔ Conectado al chat de Twitch.");
                    overlay.showTwitchViewers();
                });
            } catch (Exception e) {
                config.saveLastConnectionSuccess("twitch", false);
                System.err.println("[Main] Error Twitch IRC: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage("✖ Error conectando Twitch. Puedes reintentar.");
                    overlay.enableTwitchButton(); // rehabilitar para reintento
                    overlay.showTwitchButton();
                });
                return;
            }

            // EventSub
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

    private static void connectYoutube(Config config,
                                        BlockingQueue<ChatMessage> queue,
                                        ImageCache sharedImageCache,
                                        ChatOverlay overlay) {
        Thread.ofVirtual().name("youtube-connect").start(() -> {
            try {
                Thread t = new Thread(
                        new YouTubeChatReader(config.getYoutubeChannelId(),
                                            config.getYoutubeVideoId(),
                                            config.getYoutubeApiKeys(),
                                            queue, config, sharedImageCache),
                        "youtube-reader");
                t.setDaemon(true);
                t.start();

                Thread.sleep(3000);

                config.saveLastConnectionSuccess("youtube", true);
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage("✔ Conectado al chat de YouTube.");
                    overlay.showYoutubeViewers();
                });
            } catch (Exception e) {
                config.saveLastConnectionSuccess("youtube", false);
                System.err.println("[Main] Error YouTube: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    overlay.appendSystemMessage("✖ Error conectando YouTube. Puedes reintentar.");
                    overlay.enableYoutubeButton();
                    overlay.showYoutubeButton();
                });
            }
        });
    }
}