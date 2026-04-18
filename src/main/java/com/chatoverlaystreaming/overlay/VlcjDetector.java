package com.chatoverlaystreaming.overlay;

public class VlcjDetector {

    private static Boolean available = null;

    public static boolean isAvailable() {
        if (available != null) return available;
        try {
            // Intentar cargar la clase principal de vlcj
            Class.forName("uk.co.caprica.vlcj.factory.MediaPlayerFactory");
            // Intentar crear una factory real para ver si VLC está instalado
            uk.co.caprica.vlcj.factory.MediaPlayerFactory factory =
                    new uk.co.caprica.vlcj.factory.MediaPlayerFactory();
            factory.release();
            available = true;
            System.out.println("[vlcj] VLC detectado, usando vlcj para vídeo.");
        } catch (Throwable e) {
            available = false;
            System.out.println("[vlcj] VLC no disponible ("
                    + e.getClass().getSimpleName()
                    + "), usando JavaFX para vídeo.");
        }
        return available;
    }
}