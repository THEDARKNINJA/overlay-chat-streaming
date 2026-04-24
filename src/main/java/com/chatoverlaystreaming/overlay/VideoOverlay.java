package com.chatoverlaystreaming.overlay;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Overlay de reproducción de vídeo basado en JavaFX (modo GDI/software rendering).
 *
 * Captura frames de una escena JavaFX off-screen mediante snapshots y los pinta
 * en un JPanel de Swing, lo que permite que OBS capture la ventana con BitBlt.
 *
 * Estrategia de chroma key:
 *   - Cuando el chroma está activo, la ventana tiene fondo del color croma sólido.
 *   - SetLayeredWindowAttributes hace ese color transparente para el usuario en pantalla.
 *   - OBS captura con BitBlt antes del compositing de Windows y ve el color sólido,
 *     permitiéndole aplicar su propio filtro Chroma Key.
 *   - El VideoPanel aplica el chroma al frame antes de pintarlo, por lo que donde
 *     el frame era del color croma se ve el fondo de la ventana (transparente en pantalla,
 *     sólido para OBS).
 *   - Cuando el chroma no está activo, el fondo es negro sólido.
 */
public class VideoOverlay extends JFrame implements VideoPlayerWindow {

    // ── Estado ────────────────────────────────────────────────────────────────

    private final int    WIDTH;
    private final int    HEIGHT;
    private final Path   videoFile;
    private final double volume;
    private final int    displayIndex;
    private final int    fps;

    /** Color actual del fondo/croma. Negro cuando no hay chroma activo. */
    private Color chromaWindowColor = Color.BLACK;

    // ── Componentes ───────────────────────────────────────────────────────────

    private final VideoPanel videoPanel;
    private volatile MediaPlayer player;

    // ── Callbacks ─────────────────────────────────────────────────────────────

    private Runnable         onReadyCallback;
    private Consumer<String> onErrorCallback;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea el overlay de vídeo y lo posiciona en pantalla.
     *
     * @param videoFile    Archivo de vídeo a reproducir.
     * @param volume       Volumen entre 0.0 y 1.0.
     * @param width        Ancho del panel en píxeles.
     * @param height       Alto del panel en píxeles.
     * @param displayIndex Índice de pantalla (0 = principal). Si no existe, usa la principal.
     * @param fps          FPS del snapshot (30 o 60).
     * @param windowTitle  Título de la ventana (para captura por nombre en OBS).
     * @param posX         Posición X relativa a la pantalla elegida.
     * @param posY         Posición Y relativa a la pantalla elegida.
     * @param randomPos    Si true, ignora posX/posY y genera una posición aleatoria.
     */
    public VideoOverlay(Path videoFile, double volume,
                        int width, int height, int displayIndex, int fps,
                        String windowTitle, int posX, int posY, boolean randomPos) {
        this.videoFile    = videoFile;
        this.volume       = volume;
        this.WIDTH        = width;
        this.HEIGHT       = height;
        this.displayIndex = displayIndex;
        this.fps          = fps;

        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setTitle(windowTitle != null && !windowTitle.isBlank() ? windowTitle : "OverlayVideo");
        setSize(width, height);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setIconImage(new ImageIcon("icon.png").getImage());

        // Fondo negro por defecto; se actualiza al aplicar chroma
        applyWindowBackground(Color.BLACK);

        videoPanel = new VideoPanel(width, height);
        add(videoPanel, BorderLayout.CENTER);

        positionOnScreen(posX, posY, randomPos);
    }

    // ── Posicionamiento ───────────────────────────────────────────────────────

    /**
     * Calcula y aplica la posición de la ventana en la pantalla destino.
     * Si la pantalla elegida no existe, usa la principal.
     * Ajusta la posición para que el panel no se salga de los límites de la pantalla.
     */
    private void positionOnScreen(int posX, int posY, boolean randomPos) {
        GraphicsDevice[] screens = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();

        int targetIndex = (displayIndex > 0 && displayIndex < screens.length)
                ? displayIndex : 0;

        if (targetIndex != displayIndex) {
            System.out.println("[VideoOverlay] Pantalla " + (displayIndex + 1)
                    + " no disponible, usando pantalla principal.");
        }

        Rectangle bounds = screens[targetIndex].getDefaultConfiguration().getBounds();

        int absX, absY;
        if (randomPos) {
            int maxX = Math.max(0, bounds.width  - WIDTH);
            int maxY = Math.max(0, bounds.height - HEIGHT);
            absX = bounds.x + new Random().nextInt(maxX + 1);
            absY = bounds.y + new Random().nextInt(maxY + 1);
        } else {
            absX = Math.max(bounds.x, Math.min(bounds.x + posX,
                    bounds.x + bounds.width  - WIDTH));
            absY = Math.max(bounds.y, Math.min(bounds.y + posY,
                    bounds.y + bounds.height - HEIGHT));
        }

        setLocation(absX, absY);
    }

