package com.chatoverlaystreaming.overlay;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

// VideoOverlay, versión GDI

public class VideoOverlay extends JFrame implements VideoPlayerWindow {

    private int WIDTH;
    private int HEIGHT;
    private final Path videoFile;
    private final double volume;
    private final int displayIndex;
    private final int fps;

    // Panel de Swing que pinta los frames
    private final VideoPanel videoPanel;

    // Referencia al MediaPlayer para poder pararlo
    private volatile MediaPlayer player;

    private Runnable onReadyCallback;
    private java.util.function.Consumer<String> onErrorCallback;

    // Escena JavaFX off-screen
    // private static JFXPanel fxInitPanel; // solo para inicializar el toolkit

    /*
    static {
        // Inicializar el toolkit de JavaFX sin mostrar nada
        if (fxInitPanel == null) {
            fxInitPanel = new JFXPanel();
        }
    }
         */

    public VideoOverlay(Path videoFile, double volume,
                        int width, int height, int displayIndex, int fps, String windowTitle, int posX, int posY) {
        this.videoFile    = videoFile;
        this.volume       = volume;
        this.WIDTH        = width;
        this.HEIGHT       = height;
        this.displayIndex = displayIndex;
        this.fps          = fps;

        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setTitle(windowTitle != null && !windowTitle.isBlank()
            ? windowTitle : "OverlayVideo");
        setSize(width, height);

        //setBackground(java.awt.Color.BLACK);
setBackground(new java.awt.Color(0, 0, 0, 0));
getRootPane().setOpaque(false);
((JComponent) getContentPane()).setOpaque(false);

        setIconImage( new ImageIcon("icon.png").getImage() );
        positionOnScreen(posX, posY);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);

