package com.chatoverlaystreaming.overlay;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.function.Consumer;

public class VlcjVideoOverlay extends JFrame implements VideoPlayerWindow {

    private final Path videoFile;
    private final double volume;
    private final int displayIndex;
    private final String windowTitle;

    private EmbeddedMediaPlayerComponent mediaPlayerComponent;
    private EmbeddedMediaPlayer mediaPlayer;

    // Chroma
    private volatile boolean chromaEnabled;
    private volatile Color   chromaColor;
    private volatile int     chromaTolerance;

    private Runnable          onReadyCallback;
    private Consumer<String>  onErrorCallback;

    public VlcjVideoOverlay(Path videoFile, double volume,
                             int width, int height,
                             int displayIndex, int fps,
                             String windowTitle, int posX, int posY) {
        this.videoFile    = videoFile;
        this.volume       = volume;
        this.displayIndex = displayIndex;
        this.windowTitle  = windowTitle != null ? windowTitle : "VideoOverlay";

        setTitle(this.windowTitle);
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setSize(width, height);

        positionOnScreen(width, height, posX, posY);

        mediaPlayerComponent = new EmbeddedMediaPlayerComponent();
        add(mediaPlayerComponent, BorderLayout.CENTER);
        mediaPlayer = mediaPlayerComponent.mediaPlayer();
    }

    private void positionOnScreen(int width, int height, int posX, int posY) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsDevice[] screens = ge.getScreenDevices();

        // Si el índice elegido no existe, usar la pantalla principal
        int targetIndex = (displayIndex > 0 && displayIndex < screens.length)
                ? displayIndex : 0;

        // Posición relativa a la pantalla elegida
        Rectangle bounds = screens[targetIndex]
            .getDefaultConfiguration().getBounds();
            
        int absX = bounds.x + posX;
        int absY = bounds.y + posY;

        // Asegurarse de que no se sale de la pantalla
        absX = Math.max(bounds.x,
            Math.min(absX, bounds.x + bounds.width  - width));
        absY = Math.max(bounds.y,
            Math.min(absY, bounds.y + bounds.height - height));

        if (absX != bounds.x + posX || absY != bounds.y + posY) {
            System.out.println("[Video] Posición ajustada para no salirse de pantalla.");
        }

        setLocation(absX, absY);
    }

    @Override
    public void play() {
        int vlcVolume = (int)(volume * 100);
        mediaPlayer.audio().setVolume(vlcVolume);
        // Registrar eventos
        mediaPlayer.events().addMediaPlayerEventListener(
                new uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter() {

            @Override
            public void playing(uk.co.caprica.vlcj.player.base.MediaPlayer player) {
                mediaPlayer.audio().setVolume(vlcVolume);
                if (onReadyCallback != null) onReadyCallback.run();
            }

            @Override
            public void finished(uk.co.caprica.vlcj.player.base.MediaPlayer player) {
                SwingUtilities.invokeLater(() -> {
                    setVisible(false);
                    dispose();
                });
            }

            @Override
            public void error(uk.co.caprica.vlcj.player.base.MediaPlayer player) {
                String msg = "Error de vlcj reproduciendo: " + videoFile.getFileName();
                System.err.println("[vlcj] " + msg);
                if (onErrorCallback != null) onErrorCallback.accept(msg);
                SwingUtilities.invokeLater(() -> {
                    setVisible(false);
                    dispose();
                });
            }
        });

        mediaPlayer.media().play(videoFile.toAbsolutePath().toString());
    }

    @Override
    public void setChroma(boolean enabled, Color color, int tolerance) {
        // vlcj puede aplicar filtros de vídeo nativos pero es complejo.
        // Por ahora guardamos los valores — implementación futura.
        this.chromaEnabled   = enabled;
        this.chromaColor     = color;
        this.chromaTolerance = tolerance;
        if (enabled) {
            System.out.println("[vlcj] Chroma key no implementado en vlcj todavía, " +
                               "usa JavaFX si necesitas croma.");
        }
    }

    @Override
    public void setOnError(Consumer<String> callback) {
        this.onErrorCallback = callback;
    }

    @Override
    public void setOnReady(Runnable callback) {
        this.onReadyCallback = callback;
    }

    @Override
    public void dispose() {
        if (mediaPlayer != null) {
            mediaPlayer.controls().stop();
            mediaPlayerComponent.release();
            mediaPlayer = null;
        }
        super.dispose();
    }
}