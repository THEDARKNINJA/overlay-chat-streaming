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
import java.util.function.Consumer;

public class VlcjVideoOverlay extends JFrame implements VideoPlayerWindow {

    private final Path   videoFile;
    private final double volume;
    private final int    displayIndex;
    private final String windowTitle;
    private final int    WIDTH;
    private final int    HEIGHT;

    // Chroma
    private volatile boolean chromaEnabled  = false;
    private volatile int     chromaR        = 0;
    private volatile int     chromaG        = 255;
    private volatile int     chromaB        = 0;
    private volatile int     chromaTolerance = 40;

    private Runnable         onReadyCallback;
    private Consumer<String> onErrorCallback;

    private CallbackMediaPlayerComponent mediaPlayerComponent;

    // Buffer de renderizado
    private BufferedImage renderImage;
    private int[]         renderPixels;

    // Panel que pinta los frames
    private final FramePanel framePanel;

    public VlcjVideoOverlay(Path videoFile, double volume,
                             int width, int height,
                             int displayIndex, int fps,
                             String windowTitle, int posX, int posY, boolean randomPos) {
        this.videoFile    = videoFile;
        this.volume       = volume;
        this.WIDTH        = width;
        this.HEIGHT       = height;
        this.displayIndex = displayIndex;
        this.windowTitle  = windowTitle != null ? windowTitle : "VideoOverlay";

        setTitle(this.windowTitle);
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
        setBackground(new Color(0, 0, 0, 0));
        getRootPane().setOpaque(false);
        ((JComponent) getContentPane()).setOpaque(false);
        setSize(width, height);

        positionOnScreen(posX, posY, randomPos);

        framePanel = new FramePanel(width, height);
        add(framePanel, BorderLayout.CENTER);
    }

    private void positionOnScreen(int posX, int posY, boolean randomPos) {
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

        int absX, absY;
        // Posición relativa a la pantalla elegida
        // fijar posición según si random o no, Asegurarse de que no se sale de la pantalla
        if (randomPos) {
            int maxX = Math.max(0, bounds.width  - WIDTH);
            int maxY = Math.max(0, bounds.height - HEIGHT);
            absX = bounds.x + new java.util.Random().nextInt(maxX + 1);
            absY = bounds.y + new java.util.Random().nextInt(maxY + 1);
        } else {
            absX = bounds.x + posX;
            absY = bounds.y + posY;
            absX = Math.max(bounds.x, Math.min(absX, bounds.x + bounds.width  - WIDTH));
            absY = Math.max(bounds.y, Math.min(absY, bounds.y + bounds.height - HEIGHT));
        }

        setLocation(absX, absY);
    }

    @Override
    public void play() {
        // Preparar buffer de renderizado al tamaño del panel
        renderImage  = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        renderPixels = ((DataBufferInt) renderImage.getRaster()
                .getDataBuffer()).getData();

        BufferFormatCallback bufferFormatCallback = new BufferFormatCallback() {
            @Override
            public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
                return new RV32BufferFormat(WIDTH, HEIGHT);
            }

            @Override
            public void allocatedBuffers(ByteBuffer[] buffers) {
                // no necesitamos hacer nada aquí
            }
        };

