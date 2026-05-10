package com.chatoverlaystreaming.overlay;

import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.component.CallbackMediaPlayerComponent;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Overlay de reproducción de vídeo basado en vlcj.
 *
 * vlcj entrega frames directamente en un buffer compartido (RV32), evitando
 * el coste de los snapshots de JavaFX. Soporta cualquier formato que VLC pueda
 * reproducir (H.264, H.265, MKV, etc.).
 *
 * Estrategia de chroma key (idéntica a VideoOverlay):
 *   - Cuando el chroma está activo, la ventana tiene fondo del color croma sólido.
 *   - SetLayeredWindowAttributes hace ese color transparente para el usuario en pantalla.
 *   - OBS captura con BitBlt antes del compositing y ve el color sólido,
 *     permitiéndole aplicar su propio filtro Chroma Key.
 *   - FramePanel aplica el chroma al frame antes de pintarlo. Donde el frame
 *     es transparente se ve el fondo del color croma (transparente al usuario, sólido en OBS).
 *   - Cuando el chroma no está activo, el fondo es negro sólido.
 *
 * Nota sobre el orden de bytes de RV32:
 *   vlcj entrega los píxeles con el alpha en 0 y RGB en orden correcto para Java.
 *   La conversión es simplemente: dst[i] = 0xFF000000 | (src[i] & 0x00FFFFFF).
 */
public class VlcjVideoOverlay extends JFrame implements VideoPlayerWindow {

    // ── Estado ────────────────────────────────────────────────────────────────

    private final Path   videoFile;
    private final double volume;
    private final int    displayIndex;
    private final int    WIDTH;
    private final int    HEIGHT;

    /** Color actual del fondo/croma. Negro cuando no hay chroma activo. */
    private Color chromaWindowColor = Color.BLACK;

    // ── Chroma (volatile para visibilidad entre el hilo de vlcj y el EDT) ────

    private volatile boolean chromaEnabled   = false;
    private volatile int     chromaR         = 0;
    private volatile int     chromaG         = 255;
    private volatile int     chromaB         = 0;
    private volatile int     chromaTolerance = 40;

    // ── Callbacks ─────────────────────────────────────────────────────────────

    private Runnable         onReadyCallback;
    private Consumer<String> onErrorCallback;

    // ── Componentes ───────────────────────────────────────────────────────────

    private CallbackMediaPlayerComponent mediaPlayerComponent;

    /**
     * Buffer compartido entre vlcj y el hilo de renderizado.
     * vlcj escribe directamente en renderPixels a través de renderImage.
     */
    private BufferedImage renderImage;
    private int[]         renderPixels;

    private final FramePanel framePanel;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Crea el overlay de vídeo con vlcj y lo posiciona en pantalla.
     *
     * @param videoFile    Archivo de vídeo a reproducir.
     * @param volume       Volumen entre 0.0 y 1.0.
     * @param width        Ancho del panel en píxeles.
     * @param height       Alto del panel en píxeles.
     * @param displayIndex Índice de pantalla (0 = principal). Si no existe, usa la principal.
     * @param fps          No usado en vlcj (vlcj entrega frames a su propio ritmo), mantenido por interfaz.
     * @param windowTitle  Título de la ventana (para captura por nombre en OBS).
     * @param posX         Posición X relativa a la pantalla elegida.
     * @param posY         Posición Y relativa a la pantalla elegida.
     * @param randomPos    Si true, ignora posX/posY y genera una posición aleatoria.
     */
    public VlcjVideoOverlay(Path videoFile, double volume,
                             int width, int height, int displayIndex, int fps,
                             String windowTitle, int posX, int posY, boolean randomPos) {
        this.videoFile    = videoFile;
        this.volume       = volume;
        this.WIDTH        = width;
        this.HEIGHT       = height;
        this.displayIndex = displayIndex;

        setTitle(windowTitle != null && !windowTitle.isBlank() ? windowTitle : "VideoOverlay");
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setIconImage(new ImageIcon("icon.png").getImage());
        setSize(width, height);

        // Fondo negro por defecto; se actualiza al aplicar chroma
        applyWindowBackground(Color.BLACK);

        framePanel = new FramePanel(width, height);
        add(framePanel, BorderLayout.CENTER);

        positionOnScreen(posX, posY, randomPos);
    }

    // ── Posicionamiento ───────────────────────────────────────────────────────

