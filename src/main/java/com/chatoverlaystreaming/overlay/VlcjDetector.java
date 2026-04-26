package com.chatoverlaystreaming.overlay;

/**
 * Detecta en tiempo de ejecución si VLC Media Player está instalado
 * y vlcj puede usarse como backend de reproducción de vídeo.
 *
 * La detección se hace una sola vez (lazy + cached): intenta cargar la clase
 * principal de vlcj y crear una MediaPlayerFactory real. Si VLC no está
 * instalado, la factory lanza una excepción nativa al intentar cargar las
 * librerías de VLC, lo que permite distinguir "vlcj en classpath pero VLC
 * no instalado" de "vlcj no está en classpath".
 *
 * Uso:
 *   if (VlcjDetector.isAvailable()) {
 *       // usar VlcjVideoOverlay
 *   } else {
 *       // usar VideoOverlay (JavaFX)
 *   }
 */
public class VlcjDetector {

    /** Resultado cacheado de la detección. Null = aún no se ha comprobado. */
    private static Boolean available = null;

    /**
     * Comprueba si vlcj y VLC están disponibles en el sistema.
     * El resultado se cachea tras la primera llamada.
     *
     * @return true si vlcj está en el classpath y VLC está instalado.
     */
    public static boolean isAvailable() {
        if (available != null) return available;

        try {
            // Verificar que vlcj está en el classpath
            Class.forName("uk.co.caprica.vlcj.factory.MediaPlayerFactory");

            // Verificar que VLC está instalado intentando crear una factory real.
            // Si VLC no está instalado, esto lanza una excepción nativa.
            uk.co.caprica.vlcj.factory.MediaPlayerFactory factory =
                    new uk.co.caprica.vlcj.factory.MediaPlayerFactory();
            factory.release();

            available = true;
            System.out.println("[vlcj] VLC detectado — usando vlcj para vídeo.");

        } catch (Throwable e) {
            available = false;
            System.out.println("[vlcj] VLC no disponible ("
                    + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "")
                    + ") — usando JavaFX para vídeo.");
        }

        return available;
    }

    /** Constructor privado: clase de utilidad, no instanciable. */
    private VlcjDetector() {}
}