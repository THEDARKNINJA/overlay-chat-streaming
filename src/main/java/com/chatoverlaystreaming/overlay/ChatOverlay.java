package com.chatoverlaystreaming.overlay;

import com.chatoverlaystreaming.emotes.*;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.model.EmoteToken;
import com.chatoverlaystreaming.readers.TwitchEventSub;
import com.chatoverlaystreaming.service.ViewerCountService;

import javax.swing.*;
import javax.swing.text.*;
import javax.swing.event.PopupMenuListener;
import javax.swing.event.PopupMenuEvent;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public class ChatOverlay extends JFrame {

    private final JTextPane textPane;
    private final StyledDocument doc;
    private static final int MAX_MESSAGES = 100;

    private final TwitchEmoteCache  twitchEmoteCache = new TwitchEmoteCache();
    private final BTTVEmoteCache    bttvEmoteCache;
    private final TwitchBadgeCache  badgeCache;
    private final EmoteRenderer     emoteRenderer;
    // messageId -> [startOffset, endOffset]
    private final Map<String, int[]> messageOffsets = new LinkedHashMap<>();
    // usuario -> lista de messageIds
    private final Map<String, List<String>> userMessages = new LinkedHashMap<>();
    private WindowClickThrough clickThrough;
    private TrayIcon trayIcon;
    private JMenuItem toggleItem;
    private JButton rewardsButton;
    private JPanel dragBar;
    private Rectangle closeButtonRect = new Rectangle();
    private Rectangle resizeHandleRect = new Rectangle();
    private boolean showBackground;
    private boolean canClickLink;
    private boolean isLocked = true;
    private boolean hasFocus = false;
    private String twitchViewers  = "?";
    private String youtubeViewers = "?";
    private JPanel viewerPanel;
    private ImageIcon twitchIcon;
    private ImageIcon youtubeIcon;

    private final Config config;
    private javax.swing.Timer saveTimer;
    private TwitchEventSub eventSub;

    public ChatOverlay(BlockingQueue<ChatMessage> queue,
                       String twitchChannelId,
                       String twitchClientId,
                       String twitchClientSecret, 
                       Config config, ImageCache sharedImageCache) {

        this.config = config;
        canClickLink = config.getCanClickLink();
        textPane = new JTextPane();
        rewardsButton = buildRewardsButton();

        twitchIcon  = loadIcon("/icons/twitch.png", 14);
        youtubeIcon = loadIcon("/icons/youtube.png", 14);
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setUndecorated(true);
        //setBackground(new Color(0, 0, 0, 0));
        setBackground(Color.MAGENTA);
        setAlwaysOnTop(true);
        setType(Window.Type.NORMAL);
        setSize(config.getPanelWidth(), config.getPanelHeight());
        setLocation(config.getPanelX(), config.getPanelY());
        this.showBackground = config.getShowBackground();
        emoteRenderer    = new EmoteRenderer(config.getIconSize(), textPane, sharedImageCache);
        
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
        
        viewerPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                if (!config.getShowViewerCount()) return;

                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                Font font = new Font("Segoe UI", Font.BOLD, 12);
                g2.setFont(font);
                FontMetrics fm = g2.getFontMetrics();

                int x = 6;
                int y = getHeight() / 2;

                // Icono Twitch
                if (twitchIcon != null) {
                    twitchIcon.paintIcon(this, g2, x, y - 7);
                } else {
                    g2.setColor(new Color(100, 60, 200, 200));
                    g2.fillRoundRect(x, y - 7, 14, 14, 4, 4);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 8));
                    g2.drawString("T", x + 4, y + 3);
                }

                // Número Twitch
                g2.setFont(font);
                g2.setColor(new Color(220, 200, 255));
                x += 18;
                g2.drawString(twitchViewers, x, y + fm.getAscent() / 2);
                x += fm.stringWidth(twitchViewers) + 10;

                // Icono YouTube
                if (youtubeIcon != null) {
                    youtubeIcon.paintIcon(this, g2, x, y - 7);
                } else {
                    g2.setColor(new Color(200, 40, 40, 200));
                    g2.fillRoundRect(x, y - 7, 14, 14, 4, 4);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Segoe UI", Font.BOLD, 8));
                    g2.drawString("Y", x + 4, y + 3);
                }

                // Número YouTube
                g2.setFont(font);
                g2.setColor(new Color(255, 150, 150));
                x += 18;
                g2.drawString(youtubeViewers, x, y + fm.getAscent() / 2);

                g2.dispose();
            }
        };
        viewerPanel.setOpaque(false);
        viewerPanel.setPreferredSize(new Dimension(80, 20));

        JPanel rewardsWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        rewardsWrapper.setBackground(new Color(14, 14, 16));
        rewardsWrapper.add(rewardsButton);
        //panel.add(rewardsWrapper, BorderLayout.SOUTH);

        // Añade panel inferior para agregar espectadores y botón recompensas y que ambos se vean
        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);
        bottomBar.add(viewerPanel, BorderLayout.WEST);
        bottomBar.add(rewardsWrapper, BorderLayout.CENTER);

        panel.add(bottomBar, BorderLayout.SOUTH);
        //panel.add(viewerPanel, BorderLayout.SOUTH);

        // Área de texto
        textPane.setEditable(false);
        textPane.setEditorKit(new WrapEditorKitPro()); // la clase para poder editar las palabras demasiado largas
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

        //Si puede clickar enlaces,  define los eventos
        if(canClickLink){
            textPane.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    try {
                        int pos = textPane.viewToModel2D(e.getPoint());
                        if (pos < 0) return;

                        StyledDocument doc = textPane.getStyledDocument();
                        Element elem = doc.getCharacterElement(pos);

                        String url = (String) elem.getAttributes().getAttribute("link");
                        if (url != null) {
                            Desktop.getDesktop().browse(new URI(url));
                        }

                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            });

            textPane.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    try {
                        int pos = textPane.viewToModel2D(e.getPoint());
                        if (pos < 0) {
                            textPane.setToolTipText(null);
                            return;
                        }

                        StyledDocument doc = textPane.getStyledDocument();
                        Element elem = doc.getCharacterElement(pos);

                        String url = (String) elem.getAttributes().getAttribute("link");
                        textPane.setToolTipText(url);

                    } catch (Exception ex) {
                        textPane.setToolTipText(null);
                    }
                }
            });
        }

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

        //Inicializar BTTV Emotes y Twitch Badges
        bttvEmoteCache = new BTTVEmoteCache(twitchChannelId);
        badgeCache     = new TwitchBadgeCache(twitchClientId,
                                              twitchClientSecret,
                                              twitchChannelId);

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
                clickThrough.setExcludeFromCapture(true, config.getPanelAlpha());
            } catch (Exception e) {
                System.err.println("[Overlay] Funciones nativas no disponibles: " + e.getMessage());
            }
        });

        /*
        if (SystemTray.isSupported()) {
            setupTrayIcon();
        } */

        if (config.getShowViewerCount()) {
            ViewerCountService viewerService = new ViewerCountService(
                config,
                count -> SwingUtilities.invokeLater(() -> {
                    twitchViewers = count;
                    viewerPanel.repaint();
                }),
                count -> SwingUtilities.invokeLater(() -> {
                    youtubeViewers = count;
                    viewerPanel.repaint();
                })
            );
            viewerService.start();
        }

        // Visibilidad botón recompensas con/sin focus
        addWindowFocusListener(new java.awt.event.WindowFocusListener() {
            @Override
            public void windowGainedFocus(java.awt.event.WindowEvent e) {
                rewardsButton.setVisible(true);
            }

            @Override
            public void windowLostFocus(java.awt.event.WindowEvent e) {
                rewardsButton.setVisible(false);
            }
        });
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
            clickThrough.setExcludeFromCapture(!currentlyExcluded, config.getPanelAlpha());
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
        */

    private void appendMessage(ChatMessage msg) {
        try {
            // Eventos de moderación
            if ("clearchat".equals(msg.eventType())) {
                clearUserMessages(msg.targetUser());
                return;
            }
            if ("clearall".equals(msg.eventType())) {
                clearAllMessages();
                return;
            }
            if ("clearmsg".equals(msg.eventType())) {
                clearSingleMessage(msg.messageId());
                return;
            }

            // eventos recompensa de activación (Bobobo like)
            if ("reward".equals(msg.eventType()) && msg.eventExtra() != null) {
                String[] parts = msg.eventExtra().split("\\|", 3);
                String rewardId = parts.length > 1 ? parts[1] : null;
                if (rewardId != null) {
                    String type   = config.getRewardType(rewardId);
                    String folder = config.getRewardFolder(rewardId);
                    if (type != null && folder != null) {
                        RewardMediaPlayer.play(type, folder);
                    }
                }
            }

            // Limpiar mensajes viejos
            if (doc.getDefaultRootElement().getElementCount() > MAX_MESSAGES) {
                Element root  = doc.getDefaultRootElement();
                Element first = root.getElement(0);
                try {
                    String removedText = doc.getText(first.getStartOffset(),
                            first.getEndOffset() - first.getStartOffset());
                    System.err.println("[Chat] Eliminando mensaje antiguo: " +
                            removedText.trim().substring(0, Math.min(50, removedText.trim().length())));
                } catch (Exception ignored) {}
                doc.remove(0, first.getEndOffset());
            }

            // Guardar offset de inicio antes de insertar
            int startOffset = doc.getLength();

            List<EmoteToken> tokens;
            List<String> badgeUrls = null;

            if (msg.precomputedTokens() != null) {
                tokens = msg.precomputedTokens();
            } else {
                tokens = twitchEmoteCache.tokenize(msg.emotesHeader(), msg.text());
                tokens = bttvEmoteCache.process(tokens);
                badgeUrls = badgeCache.getBadgeUrls(msg.badgesHeader());
            }

            emoteRenderer.render(doc, msg.platform(), msg.user(), msg.text(),
                    tokens, badgeUrls, msg.userColor(),
                    msg.eventType(), msg.eventExtra(), showBackground);

            // Guardar offset de fin y asociar al messageId y usuario
            int endOffset = doc.getLength();
            if (msg.messageId() != null) {
                messageOffsets.put(msg.messageId(), new int[]{startOffset, endOffset});
                System.err.println("[Chat] Guardado mensaje id=" + msg.messageId() + 
                       " offsets=[" + startOffset + "," + endOffset + "]");
            }
            if (msg.user() != null && msg.messageId() != null) {
                userMessages.computeIfAbsent(msg.user().toLowerCase(), k -> new java.util.ArrayList<>())
                        .add(msg.messageId());
            }

            textPane.setCaretPosition(doc.getLength());

        } catch (Exception e) {
            System.err.println("[Overlay] Error añadiendo mensaje: " + e.getMessage());
        }
    }

    private void clearSingleMessage(String messageId) {
        System.err.println("[Chat] Intentando borrar messageId=" + messageId);
System.err.println("[Chat] Offsets conocidos: " + messageOffsets.keySet());
        if (messageId == null || !messageOffsets.containsKey(messageId)) return;
        try {
            int[] offsets = messageOffsets.get(messageId);
            int start = offsets[0];
            int end   = offsets[1];
            int length = end - start;

            if (start < doc.getLength() && length > 0) {
                // Reemplazar el mensaje con un texto tachado en gris
                SimpleAttributeSet strikeStyle = new SimpleAttributeSet();
                StyleConstants.setStrikeThrough(strikeStyle, true);
                StyleConstants.setForeground(strikeStyle, new Color(120, 120, 120));
                StyleConstants.setFontFamily(strikeStyle, new Font("Segoe UI Emoji", Font.PLAIN, 13).getFamily());
                StyleConstants.setFontSize(strikeStyle, 13);
                doc.remove(start, length);
                doc.insertString(start, "<mensaje eliminado>\n", strikeStyle);
            }

            messageOffsets.remove(messageId);
        } catch (Exception e) {
            System.err.println("[Overlay] Error borrando mensaje: " + e.getMessage());
        }
    }

    private void clearUserMessages(String username) {
        if (username == null) return;
        List<String> ids = userMessages.get(username.toLowerCase());
        if (ids == null) return;
        // Copiar la lista para evitar ConcurrentModificationException
        new java.util.ArrayList<>(ids).forEach(this::clearSingleMessage);
        userMessages.remove(username.toLowerCase());
    }

    private void clearAllMessages() {
        try {
            doc.remove(0, doc.getLength());
            messageOffsets.clear();
            userMessages.clear();
        } catch (Exception e) {
            System.err.println("[Overlay] Error limpiando chat: " + e.getMessage());
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
    private ImageIcon loadIcon(String path, int size) {
        try {
            var url = getClass().getResource(path);
            if (url == null) return null;
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(url);
            java.awt.Image scaled = img.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            System.err.println("[Overlay] No se pudo cargar icono: " + path);
            return null;
        }
    }
    public void setEventSub(TwitchEventSub eventSub) {
        this.eventSub = eventSub;
        emoteRenderer.setEventSub(eventSub);
    }
    
    // En buildUI() o donde construyes los controles, añadir el botón:
    private JButton buildRewardsButton() {
        JButton btn = new JButton();
        try {
            /*
            // Para poner un icono personalizado, pero javax.imageio.ImageIO da problemas
            var url = getClass().getResource("/icons/rewards.png");
            if (url != null) {
                ImageIcon icon = new ImageIcon(
                        new javax.imageio.ImageIO.read(url)
                                .getScaledInstance(18, 18, Image.SCALE_SMOOTH));
                btn.setIcon(icon);
            } else {
                btn.setText("★");
            }
                 */
            btn.setText("★");
        } catch (Exception e) {
            btn.setText("★");
        }
        btn.setForeground(new Color(255, 200, 50));  // amarillo
        btn.setBackground(new Color(24, 24, 28));
        // btn.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        Dimension d = btn.getPreferredSize();
        btn.setPreferredSize(new Dimension(d.width + 2, d.height));
        btn.setBorder(BorderFactory.createLineBorder(
            new Color(255, 200, 50), 1));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("Gestionar recompensas");
        btn.setVisible(false); // oculto por defecto
        btn.addActionListener(e -> {
            if (eventSub == null) {
                JOptionPane.showMessageDialog(this,
                        "El EventSub no está disponible.\n" +
                        "Comprueba que el OAuth está configurado.",
                        "Sin conexión", JOptionPane.WARNING_MESSAGE);
                return;
            }
            new RewardsPanel(this, eventSub, config).setVisible(true);
        });
        return btn;
    }
}