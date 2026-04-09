package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;

public class EmoteRenderer {

    private final ImageCache imageCache = new ImageCache();

    private static final Color TWITCH_COLOR  = new Color(169, 112, 255);
    private static final Color YOUTUBE_COLOR = new Color(255, 80,  80);
    private static final Color USER_COLOR    = new Color(200, 200, 200);
    private static final Color TEXT_COLOR    = new Color(240, 240, 240);
    private static final Font  CHAT_FONT     = new Font("Segoe UI Emoji", Font.PLAIN, 13);

    /**
     * Inserta un mensaje completo (cabecera + tokens) en el documento.
     */
    public void render(StyledDocument doc,
                       String platform,
                       String username,
                       List<EmoteToken> tokens) throws BadLocationException {

        Color platformColor = "twitch".equals(platform) ? TWITCH_COLOR : YOUTUBE_COLOR;
        String tag = "twitch".equals(platform) ? "[T] " : "[YT] ";

        // Tag de plataforma
        SimpleAttributeSet platformStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(platformStyle, platformColor);
        StyleConstants.setBold(platformStyle, true);
        StyleConstants.setFontFamily(platformStyle, CHAT_FONT.getFamily());
        StyleConstants.setFontSize(platformStyle, CHAT_FONT.getSize());
        doc.insertString(doc.getLength(), tag, platformStyle);

        // Nombre de usuario
        SimpleAttributeSet userStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(userStyle, USER_COLOR);
        StyleConstants.setBold(userStyle, true);
        StyleConstants.setFontFamily(userStyle, CHAT_FONT.getFamily());
        StyleConstants.setFontSize(userStyle, CHAT_FONT.getSize());
        doc.insertString(doc.getLength(), username + ": ", userStyle);

        // Tokens del mensaje
        SimpleAttributeSet textStyle = new SimpleAttributeSet();
        StyleConstants.setForeground(textStyle, TEXT_COLOR);
        StyleConstants.setBold(textStyle, false);
        StyleConstants.setFontFamily(textStyle, CHAT_FONT.getFamily());
        StyleConstants.setFontSize(textStyle, CHAT_FONT.getSize());

        for (EmoteToken token : tokens) {
            switch (token) {
                case EmoteToken.Text t -> {
                    doc.insertString(doc.getLength(), t.content(), textStyle);
                }
                case EmoteToken.Emote e -> {
                    ImageIcon icon = imageCache.get(e.url());
                    if (icon != null) {
                        // Insertar imagen inline
                        insertIcon(doc, icon, e.name(), textStyle);
                    } else {
                        // Fallback al nombre del emote si la imagen no cargó
                        doc.insertString(doc.getLength(), e.name(), textStyle);
                    }
                }
            }
        }

        doc.insertString(doc.getLength(), "\n", textStyle);
    }

    private void insertIcon(StyledDocument doc, ImageIcon icon,
                            String altText, AttributeSet baseStyle) throws BadLocationException {
        // JTextPane usa un StyleConstants especial para insertar componentes/iconos
        Style iconStyle = doc.addStyle(altText + System.nanoTime(), null);
        StyleConstants.setIcon(iconStyle, icon);

        // El icono ocupa un carácter especial (U+FFFC object replacement character)
        doc.insertString(doc.getLength(), " ", iconStyle);
        // Espacio después del emote
        doc.insertString(doc.getLength(), " ", baseStyle);
    }
}