    /**
     * Calcula y aplica la posición de la ventana en la pantalla destino.
     * Si la pantalla elegida no existe, usa la principal.
     * Ajusta la posición para que el panel no se salga de los límites.
     */
    private void positionOnScreen(int posX, int posY, boolean randomPos) {
        GraphicsDevice[] screens = GraphicsEnvironment
                .getLocalGraphicsEnvironment().getScreenDevices();

        int targetIndex = (displayIndex > 0 && displayIndex < screens.length)
                ? displayIndex : 0;

        if (targetIndex != displayIndex) {
            System.out.println("[VlcjOverlay] Pantalla " + (displayIndex + 1)
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
     * Configura el buffer de renderizado, el RenderCallback que recibe frames
     * de vlcj y el MediaPlayerEventListener para gestionar eventos del ciclo de vida.
     */
    @Override
    public void play() {
        // Buffer de renderizado: vlcj escribe aquí en cada frame
        renderImage  = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        renderPixels = ((DataBufferInt) renderImage.getRaster().getDataBuffer()).getData();

        BufferFormatCallback bufferFormatCallback = new BufferFormatCallback() {
            @Override
            public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
                // Forzar el tamaño del panel independientemente del tamaño del vídeo
                return new RV32BufferFormat(WIDTH, HEIGHT);
            }

            @Override
            public void allocatedBuffers(ByteBuffer[] buffers) {}
        };

        RenderCallback renderCallback = new RenderCallback() {
            @Override
            public void display(uk.co.caprica.vlcj.player.base.MediaPlayer mediaPlayer,
                                ByteBuffer[] nativeBuffers, BufferFormat bufferFormat) {
                // Copiar el buffer de vlcj al array de píxeles
                ByteBuffer byteBuffer = nativeBuffers[0];
                byteBuffer.asIntBuffer().get(renderPixels, 0, renderPixels.length);
                byteBuffer.rewind();

                // Procesar frame y enviarlo al panel
                framePanel.setFrame(buildFrame());
            }
        };

        mediaPlayerComponent = new CallbackMediaPlayerComponent(
                null, null, null,
                true,             // lockBuffers: vlcj bloquea el buffer durante display()
                null,             // imagePainter: null = usamos nuestro renderCallback
                renderCallback,
                bufferFormatCallback,
                null              // videoSurfaceComponent: null = no añadir al layout
        );

        mediaPlayerComponent.mediaPlayer().events()
                .addMediaPlayerEventListener(new MediaPlayerEventAdapter() {

            @Override
            public void playing(uk.co.caprica.vlcj.player.base.MediaPlayer player) {
                // Aplicar volumen al inicio (vlcj puede ignorarlo si se aplica antes)
                int vlcVolume = (int)(volume * 100);
                mediaPlayerComponent.mediaPlayer().audio().setVolume(vlcVolume);

                if (onReadyCallback != null) {
                    SwingUtilities.invokeLater(onReadyCallback::run);
                }

                // Aplicar color key de Windows cuando el chroma está activo
                if (chromaEnabled) {
                    SwingUtilities.invokeLater(() -> applyWindowColorKey(chromaWindowColor));
                }
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
                String msg = "[VlcjOverlay] Error reproduciendo: " + videoFile.getFileName();
                System.err.println(msg);
                if (onErrorCallback != null) {
                    SwingUtilities.invokeLater(() -> onErrorCallback.accept(msg));
                }
                SwingUtilities.invokeLater(() -> {
                    setVisible(false);
                    dispose();
                });
            }
        });

        // Aplicar volumen antes de play como medida adicional
        mediaPlayerComponent.mediaPlayer().audio().setVolume((int)(volume * 100));
        mediaPlayerComponent.mediaPlayer().media()
                .play(videoFile.toAbsolutePath().toString());
    }

    /**
     * Construye el frame a partir del buffer actual de vlcj.
     * Si el chroma está activo, aplica el algoritmo de distancia euclídea.
     * Si no, convierte el formato RV32 de vlcj a ARGB de Java.
     *
     * RV32 de vlcj entrega los píxeles con alpha=0 y RGB en orden correcto para Java.
     * La conversión sin chroma es simplemente poner alpha=0xFF.
     */
    private BufferedImage buildFrame() {
        int w = WIDTH;
        int h = HEIGHT;

        BufferedImage frame  = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[]         dst    = ((DataBufferInt) frame.getRaster().getDataBuffer()).getData();
        double        tolSq  = (double) chromaTolerance * chromaTolerance;

        for (int i = 0; i < renderPixels.length; i++) {
            int px = renderPixels[i];

            // RV32: alpha=0, RGB en orden correcto
            int r = (px >> 16) & 0xFF;
            int g = (px >>  8) & 0xFF;
            int b =  px        & 0xFF;

            if (chromaEnabled) {
                double distSq = (double)(r - chromaR) * (r - chromaR)
                              + (double)(g - chromaG) * (g - chromaG)
                              + (double)(b - chromaB) * (b - chromaB);

                // Píxeles dentro del rango del croma → transparentes
                // Se verá el fondo del color croma de la ventana,
                // que Windows hace transparente al usuario pero OBS captura como sólido
                dst[i] = (distSq <= tolSq)
                        ? 0x00000000
                        : 0xFF000000 | (r << 16) | (g << 8) | b;
            } else {
                // Sin chroma: solo poner alpha a FF
                dst[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }

        return frame;
    }

    // ── Chroma key ────────────────────────────────────────────────────────────

    /**
     * Configura el chroma key del overlay. Misma estrategia que VideoOverlay:
     *   - Fondo sólido del color croma para OBS.
     *   - Color key de Windows para transparencia al usuario en pantalla.
     *   - buildFrame() aplica el chroma al frame píxel a píxel.
     *
     * @param enabled   Si true, activa el chroma key.
     * @param color     Color a eliminar del fondo del vídeo.
     * @param tolerance Tolerancia de distancia euclídea (0-255).
     */
    @Override
    public void setChroma(boolean enabled, Color color, int tolerance) {
        this.chromaEnabled   = enabled;
        this.chromaR         = color.getRed();
        this.chromaG         = color.getGreen();
        this.chromaB         = color.getBlue();
        this.chromaTolerance = tolerance;
        this.chromaWindowColor = enabled ? color : Color.BLACK;

        System.out.println("[VlcjOverlay] Chroma " + (enabled ? "activado" : "desactivado")
                + ": RGB=(" + chromaR + "," + chromaG + "," + chromaB
                + ") tolerancia=" + tolerance);

        SwingUtilities.invokeLater(() -> {
            framePanel.setChromaBackground(enabled ? color : Color.BLACK);
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
     * La ventana debe ser opaca para que OBS la capture bien con BitBlt.
     */
    private void applyWindowBackground(Color color) {
        setBackground(color);
        getRootPane().setOpaque(true);
        ((JComponent) getContentPane()).setOpaque(true);
        getContentPane().setBackground(color);
    }

    /**
     * Aplica SetLayeredWindowAttributes para hacer el color croma transparente
     * al usuario en pantalla. OBS con BitBlt no ve este efecto.
     *
     * @param color Color a hacer transparente.
     */
    private void applyWindowColorKey(Color color) {
        try {
            com.sun.jna.Pointer pointer = com.sun.jna.Native.getComponentPointer(this);
            if (pointer == null) return;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    new com.sun.jna.platform.win32.WinDef.HWND(pointer);

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

            // LWA_COLORKEY (0x1): hacer transparente el color dado
            WindowClickThrough.User32Extra.INSTANCE
                    .SetLayeredWindowAttributes(hwnd, winColor, (byte) 255, 0x1);

            System.out.println("[VlcjOverlay] Color key aplicado: " + color);
        } catch (Exception e) {
            System.err.println("[VlcjOverlay] Error aplicando color key: " + e.getMessage());
        }
    }

    /** Elimina el color key de Windows y restaura la ventana a opaca sin layering. */
    private void removeWindowColorKey() {
        try {
            com.sun.jna.Pointer pointer = com.sun.jna.Native.getComponentPointer(this);
            if (pointer == null) return;
            com.sun.jna.platform.win32.WinDef.HWND hwnd =
                    new com.sun.jna.platform.win32.WinDef.HWND(pointer);

            int GWL_EXSTYLE   = -20;
            int WS_EX_LAYERED = 0x00080000;
            int style = com.sun.jna.platform.win32.User32.INSTANCE
                    .GetWindowLong(hwnd, GWL_EXSTYLE);
            style &= ~WS_EX_LAYERED;
            com.sun.jna.platform.win32.User32.INSTANCE
                    .SetWindowLong(hwnd, GWL_EXSTYLE, style);
        } catch (Exception e) {
            System.err.println("[VlcjOverlay] Error eliminando color key: " + e.getMessage());
        }
    }

    // ── Ciclo de vida ─────────────────────────────────────────────────────────

    /**
     * Libera el MediaPlayerComponent de vlcj.
     * vlcj gestiona sus propios hilos internamente, por lo que no necesitamos
     * el equivalente al Platform.runLater de JavaFX.
     */
    @Override
    public void dispose() {
        if (mediaPlayerComponent != null) {
            try {
                mediaPlayerComponent.mediaPlayer().controls().stop();
                mediaPlayerComponent.release();
            } catch (Exception e) {
                System.err.println("[VlcjOverlay] Error liberando recursos: " + e.getMessage());
            }
            mediaPlayerComponent = null;
        }
        super.dispose();
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────

    @Override public void setOnReady(Runnable callback)         { this.onReadyCallback = callback; }
    @Override public void setOnError(Consumer<String> callback) { this.onErrorCallback = callback; }

    // ── FramePanel ────────────────────────────────────────────────────────────

    /**
     * Panel interno que recibe los frames procesados por buildFrame()
     * y los pinta en el EDT de Swing.
     *
     * El fondo del panel es el color croma cuando el chroma está activo
     * (visible para OBS, transparente al usuario por el color key de Windows),
     * o negro cuando no hay chroma.
     */
    private static class FramePanel extends JPanel {

        private volatile BufferedImage currentFrame;

        FramePanel(int width, int height) {
            setOpaque(true);
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(width, height));
        }

        /** Actualiza el color de fondo del panel (color croma o negro). */
        void setChromaBackground(Color color) {
            setBackground(color);
        }

        /** Recibe un nuevo frame procesado y solicita repintado en el EDT. */
        void setFrame(BufferedImage frame) {
            this.currentFrame = frame;
            SwingUtilities.invokeLater(this::repaint);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;

            // Fondo sólido: color croma (para OBS) o negro (sin chroma)
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());

            BufferedImage frame = currentFrame;
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
    }
}