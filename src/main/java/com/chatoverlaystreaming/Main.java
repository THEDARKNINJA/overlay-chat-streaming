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
        // Cargar configuración
        Config config;
        try {
            config = new Config();
            // config.descargarImagenesDesdeJsonObject(); // para descargar los emojis de YT
        } catch (Exception e) {
            Logger.init();
            System.err.println("Error cargando configuración: " + e.getMessage());
            // Shutdown hook para cerrar el log al salir
            Runtime.getRuntime().addShutdownHook(new Thread(Logger::close));
            return;
        }

        if(config.getLogActivity()) {
            Logger.init();
            // Shutdown hook para cerrar el log al salir
            Runtime.getRuntime().addShutdownHook(new Thread(Logger::close));
        }

        // Inicializar JavaFX en el EDT
        CountDownLatch fxLatch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            new JFXPanel(); // inicializa el toolkit de JavaFX
            fxLatch.countDown();
        });
        try {
            fxLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("[FX] Toolkit JavaFX inicializado.");


        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(500);
        ImageCache sharedImageCache = new ImageCache(config.getIconSize());

        // System.setProperty("prism.order", "d3d");

        TwitchAuth twitchAuth = new TwitchAuth(config.getTwitchClientId(), config);
        String accessToken;
        String twitchLogin;
        try {
            accessToken  = twitchAuth.getValidToken();
            twitchLogin  = twitchAuth.getLoginFromToken(accessToken);
            System.out.println("[Auth] Conectado como: " + twitchLogin);
        } catch (Exception e) {
            System.err.println("[Auth] Error de autenticación: " + e.getMessage());
            System.err.println("[Auth] Conectando en modo anónimo...");
            accessToken = null;
            twitchLogin = null;
        }

        // EventSub para leer y manejar las recompensas
        TwitchEventSub eventSub = accessToken != null
                ? new TwitchEventSub(accessToken, config.getTwitchClientId(),
                                    config.getTwitchChannelId(), queue)
                : null;

        if (eventSub != null) {
            Thread eventSubThread = new Thread(eventSub, "twitch-eventsub");
            eventSubThread.setDaemon(true);
            eventSubThread.start();
        }

        // Lanzar interfaz
        SwingUtilities.invokeLater(() -> {
            ChatOverlay overlay = new ChatOverlay(
                    queue, config.getTwitchChannelId(),
                    config.getTwitchClientId(), config.getTwitchClientSecret(),
                    config, sharedImageCache);
            if (eventSub != null) overlay.setEventSub(eventSub);
            overlay.setVisible(true);
            overlay.initNativeFeatures();
        });

        // Iniciar lectores
        Thread twitchThread = new Thread(
                            new TwitchChatReader(config.getTwitchChannel(), queue, accessToken, twitchLogin),
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
    }
}