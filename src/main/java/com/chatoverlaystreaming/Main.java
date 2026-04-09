package com.chatoverlaystreaming;

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
        // Cargar configuración
        Config config;
        try {
            config = new Config();
        } catch (Exception e) {
            System.err.println("Error cargando configuración: " + e.getMessage());
            return;
        }

        BlockingQueue<ChatMessage> queue = new LinkedBlockingQueue<>(500);

        // Iniciar lectores
        Thread twitchThread = new Thread(
                new TwitchChatReader(config.getTwitchChannel(), queue),
                "twitch-reader");
        twitchThread.setDaemon(true);
        twitchThread.start();

        Thread youtubeThread = new Thread(
                new YouTubeChatReader(config.getYoutubeVideoId(),
                                      config.getYoutubeApiKey(), queue),
                "youtube-reader");
        youtubeThread.setDaemon(true);
        youtubeThread.start();

        // Lanzar interfaz
        SwingUtilities.invokeLater(() -> {
            ChatOverlay overlay = new ChatOverlay(queue, config.getTwitchChannelId(),
                                                  config.getPanelX(), config.getPanelY(), config.getPanelWidth(), config.getPanelHeight());
            overlay.setVisible(true);
        });
    }
}