    // ── Reproducción ──────────────────────────────────────────────────────────

    /**
     * Inicia la reproducción del vídeo.
     * Crea la escena JavaFX off-screen, el MediaPlayer y el AnimationTimer
     * que captura frames al ritmo configurado y los envía al VideoPanel.
     * Debe llamarse después de setVisible(true).
     */
    @Override
    public void play() {
        Platform.runLater(() -> {
            try {
                Media     media = new Media(videoFile.toUri().toString());
                player          = new MediaPlayer(media);
                player.setVolume(Math.max(0.0, Math.min(1.0, volume)));

                MediaView mediaView = new MediaView(player);
                mediaView.setFitWidth(WIDTH);
                mediaView.setFitHeight(HEIGHT);
                mediaView.setPreserveRatio(true);

                StackPane pane = new StackPane(mediaView);
                pane.setStyle("-fx-background-color: black;");
                pane.setPrefSize(WIDTH, HEIGHT);

                // Escena off-screen: no se añade a ninguna ventana JavaFX visible
                Scene scene = new Scene(pane, WIDTH, HEIGHT,
                        javafx.scene.paint.Color.BLACK);

                AnimationTimer frameTimer = buildFrameTimer(scene);

                player.setOnReady(() -> {
                    player.setVolume(Math.max(0.0, Math.min(1.0, volume)));
                    if (onReadyCallback != null) onReadyCallback.run();
                    frameTimer.start();
                    player.play();
                });

                player.setOnEndOfMedia(() -> stopPlayback(frameTimer, false));
                player.setOnError(()    -> stopPlayback(frameTimer, true));

            } catch (Exception e) {
                System.err.println("[VideoOverlay] Error iniciando reproducción: "
                        + e.getMessage());
                SwingUtilities.invokeLater(this::safeDispose);
            }
        });
    }