        videoPanel = new VideoPanel(width, height);
        add(videoPanel, BorderLayout.CENTER);
    }

    private void positionOnScreen(int posX, int posY) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        // Si el índice elegido no existe, usar la pantalla principal
        int targetIndex = (displayIndex > 0 && displayIndex < screens.length)
                ? displayIndex : 0;

        if (targetIndex != displayIndex) {
            System.out.println("[Video] Pantalla " + (displayIndex + 1)
                    + " no disponible, usando pantalla principal.");
        }

        Rectangle bounds = screens[targetIndex]
                .getDefaultConfiguration().getBounds();

        // Posición relativa a la pantalla elegida
        int absX = bounds.x + posX;
        int absY = bounds.y + posY;

        // Asegurarse de que no se sale de la pantalla
        absX = Math.max(bounds.x,
            Math.min(absX, bounds.x + bounds.width  - WIDTH));
        absY = Math.max(bounds.y,
            Math.min(absY, bounds.y + bounds.height - HEIGHT));

        if (absX != bounds.x + posX || absY != bounds.y + posY) {
            System.out.println("[Video] Posición ajustada para no salirse de pantalla.");
        }

        setLocation(absX, absY);
    }

    @Override
    public void play() {
        Platform.runLater(() -> {
            try {
                Media media = new Media(videoFile.toUri().toString());
                player = new MediaPlayer(media);
                player.setVolume(volume);

                MediaView mediaView = new MediaView(player);
                mediaView.setFitWidth(WIDTH);
                mediaView.setFitHeight(HEIGHT);
                mediaView.setPreserveRatio(true);

                StackPane pane = new StackPane(mediaView);
                pane.setStyle("-fx-background-color: black;");
                pane.setPrefSize(WIDTH, HEIGHT);

                // Escena off-screen: no se muestra en ninguna ventana JavaFX
                Scene scene = new Scene(pane, WIDTH, HEIGHT, Color.BLACK);

                // Snapshot timer: captura frames y los pinta en Swing
                // JavaFX renderiza off-screen y nosotros copiamos el buffer
                /*
                javafx.animation.AnimationTimer frameTimer =
                        new javafx.animation.AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (player == null) {
                            stop();
                            return;
                        }
                        // Snapshot de la escena al frame actual
                        WritableImage snapshot = scene.snapshot(null);
                        if (snapshot != null) {
                            BufferedImage frame = SwingFXUtils.fromFXImage(snapshot, null);
                            videoPanel.setFrame(frame);
                        }
                    }
                }; */
                long[] last = {0};
                long frameInterval = 1_000_000_000L / fps; // fps = 30 o 60

                AnimationTimer frameTimer = new AnimationTimer() {
                    @Override
                    public void handle(long now) {
                        if (player == null) {
                            stop();
                            return;
                        }
                        if (now - last[0] >= frameInterval) {
                            last[0] = now;
                            WritableImage snapshot = scene.snapshot(null);
                            if (snapshot != null) {
                                BufferedImage frame = SwingFXUtils.fromFXImage(snapshot, null);
                                videoPanel.setFrame(frame);
                            }
                        }
                    }
                };

                player.setOnReady(() -> {
                    if (onReadyCallback != null) onReadyCallback.run();
                    frameTimer.start();
                    player.play();
                });

                player.setOnEndOfMedia(() -> {
                    frameTimer.stop();
                    MediaPlayer p = player;
                    player = null;
                    p.stop();
                    p.dispose();
                    SwingUtilities.invokeLater(() -> {
                        setVisible(false);
                        VideoOverlay.super.dispose();
                    });
                });

                player.setOnError(() -> {
                    frameTimer.stop();
                    String msg = player.getError() != null
                            ? player.getError().getMessage() : "desconocido";
                    if (onErrorCallback != null) {
                        onErrorCallback.accept(msg);
                    } else {
                        System.err.println("[Video] Error: " + msg);
                    }
                    MediaPlayer p = player;
                    player = null;
                    p.dispose();
                    SwingUtilities.invokeLater(() -> {
                        setVisible(false);
                        VideoOverlay.super.dispose();
                    });
                });

            } catch (Exception e) {
                System.err.println("[VideoOverlay] Error: " + e.getMessage());
                SwingUtilities.invokeLater(() -> VideoOverlay.super.dispose());
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
    
    @Override
    public void setChroma(boolean enabled, java.awt.Color color, int tolerance) {
        videoPanel.setChroma(enabled, color, tolerance);
    }

    // ── Panel que pinta los frames ───────────────────────────────────────────

    private static class VideoPanel extends JPanel {

        private final AtomicReference<BufferedImage> currentFrame =
                new AtomicReference<>();
        private final int width;
        private final int height;

        private volatile boolean chromaEnabled   = false;
        private volatile int     chromaR, chromaG, chromaB;
        private volatile int     chromaTolerance = 40;

        VideoPanel(int width, int height) {
            this.width  = width;
            this.height = height;
            setOpaque(false);
            setBackground(new java.awt.Color(0, 0, 0, 0));
            setPreferredSize(new Dimension(width, height));
        }

        void setFrame(BufferedImage frame) {
            if (chromaEnabled && frame != null) {
                frame = applyChroma(frame);
            }
            currentFrame.set(frame);
            SwingUtilities.invokeLater(this::repaint);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage frame = currentFrame.get();
            if (frame != null) {
                // Escalar manteniendo proporción
                double scaleX = (double) width  / frame.getWidth();
                double scaleY = (double) height / frame.getHeight();
                double scale  = Math.min(scaleX, scaleY);
                int drawW = (int)(frame.getWidth()  * scale);
                int drawH = (int)(frame.getHeight() * scale);
                int drawX = (width  - drawW) / 2;
                int drawY = (height - drawH) / 2;
                g.drawImage(frame, drawX, drawY, drawW, drawH, null);
            }
        }

        public void setChroma(boolean enabled, java.awt.Color color, int tolerance) {
            this.chromaEnabled   = enabled;
            this.chromaR         = color.getRed();
            this.chromaG         = color.getGreen();
            this.chromaB         = color.getBlue();
            this.chromaTolerance = tolerance;
        }

        private BufferedImage applyChroma(BufferedImage src) {
            // Convertir a ARGB para poder poner píxeles transparentes
            BufferedImage result = new BufferedImage(
                    src.getWidth(), src.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);

            int[] pixels = src.getRGB(0, 0,
                    src.getWidth(), src.getHeight(), null, 0, src.getWidth());

            for (int i = 0; i < pixels.length; i++) {
                int pixel = pixels[i];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >>  8) & 0xFF;
                int b =  pixel        & 0xFF;

                // Distancia euclídea al color croma
                double dist = Math.sqrt(
                        Math.pow(r - chromaR, 2) +
                        Math.pow(g - chromaG, 2) +
                        Math.pow(b - chromaB, 2));

                if (dist <= chromaTolerance) {
                    pixels[i] = 0x00000000; // transparente
                }
            }

            result.setRGB(0, 0, src.getWidth(), src.getHeight(),
                    pixels, 0, src.getWidth());
            return result;
        }
    }
    
    @Override
    public void setOnReady(Runnable callback) {
        this.onReadyCallback = callback;
    }

    @Override
    public void setOnError(java.util.function.Consumer<String> callback) {
        this.onErrorCallback = callback;
    }
}