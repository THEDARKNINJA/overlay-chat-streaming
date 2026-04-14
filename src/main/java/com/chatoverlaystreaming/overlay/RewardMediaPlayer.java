package com.chatoverlaystreaming.overlay;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

import javax.sound.sampled.*;
import javax.swing.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;

public class RewardMediaPlayer {

    // Inicializar JavaFX una sola vez
    static {
        new JFXPanel();
    }

    /**
     * Reproduce un archivo aleatorio de la carpeta indicada.
     * Si es audio usa javax.sound; si es video usa JavaFX en un VideoOverlay.
     */
    public static void play(String type, String folder) {
        Path dir = Paths.get("rewards", folder);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            System.err.println("[Media] Carpeta no encontrada: " + dir);
            return;
        }

        try {
            List<Path> files = Files.list(dir)
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> isValidExtension(p, type))
                    .toList();

            if (files.isEmpty()) {
                System.err.println("[Media] No hay archivos en: " + dir);
                return;
            }

            Path chosen = files.get(new Random().nextInt(files.size()));
            System.out.println("[Media] Reproduciendo: " + chosen);

            if ("audio".equals(type)) {
                playAudio(chosen);
            } else {
                playVideo(chosen);
            }

        } catch (Exception e) {
            System.err.println("[Media] Error: " + e.getMessage());
        }
    }

    private static boolean isValidExtension(Path p, String type) {
        String name = p.getFileName().toString().toLowerCase();
        if ("audio".equals(type)) {
            return name.endsWith(".wav") || name.endsWith(".mp3")
                || name.endsWith(".aac") || name.endsWith(".ogg");
        } else {
            return name.endsWith(".mp4") || name.endsWith(".mkv")
                || name.endsWith(".avi") || name.endsWith(".mov")
                || name.endsWith(".webm");
        }
    }

    private static void playAudio(Path file) {
        // JavaFX MediaPlayer soporta mp3/aac/wav
        Platform.runLater(() -> {
            try {
                Media media = new Media(file.toUri().toString());
                MediaPlayer player = new MediaPlayer(media);
                player.setOnEndOfMedia(player::dispose);
                player.setOnError(() ->
                    System.err.println("[Media] Error audio JavaFX: "
                            + player.getError().getMessage()));
                player.play();
            } catch (Exception e) {
                System.err.println("[Media] Error reproduciendo audio: " + e.getMessage());
            }
        });
    }

    private static void playVideo(Path file) {
        SwingUtilities.invokeLater(() -> {
            VideoOverlay overlay = new VideoOverlay(file);
            overlay.setVisible(true);
            overlay.play();
        });
    }
}