    /**
     * Construye el AnimationTimer que captura snapshots de la escena JavaFX
     * al ritmo de fps configurado y los envía al VideoPanel.
     *
     * @param scene Escena JavaFX off-screen de la que capturar frames.
     */
    private AnimationTimer buildFrameTimer(Scene scene) {
        long[] last          = {0};
        long   frameInterval = 1_000_000_000L / fps;

        return new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (player == null) { stop(); return; }
                if (now - last[0] < frameInterval) return;
                last[0] = now;

                WritableImage snapshot = scene.snapshot(null);
                if (snapshot == null) return;

                BufferedImage frame = SwingFXUtils.fromFXImage(snapshot, null);
                videoPanel.setFrame(frame);
            }
        };
    }

    /**
     * Para la reproducción, libera el MediaPlayer y cierra la ventana.
     *
     * @param frameTimer Timer a detener.
     * @param isError    Si true, notifica el error al callback antes de cerrar.
     */
    private void stopPlayback(AnimationTimer frameTimer, boolean isError) {
        frameTimer.stop();

        if (isError && onErrorCallback != null) {
            String msg = (player != null && player.getError() != null)
                    ? player.getError().getMessage() : "desconocido";
            System.err.println("[VideoOverlay] Error de reproducción: " + msg);
            onErrorCallback.accept(msg);
        }

        MediaPlayer p = player;
        player = null;
        if (p != null) { p.stop(); p.dispose(); }

        SwingUtilities.invokeLater(() -> {
            setVisible(false);
            safeDispose();
        });
    }

    /** Llama a super.dispose() evitando llamadas recursivas. */
    private void safeDispose() {
        VideoOverlay.super.dispose();
    }

    // ── Chroma key ────────────────────────────────────────────────────────────

    /**
     * Configura el chroma key del overlay.
     *
     * Cuando está activo:
     *   - El fondo de la ventana se pone del color croma.
     *   - SetLayeredWindowAttributes hace ese color transparente para el usuario.
     *   - OBS (BitBlt) ve el color sólido y puede aplicar su filtro Chroma Key.
     *   - El VideoPanel aplica el chroma al frame antes de pintarlo.
     *
     * Cuando está desactivado:
     *   - El fondo vuelve a negro sólido.
     *   - No se aplica color key de Windows.
     *
     * @param enabled   Si true, activa el chroma key.
     * @param color     Color a eliminar del fondo del vídeo.
     * @param tolerance Tolerancia de distancia euclídea (0-255).
     */
    @Override
    public void setChroma(boolean enabled, Color color, int tolerance) {
        videoPanel.setChroma(enabled, color, tolerance);
        chromaWindowColor = enabled ? color : Color.BLACK;

        SwingUtilities.invokeLater(() -> {
            applyWindowBackground(chromaWindowColor);
            if (enabled) {
                applyWindowColorKey(color);
            } else {
                removeWindowColorKey();
            }
        });
    }

    /**
     * Aplica el color de fondo a la ventana y al content pane.
     * Necesario para que el color croma sea visible para OBS.
     */
    private void applyWindowBackground(Color color) {
        setBackground(color);
        getContentPane().setBackground(color);
        if (color.getAlpha() < 255) {
            // Transparencia real para el caso sin chroma (aunque actualmente usamos negro)
            getRootPane().setOpaque(false);
            ((JComponent) getContentPane()).setOpaque(false);
        } else {
            getRootPane().setOpaque(true);
            ((JComponent) getContentPane()).setOpaque(true);
        }
    }

    /**
     * Aplica SetLayeredWindowAttributes para hacer el color croma transparente
     * en pantalla para el usuario. OBS con BitBlt no ve este efecto.
     *
     * @param color Color a hacer transparente.
     */
    private void applyWindowColorKey(Color color) {
        try {
            com.sun.jna.Pointer pointer = com.sun.jna.Native.getComponentPointer(this);
            if (pointer == null) return;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    new com.sun.jna.platform.win32.WinDef.HWND(pointer);

            // Activar WS_EX_LAYERED si no está activo
            int GWL_EXSTYLE   = -20;
            int WS_EX_LAYERED = 0x00080000;
            int style = com.sun.jna.platform.win32.User32.INSTANCE
                    .GetWindowLong(hwnd, GWL_EXSTYLE);
            style |= WS_EX_LAYERED;
            com.sun.jna.platform.win32.User32.INSTANCE
                    .SetWindowLong(hwnd, GWL_EXSTYLE, style);

            // Formato de color de Windows: 0x00BBGGRR
            int winColor = (color.getBlue()  << 16)
                         | (color.getGreen() <<  8)
                         |  color.getRed();

            // LWA_COLORKEY (0x1): usar color key para transparencia
            WindowClickThrough.User32Extra.INSTANCE
                    .SetLayeredWindowAttributes(hwnd, winColor, (byte) 255, 0x1);

            System.out.println("[VideoOverlay] Color key aplicado: " + color);
        } catch (Exception e) {
            System.err.println("[VideoOverlay] Error aplicando color key: " + e.getMessage());
        }
    }

    /**
     * Elimina el color key de Windows y restaura la ventana a opaca.
     */
    private void removeWindowColorKey() {
        try {
            com.sun.jna.Pointer pointer = com.sun.jna.Native.getComponentPointer(this);
            if (pointer == null) return;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    new com.sun.jna.platform.win32.WinDef.HWND(pointer);

            // Desactivar WS_EX_LAYERED
            int GWL_EXSTYLE   = -20;
            int WS_EX_LAYERED = 0x00080000;
            int style = com.sun.jna.platform.win32.User32.INSTANCE
                    .GetWindowLong(hwnd, GWL_EXSTYLE);
            style &= ~WS_EX_LAYERED;
            com.sun.jna.platform.win32.User32.INSTANCE
                    .SetWindowLong(hwnd, GWL_EXSTYLE, style);
        } catch (Exception e) {
            System.err.println("[VideoOverlay] Error eliminando color key: " + e.getMessage());
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    /**
     * Libera el MediaPlayer de JavaFX antes de cerrar la ventana.
     * La parada del player se hace en el hilo de JavaFX para evitar
     * excepciones de threading.
     */
    @Override
    public void dispose() {
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            Platform.runLater(() -> { p.stop(); p.dispose(); });
        }
        super.dispose();
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    @Override public void setOnReady(Runnable callback)         { this.onReadyCallback = callback; }
    @Override public void setOnError(Consumer<String> callback) { this.onErrorCallback = callback; }

    // ── VideoPanel ────────────────────────────────────────────────────────────

    /**
     * Panel interno que recibe los frames capturados del snapshot de JavaFX
     * y los pinta en el EDT de Swing.
     *
     * Cuando el chroma está activo:
     *   - Aplica el algoritmo de distancia euclídea para hacer transparentes
     *     los píxeles cercanos al color croma.
     *   - Donde el frame es transparente se ve el fondo de la ventana (el color croma),
     *     que Windows hace transparente al usuario mediante color key.
     */
    private static class VideoPanel extends JPanel {

        private final AtomicReference<BufferedImage> currentFrame = new AtomicReference<>();
        private final int width;
        private final int height;

        // Estado del chroma (volatile para visibilidad entre hilos)
        private volatile boolean chromaEnabled   = false;
        private volatile int     chromaR         = 0;
        private volatile int     chromaG         = 255;
        private volatile int     chromaB         = 0;
        private volatile int     chromaTolerance = 40;
        volatile Color           chromaColor     = Color.GREEN;

        VideoPanel(int width, int height) {
            this.width  = width;
            this.height = height;
            setOpaque(true); // opaco para que OBS lo capture bien con BitBlt
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(width, height));
        }

        /**
         * Recibe un nuevo frame, aplica el chroma si está activo,
         * y solicita repintado en el EDT.
         */
        void setFrame(BufferedImage frame) {
            if (chromaEnabled && frame != null) {
                frame = applyChroma(frame);
            }
            currentFrame.set(frame);
            SwingUtilities.invokeLater(this::repaint);
        }

        /**
         * Configura el chroma key del panel.
         * Actualiza el color de fondo para que coincida con el color croma.
         */
        void setChroma(boolean enabled, Color color, int tolerance) {
            this.chromaEnabled   = enabled;
            this.chromaColor     = color;
            this.chromaR         = color.getRed();
            this.chromaG         = color.getGreen();
            this.chromaB         = color.getBlue();
            this.chromaTolerance = tolerance;
            // El fondo del panel es el color croma cuando está activo (lo ve OBS),
            // negro cuando no (fondo limpio sin chroma)
            setBackground(enabled ? color : Color.BLACK);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;

            // Pintar el fondo (color croma o negro) — OBS lo ve tal cual
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());

            BufferedImage frame = currentFrame.get();
            if (frame == null) return;

            // Escalar manteniendo proporción y centrar en el panel
            double scale = Math.min(
                    (double) getWidth()  / frame.getWidth(),
                    (double) getHeight() / frame.getHeight());
            int drawW = (int)(frame.getWidth()  * scale);
            int drawH = (int)(frame.getHeight() * scale);
            int drawX = (getWidth()  - drawW) / 2;
            int drawY = (getHeight() - drawH) / 2;

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(frame, drawX, drawY, drawW, drawH, null);
        }

        /**
         * Aplica el chroma key al frame.
         * Los píxeles cuya distancia euclídea al color croma sea menor que
         * la tolerancia se vuelven transparentes (alpha = 0).
         * Donde son transparentes se verá el fondo del panel (el color croma),
         * que Windows hace transparente al usuario mediante color key.
         *
         * @param src Frame original capturado del snapshot de JavaFX.
         * @return Nuevo frame con los píxeles cromados transparentes.
         */
        private BufferedImage applyChroma(BufferedImage src) {
            // Asegurar formato ARGB para poder escribir el canal alpha
            BufferedImage argb;
            if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
                argb = src;
            } else {
                argb = new BufferedImage(src.getWidth(), src.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = argb.createGraphics();
                g.drawImage(src, 0, 0, null);
                g.dispose();
            }

            int   w      = argb.getWidth();
            int   h      = argb.getHeight();
            int[] pixels = argb.getRGB(0, 0, w, h, null, 0, w);

            double tolSq = (double) chromaTolerance * chromaTolerance;

            for (int i = 0; i < pixels.length; i++) {
                int px = pixels[i];
                int r  = (px >> 16) & 0xFF;
                int g  = (px >>  8) & 0xFF;
                int b  =  px        & 0xFF;

                double distSq = (double)(r - chromaR) * (r - chromaR)
                              + (double)(g - chromaG) * (g - chromaG)
                              + (double)(b - chromaB) * (b - chromaB);

                if (distSq <= tolSq) {
                    pixels[i] = 0x00000000; // transparente
                }
            }

            BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            result.setRGB(0, 0, w, h, pixels, 0, w);
            return result;
        }
    }
}