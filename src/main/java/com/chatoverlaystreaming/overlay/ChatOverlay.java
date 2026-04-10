package com.chatoverlaystreaming.overlay;

import com.chatoverlaystreaming.emotes.*;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.model.EmoteToken;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.PopupMenuEvent;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class ChatOverlay extends JFrame {

    private final JTextPane textPane;
    private final StyledDocument doc;
    private static final int MAX_MESSAGES = 50;

    private final TwitchEmoteCache  twitchEmoteCache = new TwitchEmoteCache();
    private final BTTVEmoteCache    bttvEmoteCache;
    private final TwitchBadgeCache  badgeCache;
    private final EmoteRenderer     emoteRenderer;
    private WindowClickThrough clickThrough;
    private TrayIcon trayIcon;
    private JMenuItem toggleItem;
    private JPanel dragBar;
    private Rectangle closeButtonRect = new Rectangle();
    private Rectangle resizeHandleRect = new Rectangle();
    private boolean showBackground;
    private boolean isLocked = true;
    private boolean hasFocus = false;

    private final Config config;
    private javax.swing.Timer saveTimer;

    public ChatOverlay(BlockingQueue<ChatMessage> queue,
                       String twitchChannelId,
                       String twitchClientId,
                       String twitchClientSecret, 
                       Config config) {

        this.config = config;

        setUndecorated(true);
        //setBackground(new Color(0, 0, 0, 0));
        setBackground(Color.MAGENTA);
        setAlwaysOnTop(true);
        setType(Window.Type.NORMAL);
        setSize(config.getPanelWidth(), config.getPanelHeight());
        setLocation(config.getPanelX(), config.getPanelY());
        this.showBackground = config.getShowBackground();
        emoteRenderer    = new EmoteRenderer(config.getIconSize());
        
        saveTimer = new javax.swing.Timer(500, e -> {
            try {
                Point loc  = getLocation();
                Dimension d = getSize();
                config.savePanel(loc.x, loc.y, d.width, d.height);
            } catch (IOException ex) {
                System.err.println("[Config] Error guardando posición: " + ex.getMessage());
            }
        });
        saveTimer.setRepeats(false); // solo dispara una vez por movimiento

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                // Reiniciar el temporizador cada vez que se mueve
                saveTimer.restart();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                saveTimer.restart();
            }
        });

        bttvEmoteCache = new BTTVEmoteCache(twitchChannelId);
        badgeCache     = new TwitchBadgeCache(twitchClientId,
                                              twitchClientSecret,
                                              twitchChannelId);

        // Panel principal con fondo semitransparente
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override /*
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
               // g2.setColor(new Color(10, 10, 10, config.getPanelAlpha()));
                        g2.setColor(Color.MAGENTA);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        // Luego pintar el fondo semitransparente del chat
                        // Ojo: sin alpha, color sólido
                        g2.setColor(new Color(0, 0, 0));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
            }
                */
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                if (showBackground) {
                    // Modo con fondo: magenta en esquinas + rectángulo oscuro
                    g2.setColor(Color.MAGENTA);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(new Color(10, 10, 10));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                } else {
                    // Modo sin fondo: todo magenta, solo texto visible
                    g2.setColor(Color.MAGENTA);
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                g2.dispose();
            }
        };
        //panel.setOpaque(false);
        panel.setOpaque(true);

        // Barra de arrastre en la parte superior
        dragBar = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                if (!hasFocus) return; // si no tiene foco, no pintar nada
                Graphics2D g2 = (Graphics2D) g.create();

                 g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Color según foco: morado si tiene foco, gris oscuro si no
                Color barColor = hasFocus
                    ? new Color(100, 60, 200, 170)
                    : new Color(40, 40, 40, 140);

                g2.setColor(barColor);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);

                // Tres puntitos para indicar que es arrastrable
                // Puntitos
                Color dotColor = hasFocus
                    ? new Color(220, 200, 255, 200)
                    : new Color(150, 150, 150, 180);
                g2.setColor(dotColor);
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                g2.fillOval(cx - 12, cy - 2, 5, 5);
                g2.fillOval(cx - 2,  cy - 2, 5, 5);
                g2.fillOval(cx + 8,  cy - 2, 5, 5);

                        // Botón X a la derecha
        int size = 14;
        int margin = 3;
        int bx = getWidth() - size - margin;
        int by = (getHeight() - size) / 2;
        closeButtonRect.setBounds(bx, by, size, size);

        // Fondo del botón X
        g2.setColor(new Color(180, 60, 60, 200));
        g2.fillRoundRect(bx, by, size, size, 4, 4);

        // La X
        g2.setColor(new Color(255, 255, 255, 230));
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int padding = 3;
        g2.drawLine(bx + padding, by + padding,
                    bx + size - padding, by + size - padding);
        g2.drawLine(bx + size - padding, by + padding,
                    bx + padding, by + size - padding);

                g2.dispose();
            }
        };
        dragBar.setOpaque(false);
        dragBar.setPreferredSize(new Dimension(0, 15));
        makeDraggable(dragBar);

        // Área resizable
        JPanel resizeHandle = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                if (!hasFocus) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(100, 60, 200, 180));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int w = getWidth();
                int h = getHeight();
                // Tres líneas diagonales en la esquina
                for (int i = 1; i <= 3; i++) {
                    int offset = i * 4;
                    g2.drawLine(w - offset, h, w, h - offset);
                }
                g2.dispose();
            }
        };
        resizeHandle.setOpaque(false);
        resizeHandle.setPreferredSize(new Dimension(16, 16));

        // Área de texto
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setOpaque(false);
        textPane.setBackground(new Color(0, 0, 0, 0));
        textPane.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 13));
        MutableAttributeSet lineSpacing = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(lineSpacing, 0.3f); // 30% extra entre líneas
        textPane.setParagraphAttributes(lineSpacing, false);
        //hacer que cuando tiene el foco no sea visible el caret (marca de introducir texto)
        textPane.setCaret(new DefaultCaret() {
            @Override public void paint(Graphics g) {}
            @Override public boolean isVisible() { return false; }
            @Override public boolean isSelectionVisible() { return false; }
        });
        doc = textPane.getStyledDocument();

        JScrollPane scroll = new JScrollPane(textPane);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(0, 0));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 0));

        panel.add(dragBar, BorderLayout.NORTH);
        panel.add(scroll,  BorderLayout.CENTER);
        add(panel);

        // Hilo consumidor de la cola
        Thread consumer = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ChatMessage msg = queue.take();
                    SwingUtilities.invokeLater(() -> appendMessage(msg));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        consumer.setDaemon(true);
        consumer.start();

        addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                hasFocus = true;
                isLocked = false;
                if (clickThrough != null) {
                    clickThrough.setClickThrough(false);
                }
                dragBar.repaint();
                resizeHandle.repaint();
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                hasFocus = false;
                isLocked = true;
                if (clickThrough != null) {
                    clickThrough.setClickThrough(true);
                }
                dragBar.repaint();
                resizeHandle.repaint();
            }
            
        });

        dragBar.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (closeButtonRect.contains(e.getPoint())) {
                    System.exit(0);
                }
            }
        });

        dragBar.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (closeButtonRect.contains(e.getPoint())) {
                    dragBar.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                } else {
                    dragBar.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }
        });

        // Añadir el resize handle encima de todo en la esquina inferior derecha
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeHandle.setBounds(
                    getWidth() - 16,
                    getHeight() - 16,
                    16, 16
                );
                resizeHandleRect.setBounds(
                    getWidth() - 16,
                    getHeight() - 16,
                    16, 16
                );
            }
        });

        // Añadirlo al JFrame directamente con posición absoluta
        getLayeredPane().add(resizeHandle, JLayeredPane.DRAG_LAYER);
        getLayeredPane().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                resizeHandle.setBounds(
                    getLayeredPane().getWidth() - 16,
                    getLayeredPane().getHeight() - 16,
                    16, 16
                );
            }
        });

        MouseAdapter resizeAdapter = new MouseAdapter() {
            private Point dragStart;
            private Dimension startSize;

            @Override
            public void mouseEntered(MouseEvent e) {
                setCursor(new Cursor(Cursor.SE_RESIZE_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = e.getLocationOnScreen();
                startSize = getSize();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart == null) return;
                Point current = e.getLocationOnScreen();
                int newWidth  = Math.max(200, startSize.width  + current.x - dragStart.x);
                int newHeight = Math.max(150, startSize.height + current.y - dragStart.y);
                setSize(newWidth, newHeight);
                revalidate();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragStart = null;
            }
        };

        resizeHandle.addMouseListener(resizeAdapter);
        resizeHandle.addMouseMotionListener(resizeAdapter);
    }

    /**
     * Llamar desde Main.java justo después de setVisible(true).
     * Inicializa el click-through y el icono de bandeja.
     */
    public void initNativeFeatures() {
        // Esperar a que la ventana esté completamente inicializada
        SwingUtilities.invokeLater(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {}

            try {
                clickThrough = new WindowClickThrough(this);
                clickThrough.setClickThrough(isLocked);
                clickThrough.setExcludeFromCapture(true);
            } catch (Exception e) {
                System.err.println("[Overlay] Funciones nativas no disponibles: " + e.getMessage());
            }
        });

        if (SystemTray.isSupported()) {
            setupTrayIcon();
        }
    }

    /* 
    private void setupTrayIcon() {
        SystemTray tray = SystemTray.getSystemTray();

        // Icono simple morado — reemplaza con una imagen real si quieres
        BufferedImage iconImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = iconImg.createGraphics();
        g.setColor(new Color(100, 60, 200));
        g.fillOval(0, 0, 16, 16);
        g.dispose();

        PopupMenu popup = new PopupMenu();

        MenuItem toggleItem = new MenuItem(
                isLocked ? "Desbloquear (recibir clicks)" : "Bloquear (pasar clicks)");
        toggleItem.addActionListener(e -> {
            isLocked = !isLocked;
            clickThrough.setClickThrough(isLocked);
            toggleItem.setLabel(
                    isLocked ? "Desbloquear (recibir clicks)" : "Bloquear (pasar clicks)");
        });

        MenuItem exitItem = new MenuItem("Salir");
        exitItem.addActionListener(e -> System.exit(0));

        popup.add(toggleItem);
        popup.addSeparator();
        popup.add(exitItem);

        trayIcon = new TrayIcon(iconImg, "Chat Overlay", popup);
        trayIcon.setImageAutoSize(true);

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("[Overlay] No se pudo añadir el icono a la bandeja.");
        }
    }
        */

    private void setupTrayIcon() {
        SystemTray tray = SystemTray.getSystemTray();

        BufferedImage iconImg = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = iconImg.createGraphics();
        g.setColor(new Color(100, 60, 200));
        g.fillOval(0, 0, 16, 16);
        g.dispose();

        // En Windows el PopupMenu del TrayIcon a veces falla,
        // es más fiable usar un JPopupMenu propio
        JPopupMenu popup = new JPopupMenu();

        JMenuItem captureItem = new JMenuItem("Permitir captura de pantalla");
        captureItem.addActionListener(e -> {
            boolean currentlyExcluded = true; // podrías guardarlo como campo
            // toggle
            clickThrough.setExcludeFromCapture(!currentlyExcluded);
            captureItem.setText(currentlyExcluded
                ? "Excluir de captura de pantalla"
                : "Permitir captura de pantalla");
        });
        popup.add(captureItem);

        toggleItem = new JMenuItem(
                isLocked ? "Desbloquear (recibir clicks)" : "Bloquear (pasar clicks)");
        toggleItem.addActionListener(e -> {
            isLocked = !isLocked;
            if (clickThrough != null) {
                clickThrough.setClickThrough(isLocked);
            }
            toggleItem.setText(
                    isLocked ? "Desbloquear (recibir clicks)" : "Bloquear (pasar clicks)");
        });

        JMenuItem exitItem = new JMenuItem("Salir");
        exitItem.addActionListener(e -> System.exit(0));

        popup.add(toggleItem);
        popup.addSeparator();
        popup.add(exitItem);

        trayIcon = new TrayIcon(iconImg, "Chat Overlay");
        trayIcon.setImageAutoSize(true);

        // Mostrar el JPopupMenu manualmente al hacer clic derecho
        trayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON3) {
                    showTrayPopup(popup, e.getX(), e.getY());
                }
                if (e.getButton() == MouseEvent.BUTTON1) {
                    // Clic izquierdo: traer ventana al frente y darle foco
                    SwingUtilities.invokeLater(() -> {
                        setVisible(true);
                        toFront();
                        requestFocus();
                    });
                }
            }
        });
        JMenuItem bgItem = new JMenuItem(showBackground ? "Quitar fondo" : "Mostrar fondo");
        bgItem.addActionListener(e -> {
            showBackground = !showBackground;
            bgItem.setText(showBackground ? "Quitar fondo" : "Mostrar fondo");
            // Guardar en config
            try {
                Point loc = getLocation();
                Dimension d = getSize();
                config.savePanel(loc.x, loc.y, d.width, d.height);
            } catch (IOException ex) {
                System.err.println("[Config] Error guardando: " + ex.getMessage());
            }
            repaint();
        });
        popup.add(bgItem);

        try {
            tray.add(trayIcon);
        } catch (AWTException e) {
            System.err.println("[Overlay] No se pudo añadir el icono a la bandeja.");
        }
    }

    private void showTrayPopup(JPopupMenu popup, int x, int y) {
        // Ventana auxiliar invisible para anclar el popup
        JWindow popupWindow = new JWindow();
        popupWindow.setAlwaysOnTop(true);
        popupWindow.setLocation(x, y);
        popupWindow.setVisible(true);
        popupWindow.setSize(1, 1);

        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                popupWindow.dispose();
            }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                popupWindow.dispose();
            }
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
        });

        popup.show(popupWindow, 0, 0);
    }

    private void appendMessage(ChatMessage msg) {
        try {
            // Limpiar mensajes viejos
            if (doc.getDefaultRootElement().getElementCount() > MAX_MESSAGES) {
                Element root  = doc.getDefaultRootElement();
                Element first = root.getElement(0);
                doc.remove(0, first.getEndOffset());
            }

            List<EmoteToken> tokens;
            List<String> badgeUrls = null;

            if (msg.precomputedTokens() != null) {
                // YouTube: tokens ya procesados en el reader
                tokens = msg.precomputedTokens();
            } else {
                // Twitch: procesar emotes y badges aquí
                tokens = twitchEmoteCache.tokenize(msg.emotesHeader(), msg.text());
                tokens = bttvEmoteCache.process(tokens);
                badgeUrls = badgeCache.getBadgeUrls(msg.badgesHeader());
            }

            emoteRenderer.render(doc, msg.platform(), msg.user(), tokens, badgeUrls, msg.userColor(), showBackground);
            textPane.setCaretPosition(doc.getLength());

        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void makeDraggable(JComponent target) {
        final Point[] dragStart = {null};

        MouseAdapter dragAdapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart[0] = e.getPoint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart[0] != null) {
                    Point loc = getLocation();
                    setLocation(
                        loc.x + e.getX() - dragStart[0].x,
                        loc.y + e.getY() - dragStart[0].y
                    );
                }
            }
        };

        target.addMouseListener(dragAdapter);
        target.addMouseMotionListener(dragAdapter);
    }
}