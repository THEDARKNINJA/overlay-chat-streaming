package com.chatoverlaystreaming.overlay;

import com.chatoverlaystreaming.emotes.BTTVEmoteCache;
import com.chatoverlaystreaming.emotes.EmoteRenderer;
import com.chatoverlaystreaming.emotes.TwitchEmoteCache;
import com.chatoverlaystreaming.model.ChatMessage;
import com.chatoverlaystreaming.model.EmoteToken;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.concurrent.BlockingQueue;
import java.util.List;

public class ChatOverlay extends JFrame {

    private final JTextPane textPane;
    private final StyledDocument doc;
    private static final int MAX_MESSAGES = 50;

    // Colores por plataforma
    private static final Color TEXT_COLOR    = new Color(240, 240, 240);

    private final TwitchEmoteCache twitchEmoteCache = new TwitchEmoteCache();
    private final BTTVEmoteCache   bttvEmoteCache;
    private final EmoteRenderer    emoteRenderer = new EmoteRenderer();

    public ChatOverlay(BlockingQueue<ChatMessage> queue, String twitchChannelId, int x_panel, int y_panel, int width_panel, int height_panel) {
        bttvEmoteCache = new BTTVEmoteCache(twitchChannelId);
        // Sin decoración de ventana (sin bordes ni barra de título)
        setUndecorated(true);

        // Transparencia del fondo de la ventana
        setBackground(new Color(0, 0, 0, 0));

        // Siempre por encima
        setAlwaysOnTop(true);

        // Tipo de ventana: utilidad (no aparece en la barra de tareas)
        setType(Window.Type.UTILITY);

        setSize(width_panel, height_panel);
        setLocation(x_panel, y_panel);

        // Panel con fondo semitransparente
        JPanel panel = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(10, 10, 10, 160)); // negro con ~63% opacidad
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.dispose();
            }
        };
        panel.setOpaque(false);

        // Área de texto
        textPane = new JTextPane();
        textPane.setEditable(false);
        textPane.setOpaque(false);
        textPane.setBackground(new Color(0, 0, 0, 0));
        textPane.setForeground(TEXT_COLOR);
        textPane.setFont(new Font("Segoe UI", Font.PLAIN, 13));
        doc = textPane.getStyledDocument();

        JScrollPane scroll = new JScrollPane(textPane);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);

        panel.add(scroll, BorderLayout.CENTER);
        add(panel);

        // Hacer la ventana arrastrable con el ratón
        makeDraggable();

        // Hilo que consume la cola y actualiza la UI
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

    private void appendMessage(ChatMessage msg) {
        try {
            // Limpiar mensajes viejos
            if (doc.getDefaultRootElement().getElementCount() > MAX_MESSAGES) {
                Element root  = doc.getDefaultRootElement();
                Element first = root.getElement(0);
                doc.remove(0, first.getEndOffset());
            }

            List<EmoteToken> tokens;

            if (msg.precomputedTokens() != null) {
                // YouTube: tokens ya procesados en el reader
                tokens = msg.precomputedTokens();
            } else {
                // Twitch: procesar aquí
                tokens = twitchEmoteCache.tokenize(msg.emotesHeader(), msg.text());
                tokens = bttvEmoteCache.process(tokens);
            }

            emoteRenderer.render(doc, msg.platform(), msg.user(), tokens);
            textPane.setCaretPosition(doc.getLength());

        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    private void makeDraggable() {
        final Point[] dragStart = {null};
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) { dragStart[0] = e.getPoint(); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (dragStart[0] != null) {
                    Point loc = getLocation();
                    setLocation(loc.x + e.getX() - dragStart[0].x,
                                loc.y + e.getY() - dragStart[0].y);
                }
            }
        });
    }
}