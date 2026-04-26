package com.chatoverlaystreaming.overlay;

import java.awt.Color;
import java.util.function.Consumer;

/**
 * Interfaz común para los reproductores de vídeo del overlay.
 *
 * Define el contrato que deben cumplir las implementaciones:
 *   - {@link VideoOverlay}: usa JavaFX como backend. Recomendado cuando
 *     el chroma key está activo por su mayor calidad visual.
 *   - {@link VlcjVideoOverlay}: usa VLC como backend vía vlcj. Mayor
 *     compatibilidad de codecs (MKV, H.265, etc.).
 *
 * Ciclo de vida esperado:
 * <pre>
 *   window.setChroma(...);    // opcional, antes de play()
 *   window.setOnError(...);   // registrar callback de error
 *   window.setOnReady(...);   // registrar callback de inicio
 *   window.setVisible(true);  // mostrar la ventana
 *   window.play();            // iniciar reproducción
 *   // ... el backend llama a onReady cuando arranca
 *   // ... el backend llama a onError si falla
 *   // ... al terminar el vídeo, el overlay se cierra automáticamente
 * </pre>
 */
public interface VideoPlayerWindow {

    /**
     * Inicia la reproducción del vídeo.
     * Debe llamarse después de {@link #setVisible(boolean)} para garantizar
     * que la ventana nativa existe y los handles están inicializados.
     */
    void play();

    /**
     * Registra el callback que se invoca si la reproducción falla.
     * El argumento del Consumer es el mensaje de error.
     * Debe registrarse antes de llamar a {@link #play()}.
     *
     * @param callback Consumer que recibe el mensaje de error.
     */
    void setOnError(Consumer<String> callback);

    /**
     * Registra el callback que se invoca cuando el vídeo empieza a reproducirse.
     * Usado por {@link RewardMediaPlayer} para registrar la reproducción
     * en las estadísticas una vez confirmado que el archivo es válido.
     * Debe registrarse antes de llamar a {@link #play()}.
     *
     * @param callback Runnable invocado al inicio de la reproducción.
     */
    void setOnReady(Runnable callback);

    /**
     * Configura el chroma key del overlay.
     * Debe llamarse antes de {@link #play()} para que el color de fondo
     * de la ventana se aplique correctamente desde el inicio.
     *
     * @param enabled   Si true, activa el chroma key.
     * @param color     Color del fondo a eliminar.
     * @param tolerance Tolerancia de distancia euclídea (0-255).
     *                  A mayor tolerancia, más píxeles se consideran del color croma.
     */
    void setChroma(boolean enabled, Color color, int tolerance);

    /**
     * Muestra u oculta la ventana del overlay.
     * Heredado de {@link java.awt.Window}, necesario en la interfaz porque
     * {@link RewardMediaPlayer} trabaja con esta interfaz y no con JFrame directamente.
     *
     * @param visible true para mostrar, false para ocultar.
     */
    void setVisible(boolean visible);

    /**
     * Libera los recursos del reproductor y cierra la ventana.
     * Cada implementación garantiza que el backend (JavaFX MediaPlayer o
     * vlcj MediaPlayerComponent) se detiene y libera correctamente.
     */
    void dispose();
}