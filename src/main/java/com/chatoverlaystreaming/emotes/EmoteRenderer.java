package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.List;

public class EmoteRenderer {

    private final ImageCache imageCache = new ImageCache();

    private static final Color TWITCH_COLOR  = new Color(169, 112, 255);
    private static final Color YOUTUBE_COLOR = new Color(255, 80,  80);
    private static final Color USER_COLOR    = new Color(200, 200, 200);
    private static final Color TEXT_COLOR    = new Color(240, 240, 240);
    private static final Font  CHAT_FONT     = new Font("Segoe UI Emoji", Font.PLAIN, 13);

    private final ImageIcon TWITCH_ICON;
    private final ImageIcon YOUTUBE_ICON;

    public EmoteRenderer() {
        TWITCH_ICON  = loadResourceIcon("/icons/twitch.png");
        YOUTUBE_ICON = loadResourceIcon("/icons/youtube.png");
    }

    private ImageIcon loadResourceIcon(String path) {
        try {
            var url = getClass().getResource(path);
            if (url == null) return null;
            BufferedImage img = ImageIO.read(url);
            Image scaled = img.getScaledInstance(16, 16, Image.SCALE_SMOOTH);
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
                       List<EmoteToken> tokens,
                       List<String> badgeUrls) throws BadLocationException {

        // Estilo base compartido
        SimpleAttributeSet baseStyle = new SimpleAttributeSet();
        StyleConstants.setFontFamily(baseStyle, CHAT_FONT.getFamily());
        StyleConstants.setFontSize(baseStyle, CHAT_FONT.getSize());

        // -- Badges de Twitch --
        if (badgeUrls != null && !badgeUrls.isEmpty()) {
            for (String badgeUrl : badgeUrls) {
                ImageIcon badge = imageCache.get(badgeUrl);
                if (badge != null) {
                    insertIcon(doc, badge, "badge", baseStyle);
                    // Pequeño espacio después de cada badge
                    doc.insertString(doc.getLength(), " ", baseStyle);
                }
            }
        }

        // -- Icono de plataforma --
        SimpleAttributeSet platformStyle = new SimpleAttributeSet(baseStyle);
        Color platformColor = "twitch".equals(platform) ? TWITCH_COLOR : YOUTUBE_COLOR;
        StyleConstants.setForeground(platformStyle, platformColor);
        StyleConstants.setBold(platformStyle, true);

        ImageIcon platformIcon = "twitch".equals(platform) ? TWITCH_ICON : YOUTUBE_ICON;
        if (platformIcon != null) {
            insertIcon(doc, platformIcon, platform, platformStyle);
            doc.insertString(doc.getLength(), " ", platformStyle);
        } else {
            // Fallback al texto si no hay icono
            String tag = "twitch".equals(platform) ? "[T] " : "[YT] ";
            doc.insertString(doc.getLength(), tag, platformStyle);
        }

        // -- Nombre de usuario --
        SimpleAttributeSet userStyle = new SimpleAttributeSet(baseStyle);
        StyleConstants.setForeground(userStyle, USER_COLOR);
        StyleConstants.setBold(userStyle, true);
        doc.insertString(doc.getLength(), username + ": ", userStyle);

        // -- Tokens del mensaje --
        SimpleAttributeSet textStyle = new SimpleAttributeSet(baseStyle);
        StyleConstants.setForeground(textStyle, TEXT_COLOR);
        StyleConstants.setBold(textStyle, false);

        for (EmoteToken token : tokens) {
            switch (token) {
                case EmoteToken.Text t -> {
                    doc.insertString(doc.getLength(), t.content(), textStyle);
                }
                case EmoteToken.Emote e -> {
                    ImageIcon icon = imageCache.get(e.url());
                    if (icon != null) {
                        insertIcon(doc, icon, e.name(), textStyle);
                    } else {
                        // Fallback al nombre del emote
                        doc.insertString(doc.getLength(), e.name(), textStyle);
                    }
                }
            }
        }

        doc.insertString(doc.getLength(), "\n", textStyle);
    }

    private void insertIcon(StyledDocument doc, ImageIcon icon,
                            String altText, AttributeSet baseStyle)
            throws BadLocationException {
        Style iconStyle = doc.addStyle(altText + System.nanoTime(), null);
        StyleConstants.setIcon(iconStyle, icon);
        doc.insertString(doc.getLength(), " ", iconStyle);
    }
}