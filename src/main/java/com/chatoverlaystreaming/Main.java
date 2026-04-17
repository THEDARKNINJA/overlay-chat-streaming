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

            // Mensaje de bienvenida siempre
            overlay.appendSystemMessage(
                "⚡ Chat Overlay iniciado. " +
                (finalConfigOk
                    ? "Intentando conectar con Twitch y YouTube..."
                    : "⚠ No se encontró config.json o contiene errores. " +
                    "Usa el botón ⚙ para configurar la aplicación y reinicia.")
            );

            // Iniciar conexiones en background
            Thread.ofVirtual().start(() -> {
                startConnections(finalConfig, queue, sharedImageCache,
                                overlay, eventSubHolder);
            });
        });
    }

    private static void startConnections(Config config,
                                        BlockingQueue<ChatMessage> queue,
                                        ImageCache sharedImageCache,
                                        ChatOverlay overlay,
                                        TwitchEventSub[] eventSubHolder) {
        // OAuth Twitch
        String accessToken  = null;
        System.err.print("1");
        String twitchLogin  = null;

        try {
            TwitchAuth auth = new TwitchAuth(config.getTwitchClientId(), config);
            accessToken = auth.getValidToken();
            twitchLogin = auth.getLoginFromToken(accessToken);
            System.out.println("[Auth] Conectado como: " + twitchLogin);
            final String login = twitchLogin;
            SwingUtilities.invokeLater(() ->
                overlay.appendSystemMessage("✔ Twitch OAuth OK — conectado como " + login));
        } catch (Exception e) {
            System.err.println("[Auth] Error OAuth: " + e.getMessage());
            SwingUtilities.invokeLater(() ->
                overlay.appendSystemMessage(
                    "⚠ Twitch OAuth falló, conectando en modo anónimo. " +
                    "Las recompensas y moderación no estarán disponibles."));
        }

        // Twitch IRC
        final String finalToken = accessToken;
        final String finalLogin = twitchLogin;
        try {
            Thread twitchThread = new Thread(
                    new TwitchChatReader(config.getTwitchChannel(),
                                        queue, finalToken, finalLogin),
                    "twitch-reader");
            twitchThread.setDaemon(true);
            twitchThread.start();
            SwingUtilities.invokeLater(() ->
                overlay.appendSystemMessage("✔ Conectado al chat de Twitch."));
        } catch (Exception e) {
            System.err.println("[Main] Error iniciando Twitch: " + e.getMessage());
            SwingUtilities.invokeLater(() ->
                overlay.appendSystemMessage("✖ Error conectando con Twitch: "
                        + e.getMessage()));
        }

        // EventSub
        if (accessToken != null) {
            try {
                TwitchEventSub eventSub = new TwitchEventSub(
                        accessToken, config.getTwitchClientId(),
                        config.getTwitchChannelId(), queue);
                eventSubHolder[0] = eventSub;

                Thread eventSubThread = new Thread(eventSub, "twitch-eventsub");
                eventSubThread.setDaemon(true);
                eventSubThread.start();

                SwingUtilities.invokeLater(() -> {
                    overlay.setEventSub(eventSub);
                    overlay.appendSystemMessage("✔ EventSub conectado. Recompensas activas.");
                });
            } catch (Exception e) {
                System.err.println("[Main] Error EventSub: " + e.getMessage());
                SwingUtilities.invokeLater(() ->
                    overlay.appendSystemMessage("⚠ EventSub no disponible: "
                            + e.getMessage()));
            }
        }

        // YouTube
        try {
            Thread youtubeThread = new Thread(
                    new YouTubeChatReader(config.getYoutubeChannelId(),
                                        config.getYoutubeVideoId(),
                                        config.getYoutubeApiKeys(),
                                        queue, config, sharedImageCache),
                    "youtube-reader");
            youtubeThread.setDaemon(true);
            youtubeThread.start();
            SwingUtilities.invokeLater(() ->
                overlay.appendSystemMessage("✔ Conectado al chat de YouTube."));
        } catch (Exception e) {
            System.err.println("[Main] Error YouTube: " + e.getMessage());
            SwingUtilities.invokeLater(() ->
                overlay.appendSystemMessage("⚠ YouTube no disponible: "
                        + e.getMessage()));
        }
    }
}