        RenderCallback renderCallback = new RenderCallback() {
            @Override
            public void display(uk.co.caprica.vlcj.player.base.MediaPlayer mediaPlayer,
                                ByteBuffer[] nativeBuffers,
                                BufferFormat bufferFormat) {
                ByteBuffer byteBuffer = nativeBuffers[0];
                byteBuffer.asIntBuffer().get(renderPixels, 0, renderPixels.length);
                byteBuffer.rewind();

                BufferedImage frame;
                if (chromaEnabled) {
                    frame = applyChroma(renderImage);
                } else {
                    // Copiar el buffer al frame sin chroma
                    frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
                    int[] dst = ((DataBufferInt) frame.getRaster()
                            .getDataBuffer()).getData();
                    // RV32 viene como BGRA, necesitamos ARGB
                    for (int i = 0; i < renderPixels.length; i++) {
                        int px = renderPixels[i];
                        int b = (px >> 16) & 0xFF;
                        int g = (px >>  8) & 0xFF;
                        int r =  px        & 0xFF;
                        dst[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    }
                }

                framePanel.setFrame(frame);
            }
        };

        // Constructor correcto de vlcj 4:
        // (MediaPlayerFactory, FullScreenStrategy, InputEvents, lockBuffers,
        //  CallbackImagePainter, RenderCallback, BufferFormatCallback, JComponent)
        mediaPlayerComponent = new CallbackMediaPlayerComponent(
                null, null, null,
                true,         // lockBuffers
                null,         // imagePainter (null = usamos nuestro renderCallback)
                renderCallback,
                bufferFormatCallback,
                null          // videoSurfaceComponent (null = no añadir al layout del componente)
        );

        mediaPlayerComponent.mediaPlayer().events()
                .addMediaPlayerEventListener(new MediaPlayerEventAdapter() {

            @Override
            public void playing(uk.co.caprica.vlcj.player.base.MediaPlayer player) {
                // Aplicar volumen al inicio y en playing para asegurarse
                int vlcVolume = (int)(volume * 100);
                mediaPlayerComponent.mediaPlayer().audio().setVolume(vlcVolume);
                System.out.println("[vlcj] Volumen aplicado: " + vlcVolume + "%");
                if (onReadyCallback != null) {
                    SwingUtilities.invokeLater(onReadyCallback::run);
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
                String msg = "Error vlcj: " + videoFile.getFileName();
                System.err.println("[vlcj] " + msg);
                if (onErrorCallback != null) {
                    SwingUtilities.invokeLater(() -> onErrorCallback.accept(msg));
                }
                SwingUtilities.invokeLater(() -> {
                    setVisible(false);
                    dispose();
                });
            }
        });

        // Aplicar volumen antes de play también
        int vlcVolume = (int)(volume * 100);
        mediaPlayerComponent.mediaPlayer().audio().setVolume(vlcVolume);

        mediaPlayerComponent.mediaPlayer().media()
                .play(videoFile.toAbsolutePath().toString());
    }

    private BufferedImage applyChroma(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] srcPixels = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();

        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] dstPixels = ((DataBufferInt) result.getRaster()
                .getDataBuffer()).getData();

        double tolSq = (double) chromaTolerance * chromaTolerance;

        for (int i = 0; i < srcPixels.length; i++) {
            // RV32 viene en formato BGRA desde vlcj
            int px = srcPixels[i];
            int b  = (px >> 16) & 0xFF;
            int g  = (px >>  8) & 0xFF;
            int r  =  px        & 0xFF;

            double distSq = Math.pow(r - chromaR, 2)
                          + Math.pow(g - chromaG, 2)
                          + Math.pow(b - chromaB, 2);

            if (distSq <= tolSq) {
                dstPixels[i] = 0x00000000; // transparente
            } else {
                // Reordenar a ARGB
                dstPixels[i] = (0xFF << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return result;
    }

    @Override
    public void setChroma(boolean enabled, Color color, int tolerance) {
        this.chromaEnabled   = enabled;
        this.chromaR         = color.getRed();
        this.chromaG         = color.getGreen();
        this.chromaB         = color.getBlue();
        this.chromaTolerance = tolerance;
        System.out.println("[vlcj] Chroma configurado: RGB=("
                + chromaR + "," + chromaG + "," + chromaB
                + ") tolerancia=" + tolerance);
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
        if (mediaPlayerComponent != null) {
            try {
                mediaPlayerComponent.mediaPlayer().controls().stop();
                mediaPlayerComponent.release();
            } catch (Exception e) {
                System.err.println("[vlcj] Error al liberar: " + e.getMessage());
            }
            mediaPlayerComponent = null;
        }
        super.dispose();
    }

    // ── Panel que pinta los frames ────────────────────────────────────────────

    private static class FramePanel extends JPanel {

        private volatile BufferedImage currentFrame;

        FramePanel(int width, int height) {
            setOpaque(false);
            setBackground(new Color(0, 0, 0, 0));
            setPreferredSize(new Dimension(width, height));
        }

        void setFrame(BufferedImage frame) {
            this.currentFrame = frame;
            SwingUtilities.invokeLater(this::repaint);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            // Limpiar con transparencia
            g2.setComposite(AlphaComposite.Clear);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setComposite(AlphaComposite.SrcOver);

            BufferedImage frame = currentFrame;
            if (frame != null) {
                double scaleX = (double) getWidth()  / frame.getWidth();
                double scaleY = (double) getHeight() / frame.getHeight();
                double scale  = Math.min(scaleX, scaleY);
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
}