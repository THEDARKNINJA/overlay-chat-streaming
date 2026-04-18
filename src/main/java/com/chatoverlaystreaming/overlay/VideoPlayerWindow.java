package com.chatoverlaystreaming.overlay;

import java.awt.Color;
import java.nio.file.Path;

/**
 * Interfaz común para reproductores de vídeo.
 * Implementada por VlcjVideoOverlay y VideoOverlay (JavaFX).
 */
public interface VideoPlayerWindow {
    void play();
    void setOnError(java.util.function.Consumer<String> callback);
    void setOnReady(Runnable callback);
    void setChroma(boolean enabled, Color color, int tolerance);
    void setVisible(boolean visible);
    void dispose();
}