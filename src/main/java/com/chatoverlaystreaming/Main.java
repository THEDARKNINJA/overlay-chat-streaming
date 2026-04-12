package com.chatoverlaystreaming;

import com.chatoverlaystreaming.emotes.ImageCache;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.overlay.ChatOverlay;
import com.chatoverlaystreaming.overlay.Config;
import com.chatoverlaystreaming.readers.TwitchChatReader;
import com.chatoverlaystreaming.readers.YouTubeChatReader;
import javax.swing.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Main {

    public static void main(String[] args) {
        Logger.init();
        // Shutdown hook para cerrar el log al salir
        Runtime.getRuntime().addShutdownHook(new Thread(Logger::close));
        // Cargar configuración
        Config config;
        try {
            config = new Config();
            // config.descargarImagenesDesdeJsonObject(); // para descargar los emojis de YT
        } catch (Exception e) {
            System.err.println("Error cargando configuración: " + e.getMessage());
            return;
        }

        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(500);
        ImageCache sharedImageCache = new ImageCache(config.getIconSize());

        // Iniciar lectores
        Thread twitchThread = new Thread(
                new TwitchChatReader(config.getTwitchChannel(), queue),
                "twitch-reader");
        twitchThread.setDaemon(true);
        twitchThread.start();

        Thread youtubeThread = new Thread(
                new YouTubeChatReader(config.getYoutubeChannelId(),
                                      config.getYoutubeVideoId(),
                                      config.getYoutubeApiKeys(),
                                      queue, config, sharedImageCache),
                "youtube-reader");
        youtubeThread.setDaemon(true);
        youtubeThread.start();

        // Lanzar interfaz
        SwingUtilities.invokeLater(() -> {
            ChatOverlay overlay = new ChatOverlay(
                    queue,
                    config.getTwitchChannelId(),
                    config.getTwitchClientId(),
                    config.getTwitchClientSecret(),
                    config, sharedImageCache
                );
                overlay.setVisible(true);
                overlay.initNativeFeatures();
        });
    }
}