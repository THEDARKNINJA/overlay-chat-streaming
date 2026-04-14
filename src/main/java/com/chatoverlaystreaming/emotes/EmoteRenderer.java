package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import com.chatoverlaystreaming.readers.TwitchEventSub;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EmoteRenderer {

    private final ImageCache imageCache;

    /*
    private static final Color TWITCH_COLOR  = new Color(169, 112, 255);
    private static final Color YOUTUBE_COLOR = new Color(255, 80,  80);
    private static final Color USER_COLOR    = new Color(200, 200, 200);
    private static final Color TEXT_COLOR    = new Color(240, 240, 240); 
     */
    private static final Color TWITCH_COLOR  = new Color(200, 140, 255); // morado más vivo
    private static final Color YOUTUBE_COLOR = new Color(255, 80,  80);  // rojo ya está bastante vivo
    private static final Color USER_COLOR    = new Color(255, 255, 255); // blanco puro
    private static final Color TEXT_COLOR    = new Color(255, 255, 255); // blanco puro
    private static final Color REWARD_COLOR  = new Color(255, 180,  50); // dorado
    private static final Color SUBGIFT_COLOR = new Color( 50, 220, 120); // verde
    private static final Color CHEER_COLOR   = new Color(150, 100, 255); // morado claro
    private static final Font  CHAT_FONT     = new Font("Segoe UI Emoji", Font.PLAIN, 15);
    private static final int LINE_HEIGHT = 14; // altura de línea

    private final ImageIcon TWITCH_ICON;
    private final ImageIcon YOUTUBE_ICON;
    private final int iconSize;
    private final JTextPane textPane;
    private static final Pattern URL_PATTERN = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    private TwitchEventSub eventSub = null;


    public EmoteRenderer(int iconSize, JTextPane textPane, ImageCache sharedImageCache) {
        this.textPane = textPane;
        this.imageCache = sharedImageCache;
        this.iconSize = iconSize;
        //this.imageCache = new ImageCache(iconSize);
        TWITCH_ICON  = loadResourceIcon("/icons/twitch.png");
        YOUTUBE_ICON = loadResourceIcon("/icons/youtube.png");
    }

    private ImageIcon loadResourceIcon(String path) {
        try {
            var url = getClass().getResource(path);
            if (url == null) return null;
            BufferedImage img = ImageIO.read(url);
            int scaledWidth = (int) ((double) img.getWidth() / img.getHeight() * iconSize);
            Image scaled = img.getScaledInstance(scaledWidth, iconSize, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            System.err.println("[EmoteRenderer] No se pudo cargar icono: " + path);
            return null;
        }
    }

    /**
     * Inserta un mensaje completo en el documento con badges, icono de
     * plataforma, nombre de usuario y tokens de texto/emotes.
     */
    public void render(StyledDocument doc,
                   String platform,
                   String username,
                   String text,
                   List<EmoteToken> tokens,
                   List<String> badgeUrls,
                   String userColor,
                   String eventType,
                   String eventExtra,
                   boolean showBackground) throws BadLocationException {

        SimpleAttributeSet baseStyle = new SimpleAttributeSet();
        StyleConstants.setFontFamily(baseStyle, CHAT_FONT.getFamily());
        StyleConstants.setFontSize(baseStyle, CHAT_FONT.getSize());

        if (eventType != null) {
            switch (eventType) {
                case "reward" -> {
                    // eventExtra tiene formato "titulo|rewardId|redemptionId"
                    // o solo "titulo" si viene del IRC (mensajes destacados, recompensas sin EventSub)
                    String[] parts = eventExtra != null ? eventExtra.split("\\|", 3) : new String[0];
                    String rewardTitle  = parts.length > 0 ? parts[0] : "";
                    String rewardId     = parts.length > 1 ? parts[1] : null;
                    String redemptionId = parts.length > 2 ? parts[2] : null;

                    if ("Mensaje destacado".equals(rewardTitle)) {
                        SimpleAttributeSet highlightStyle = new SimpleAttributeSet(baseStyle);
                        StyleConstants.setUnderline(highlightStyle, true);
                        renderMessageBody(doc, platform, username, tokens,
                                badgeUrls, userColor, highlightStyle);
                        SimpleAttributeSet tagStyle = new SimpleAttributeSet(baseStyle);
                        StyleConstants.setForeground(tagStyle, REWARD_COLOR);
                        StyleConstants.setBold(tagStyle, true);
                        doc.insertString(doc.getLength(), " — ★ Mensaje destacado\n", tagStyle);

                    } else {
                        SimpleAttributeSet eventStyle = new SimpleAttributeSet(baseStyle);
                        StyleConstants.setForeground(eventStyle, REWARD_COLOR);
                        StyleConstants.setBold(eventStyle, true);

                        if (text == null || text.isBlank()) {
                            doc.insertString(doc.getLength(),
                                    //"★ RECOMPENSA — " + rewardTitle + " — " + username, eventStyle);
                                    "★ " + username + " CANJEÓ: — " + rewardTitle + "\n", eventStyle);
                        } else {
                            doc.insertString(doc.getLength(),
                                    "★ RECOMPENSA CANJEADA: — " + rewardTitle + "\n", eventStyle);
                            renderMessageBody(doc, platform, username, tokens,
                                    badgeUrls, userColor, baseStyle);
                        }

                        // Botones solo si tenemos los IDs y es una recompensa de la app (viene de EventSub)
                        /*
                        if (rewardId != null && redemptionId != null && eventSub != null && eventSub.isRewardOwnedByApp(rewardId)) {
                            insertRewardButtons(doc, rewardId, redemptionId, eventSub);
                        }
                             */

                        doc.insertString(doc.getLength(), "\n", baseStyle);
                    }
                    return;
                }
                case "subgift" -> {
                    SimpleAttributeSet eventStyle = new SimpleAttributeSet(baseStyle);
                    StyleConstants.setForeground(eventStyle, SUBGIFT_COLOR);
                    StyleConstants.setBold(eventStyle, true);
                    doc.insertString(doc.getLength(),
                            "♥ GIFT — " + username + " regaló " + eventExtra + "\n", eventStyle);
                    return;
                }
                case "cheer" -> {
                    SimpleAttributeSet eventStyle = new SimpleAttributeSet(baseStyle);
                    StyleConstants.setForeground(eventStyle, CHEER_COLOR);
                    StyleConstants.setBold(eventStyle, true);
                    doc.insertString(doc.getLength(),
                            "✦ " + eventExtra + " BITS — ", eventStyle);
                    renderMessageBody(doc, platform, username, tokens,
                                    badgeUrls, userColor, baseStyle);
                    doc.insertString(doc.getLength(), "\n", baseStyle);
                    return;
                }
            }
        }

        // Mensaje normal
        renderMessageBody(doc, platform, username, tokens, badgeUrls, userColor, baseStyle);
        doc.insertString(doc.getLength(), "\n", baseStyle);
    }

    private void renderMessageBody(StyledDocument doc,
                                String platform,
                                String username,
                                List<EmoteToken> tokens,
                                List<String> badgeUrls,
                                String userColor,
                                AttributeSet overrideStyle) throws BadLocationException {

        SimpleAttributeSet platformStyle = new SimpleAttributeSet(overrideStyle);
        Color platformColor = "twitch".equals(platform) ? TWITCH_COLOR : YOUTUBE_COLOR;
        StyleConstants.setForeground(platformStyle, platformColor);
        StyleConstants.setBold(platformStyle, true);

        ImageIcon platformIcon = "twitch".equals(platform) ? TWITCH_ICON : YOUTUBE_ICON;
        if (platformIcon != null) {
            insertIcon(doc, platformIcon, platform, platformStyle);
            doc.insertString(doc.getLength(), " ", platformStyle);
        } else {
            doc.insertString(doc.getLength(), "twitch".equals(platform) ? "[T] " : "[YT] ", platformStyle);
        }

        if (badgeUrls != null && !badgeUrls.isEmpty()) {
            for (String badgeUrl : badgeUrls) {
                ImageIcon badge = imageCache.get(badgeUrl);
                if (badge != null) {
                    insertIcon(doc, badge, "badge", overrideStyle);
                    doc.insertString(doc.getLength(), " ", overrideStyle);
                }
            }
        }

        Color nameColor = resolveUserColor(userColor, username);
        SimpleAttributeSet userStyle = new SimpleAttributeSet(overrideStyle);
        StyleConstants.setForeground(userStyle, nameColor);
        StyleConstants.setBold(userStyle, true);
        doc.insertString(doc.getLength(), username + ": ", userStyle);

        SimpleAttributeSet textStyle = new SimpleAttributeSet(overrideStyle);
        StyleConstants.setForeground(textStyle, TEXT_COLOR);
        StyleConstants.setBold(textStyle, false);
        for (EmoteToken token : tokens) {
            
            switch (token) {
                case EmoteToken.Text t -> {
                    //doc.insertString(doc.getLength(), t.content(), textStyle);
                    String content = t.content();
                    Matcher m = URL_PATTERN.matcher(content);

                    int last = 0;
                    while (m.find()) {
                        if (m.start() > last) {
                            doc.insertString(doc.getLength(),
                                    content.substring(last, m.start()), textStyle);
                        }

                        String url = m.group();
                        String shortUrl = shortenUrl(url, 40);

                        insertClickableUrl(doc, shortUrl, url, textStyle);

                        last = m.end();
                    }

                    if (last < content.length()) {
                        doc.insertString(doc.getLength(),
                                content.substring(last), textStyle);
                    }
                }
                case EmoteToken.Emote e -> {
                    ImageIcon icon = imageCache.get(e.url());
                    if (icon != null) {
                        insertIcon(doc, icon, e.name(), textStyle);
                    } else {
                        doc.insertString(doc.getLength(), e.name(), textStyle);
                    }
                    /* 
                    ImageIcon icon;
                    if (e.url() != null && e.url().startsWith("local:")) {
                        // Emoji local de YouTube
                        // icon = youtubeEmojiCache.getLocalIcon(e.name());
                            String filename = e.name().replace(":", "");
                            icon = imageCache.get(filename);
                    } else {
                        icon = imageCache.get(e.url());
                    }
                    if (icon != null) {
                        insertIcon(doc, icon, e.name(), textStyle);
                    } else {
                        doc.insertString(doc.getLength(), e.name(), textStyle);
                    }
                        */
                }
            }
        }
    }

    private Color resolveUserColor(String hexColor, String username) {
        if (hexColor != null && hexColor.startsWith("#") && hexColor.length() == 7) {
            try {
                return Color.decode(hexColor);
            } catch (NumberFormatException ignored) {}
        }
        // Color generado a partir del nombre, siempre el mismo para el mismo usuario
        return generateColorFromName(username);
    }

    private Color generateColorFromName(String name) {
        // Paleta de colores legibles sobre fondo oscuro
        Color[] palette = {
            new Color(255, 100, 100),  // rojo suave
            new Color(100, 200, 255),  // azul claro
            new Color(100, 255, 150),  // verde claro
            new Color(255, 200,  80),  // amarillo
            new Color(200, 130, 255),  // morado
            new Color(255, 150,  80),  // naranja
            new Color( 80, 220, 200),  // turquesa
            new Color(255, 120, 180),  // rosa
        };
        int index = Math.abs(name.hashCode()) % palette.length;
        return palette[index];
    }

    private void insertIcon(StyledDocument doc, ImageIcon icon,
                            String altText, AttributeSet baseStyle)
            throws BadLocationException {
        CenteredIcon centered = new CenteredIcon(icon, LINE_HEIGHT);
        Style iconStyle = doc.addStyle(altText + System.nanoTime(), null);
        StyleConstants.setIcon(iconStyle, centered);
        doc.insertString(doc.getLength(), " ", iconStyle);
    }
    

    public ImageCache getImageCache() {
        return imageCache;
    }

    public static String shortenUrl(String url, int maxLen) {
        if (url.length() <= maxLen) return url;

        int keep = (maxLen - 1) / 2;
        String start = url.substring(0, keep);
        String end   = url.substring(url.length() - keep);

        return start + "…" + end;
    }

    private void insertClickableUrl(StyledDocument doc,
                                String visible,
                                String fullUrl,
                                AttributeSet baseStyle)
        throws BadLocationException {

        SimpleAttributeSet linkStyle = new SimpleAttributeSet(baseStyle);
        StyleConstants.setForeground(linkStyle, new Color(120, 180, 255));
        StyleConstants.setUnderline(linkStyle, true);

        // Marcamos el enlace para que ChatOverlay lo detecte
        linkStyle.addAttribute("link", fullUrl);

        doc.insertString(doc.getLength(), visible, linkStyle);
    }

    private void insertRewardButtons(StyledDocument doc,
                                  String rewardId,
                                  String redemptionId,
                                  TwitchEventSub eventSub)
        throws BadLocationException {

        JButton[] buttons = new JButton[2];

        // Botón OK
        buttons[0] = new JButton("✔");
        buttons[0].setFont(new Font("Segoe UI Emoji", Font.PLAIN, 11));
        buttons[0].setForeground(new Color(50, 220, 120));
        buttons[0].setBackground(new Color(30, 30, 35));
        buttons[0].setBorder(BorderFactory.createLineBorder(new Color(50, 220, 120), 1));
        buttons[0].setFocusPainted(false);
        buttons[0].setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        buttons[0].setMargin(new Insets(1, 4, 1, 4));

        // Botón DEVOLVER
        buttons[1] = new JButton("✖");
        buttons[1].setFont(new Font("Segoe UI Emoji", Font.PLAIN, 11));
        buttons[1].setForeground(new Color(255, 80, 80));
        buttons[1].setBackground(new Color(30, 30, 35));
        buttons[1].setBorder(BorderFactory.createLineBorder(new Color(255, 80, 80), 1));
        buttons[1].setFocusPainted(false);
        buttons[1].setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        buttons[1].setMargin(new Insets(1, 4, 1, 4));

        buttons[0].addActionListener(e -> {
            eventSub.updateRedemption(rewardId, redemptionId, true);
            buttons[0].setEnabled(false);
            buttons[1].setEnabled(false);
        });

        buttons[1].addActionListener(e -> {
            eventSub.updateRedemption(rewardId, redemptionId, false);
            buttons[0].setEnabled(false);
            buttons[1].setEnabled(false);
        });

        for (JButton btn : buttons) {
            Style style = doc.addStyle(btn.getText() + "-" + redemptionId + System.nanoTime(), null);
            StyleConstants.setComponent(style, btn);
            doc.insertString(doc.getLength(), " ", style);
        }
    }

    public void setEventSub(TwitchEventSub eventSub) {
        this.eventSub = eventSub;
    }

}