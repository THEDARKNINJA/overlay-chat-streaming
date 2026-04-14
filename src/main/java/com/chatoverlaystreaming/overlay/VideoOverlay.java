package com.chatoverlaystreaming.overlay;

import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;

import javax.swing.*;
import java.nio.file.Path;

public class VideoOverlay extends JFrame {

    private static final int WIDTH  = 480;
    private static final int HEIGHT = 270;

    private final JFXPanel fxPanel;
    private final Path videoFile;
    private MediaPlayer player;

    public VideoOverlay(Path videoFile) {
        this.videoFile = videoFile;

        setUndecorated(true);
        setAlwaysOnTop(true);
        setLocation(0, 0);
        setSize(WIDTH, HEIGHT);
        setBackground(new java.awt.Color(0, 0, 0, 0));
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        fxPanel = new JFXPanel();
        fxPanel.setBackground(java.awt.Color.BLACK);
        add(fxPanel);

        // NO excluir de captura — este panel sí debe verse en OBS
    }

    public void play() {
        Platform.runLater(() -> {
            try {
                Media media = new Media(videoFile.toUri().toString());
                player = new MediaPlayer(media);

                MediaView mediaView = new MediaView(player);
                mediaView.setFitWidth(WIDTH);
                mediaView.setFitHeight(HEIGHT);
                mediaView.setPreserveRatio(true);

                StackPane pane = new StackPane(mediaView);
                pane.setStyle("-fx-background-color: black;");

                Scene scene = new Scene(pane, WIDTH, HEIGHT, Color.BLACK);
                //Scene scene = new Scene(pane, WIDTH, HEIGHT, Color.TRANSPARENT);
                fxPanel.setScene(scene);

                player.setOnEndOfMedia(() -> {
                    MediaPlayer p = player;
                    player = null;  // anular referencia antes de dispose
                    p.stop();
                    p.dispose();
                    SwingUtilities.invokeLater(() -> {
                        setVisible(false);
                        super.dispose();  // dispose del JFrame directamente
                    });
                });

                player.setOnError(() ->
                    System.err.println("[Video] Error: " + player.getError().getMessage()));

                player.play();

            } catch (Exception e) {
                System.err.println("[VideoOverlay] Error: " + e.getMessage());
                SwingUtilities.invokeLater(() -> super.dispose());
            }
        });
    }

    @Override
    public void dispose() {
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            Platform.runLater(() -> {
                p.stop();
                p.dispose();
            });
        }
        super.dispose();
    }
}