package com.chatoverlaystreaming.emotes;

import com.chatoverlaystreaming.model.EmoteToken;
import com.chatoverlaystreaming.readers.TwitchEventSub;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renderiza mensajes de chat en un StyledDocument de un JTextPane.
 *
 * Inserta cada mensaje como una secuencia de elementos estilizados:
 * icono de plataforma → badges → nombre de usuario → tokens de texto/emotes.
 * Los eventos especiales (recompensas, cheers, subgifts) tienen un formato propio.
 *
 * Diseño:
 *   - {@link #render} es el punto de entrada público. Determina el tipo de
 *     mensaje y delega en {@link #renderMessageBody} para los mensajes normales.
 *   - Los emotes se insertan como {@link CenteredIcon} dentro del documento.
 *   - Las URLs en el texto se detectan con regex y se marcan con el atributo
 *     "link" para que ChatOverlay las abra al hacer clic.
 *   - Los colores de usuario sin color definido se generan determinísticamente
 *     a partir del hash del nombre, garantizando consistencia entre mensajes.
 */
public class EmoteRenderer {

    // ── Constantes de estilo ──────────────────────────────────────────────────

    private static final Color TWITCH_COLOR  = new Color(200, 140, 255);
    private static final Color YOUTUBE_COLOR = new Color(255,  80,  80);
    private static final Color TEXT_COLOR    = new Color(255, 255, 255);
    private static final Color REWARD_COLOR  = new Color(255, 180,  50);
    private static final Color SUBGIFT_COLOR = new Color( 50, 220, 120);
    private static final Color CHEER_COLOR   = new Color(150, 100, 255);
    private static final Color LINK_COLOR    = new Color(120, 180, 255);

    private static final Font CHAT_FONT   = new Font("Segoe UI Emoji", Font.PLAIN, 15);
    private static final int  LINE_HEIGHT = 14;

    /** Regex para detectar URLs en el texto de los mensajes. */
    private static final Pattern URL_PATTERN =
            Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    /**
     * Paleta de colores para usuarios sin color definido en Twitch.
     * El color se elige deterministamente por hash del nombre para que sea
     * siempre el mismo para el mismo usuario en toda la sesión.
     */
    private static final Color[] NAME_COLOR_PALETTE = {
        new Color(255, 100, 100),  // rojo suave
        new Color(100, 200, 255),  // azul claro
        new Color(100, 255, 150),  // verde claro
        new Color(255, 200,  80),  // amarillo
        new Color(200, 130, 255),  // morado
        new Color(255, 150,  80),  // naranja
        new Color( 80, 220, 200),  // turquesa
        new Color(255, 120, 180),  // rosa
    };

    // ── Dependencias ──────────────────────────────────────────────────────────

    private final ImageCache  imageCache;
    private final JTextPane   textPane;
    private final int         iconSize;
    private final ImageIcon   TWITCH_ICON;
    private final ImageIcon   YOUTUBE_ICON;

    /** EventSub inyectado desde Main cuando el OAuth está activo. Puede ser null. */
    private TwitchEventSub eventSub;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param iconSize        Tamaño en píxeles de los iconos de plataforma y badges.
     * @param textPane        JTextPane donde se insertan los mensajes.
     * @param sharedImageCache Cache compartido con los readers para emotes e imágenes.
     */
    public EmoteRenderer(int iconSize, JTextPane textPane, ImageCache sharedImageCache) {
        this.iconSize   = iconSize;
        this.textPane   = textPane;
        this.imageCache = sharedImageCache;
        TWITCH_ICON     = loadResourceIcon("/icons/twitch.png");
        YOUTUBE_ICON    = loadResourceIcon("/icons/youtube.png");
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Renderiza un mensaje completo en el documento.
     *
     * Para eventos especiales (reward, subgift, cheer) usa formato propio.
     * Para mensajes normales delega en {@link #renderMessageBody}.
     *
     * @param doc          Documento donde insertar el mensaje.
     * @param platform     "twitch" o "youtube".
     * @param username     Nombre del usuario.
     * @param text         Texto plano del mensaje (puede ser null en eventos).
     * @param tokens       Tokens de emotes ya procesados.
     * @param badgeUrls    URLs de las imágenes de badges (puede ser null).
     * @param userColor    Color del nombre en "#RRGGBB", o null para generarlo.
     * @param eventType    Tipo de evento o null para mensaje normal.
     * @param eventExtra   Datos del evento: "titulo|rewardId|redemptionId" para reward,
     *                     cantidad para cheer, descripción para subgift.
     * @param showBackground No usado actualmente, reservado para uso futuro.
     * @throws BadLocationException Si la posición de inserción no es válida.
     */
    public void render(StyledDocument doc,
                       String platform, String username, String text,
                       List<EmoteToken> tokens, List<String> badgeUrls,
                       String userColor, String eventType, String eventExtra,
                       boolean showBackground) throws BadLocationException {

        SimpleAttributeSet baseStyle = buildBaseStyle();

        if (eventType != null) {
            switch (eventType) {
                case "reward"  -> { renderReward(doc, baseStyle, platform, username,
                                           text, tokens, badgeUrls, userColor,
                                           eventExtra); return; }
                case "subgift" -> { renderSubgift(doc, baseStyle, username, eventExtra); return; }
                case "cheer"   -> { renderCheer(doc, baseStyle, platform, username,
                                           tokens, badgeUrls, userColor, eventExtra); return; }
            }
        }

        renderMessageBody(doc, platform, username, tokens, badgeUrls, userColor, baseStyle);
        doc.insertString(doc.getLength(), "\n", baseStyle);
    }

    /** Inyecta el EventSub activo para los botones de aprobación de recompensas. */
    public void setEventSub(TwitchEventSub eventSub) {
        this.eventSub = eventSub;
    }

    /** Devuelve la caché de imágenes compartida. */
    public ImageCache getImageCache() {
        return imageCache;
    }

    /**
     * Acorta una URL larga insertando "…" en el centro.
     *
     * @param url    URL original.
     * @param maxLen Longitud máxima del resultado.
     * @return URL acortada si supera maxLen, la original si no.
     */
    public static String shortenUrl(String url, int maxLen) {
        if (url.length() <= maxLen) return url;
        int keep = (maxLen - 1) / 2;
        return url.substring(0, keep) + "…" + url.substring(url.length() - keep);
    }

    // ── Renderizado de eventos ────────────────────────────────────────────────

    /**
     * Renderiza una recompensa de canal.
     * El formato varía según si tiene mensaje del usuario o no, y si es
     * un "Mensaje destacado" (highlighted message del IRC de Twitch).
     *
     * eventExtra tiene formato "titulo|rewardId|redemptionId".
     */
    private void renderReward(StyledDocument doc, SimpleAttributeSet baseStyle,
                               String platform, String username, String text,
                               List<EmoteToken> tokens, List<String> badgeUrls,
                               String userColor, String eventExtra)
            throws BadLocationException {

        String[] parts      = eventExtra != null ? eventExtra.split("\\|", 3) : new String[0];
        String rewardTitle  = parts.length > 0 ? parts[0] : "";
        String rewardId     = parts.length > 1 ? parts[1] : null;
        String redemptionId = parts.length > 2 ? parts[2] : null;

        if ("Mensaje destacado".equals(rewardTitle)) {
            // Mensaje destacado: texto subrayado + tag al final
            SimpleAttributeSet highlightStyle = new SimpleAttributeSet(baseStyle);
            StyleConstants.setUnderline(highlightStyle, true);
            renderMessageBody(doc, platform, username, tokens, badgeUrls, userColor, highlightStyle);

            SimpleAttributeSet tagStyle = new SimpleAttributeSet(baseStyle);
            StyleConstants.setForeground(tagStyle, REWARD_COLOR);
            StyleConstants.setBold(tagStyle, true);
            doc.insertString(doc.getLength(), " — ★ Mensaje destacado\n", tagStyle);

        } else {
            // Recompensa normal
            SimpleAttributeSet eventStyle = new SimpleAttributeSet(baseStyle);
            StyleConstants.setForeground(eventStyle, REWARD_COLOR);
            StyleConstants.setBold(eventStyle, true);

            if (text == null || text.isBlank()) {
                doc.insertString(doc.getLength(),
                        "★ " + username + " CANJEÓ: — " + rewardTitle + "\n", eventStyle);
            } else {
                doc.insertString(doc.getLength(),
                        "★ RECOMPENSA CANJEADA: — " + rewardTitle + "\n", eventStyle);
                renderMessageBody(doc, platform, username, tokens, badgeUrls, userColor, baseStyle);
            }

            // Botones de aprobar/rechazar si tenemos los IDs de la recompensa
            if (rewardId != null && redemptionId != null && eventSub != null) {
                insertRewardButtons(doc, rewardId, redemptionId);
            }

            doc.insertString(doc.getLength(), "\n", baseStyle);
        }
    }

    /** Renderiza un evento de regalo de suscripción. */
    private void renderSubgift(StyledDocument doc, SimpleAttributeSet baseStyle,
                                String username, String eventExtra)
            throws BadLocationException {
        SimpleAttributeSet style = new SimpleAttributeSet(baseStyle);
        StyleConstants.setForeground(style, SUBGIFT_COLOR);
        StyleConstants.setBold(style, true);
        doc.insertString(doc.getLength(),
                "♥ GIFT — " + username + " regaló " + eventExtra + "\n", style);
    }

    /** Renderiza un mensaje con bits (cheer). */
    private void renderCheer(StyledDocument doc, SimpleAttributeSet baseStyle,
                              String platform, String username,
                              List<EmoteToken> tokens, List<String> badgeUrls,
                              String userColor, String eventExtra)
            throws BadLocationException {
        SimpleAttributeSet style = new SimpleAttributeSet(baseStyle);
        StyleConstants.setForeground(style, CHEER_COLOR);
        StyleConstants.setBold(style, true);
        doc.insertString(doc.getLength(), "✦ " + eventExtra + " BITS — ", style);
        renderMessageBody(doc, platform, username, tokens, badgeUrls, userColor, baseStyle);
        doc.insertString(doc.getLength(), "\n", baseStyle);
    }

    // ── Renderizado del cuerpo del mensaje ────────────────────────────────────

    /**
     * Renderiza el cuerpo estándar de un mensaje:
     * icono de plataforma → badges → nombre de usuario → tokens.
     */
    private void renderMessageBody(StyledDocument doc,
                                    String platform, String username,
                                    List<EmoteToken> tokens, List<String> badgeUrls,
                                    String userColor, AttributeSet overrideStyle)
            throws BadLocationException {

        // Icono de plataforma
        SimpleAttributeSet platformStyle = new SimpleAttributeSet(overrideStyle);
        StyleConstants.setForeground(platformStyle,
                "twitch".equals(platform) ? TWITCH_COLOR : YOUTUBE_COLOR);
        StyleConstants.setBold(platformStyle, true);

        ImageIcon platformIcon = "twitch".equals(platform) ? TWITCH_ICON : YOUTUBE_ICON;
        if (platformIcon != null) {
            insertIcon(doc, platformIcon, platform, platformStyle);
            doc.insertString(doc.getLength(), " ", platformStyle);
        } else {
            doc.insertString(doc.getLength(),
                    "twitch".equals(platform) ? "[T] " : "[YT] ", platformStyle);
        }

        // Badges
        if (badgeUrls != null) {
            for (String badgeUrl : badgeUrls) {
                ImageIcon badge = imageCache.get(badgeUrl);
                if (badge != null) {
                    insertIcon(doc, badge, "badge", overrideStyle);
                    doc.insertString(doc.getLength(), " ", overrideStyle);
                }
            }
        }

        // Nombre de usuario
        SimpleAttributeSet userStyle = new SimpleAttributeSet(overrideStyle);
        StyleConstants.setForeground(userStyle, resolveUserColor(userColor, username));
        StyleConstants.setBold(userStyle, true);
        doc.insertString(doc.getLength(), username + ": ", userStyle);

        // Tokens de texto y emotes
        SimpleAttributeSet textStyle = new SimpleAttributeSet(overrideStyle);
        StyleConstants.setForeground(textStyle, TEXT_COLOR);
        StyleConstants.setBold(textStyle, false);
        renderTokens(doc, tokens, textStyle);
    }

    /**
     * Renderiza la lista de tokens insertando texto o iconos de emotes.
     * Las URLs en los tokens de texto se detectan y marcan como clicables.
     */
    private void renderTokens(StyledDocument doc, List<EmoteToken> tokens,
                               SimpleAttributeSet textStyle)
            throws BadLocationException {
        for (EmoteToken token : tokens) {
            switch (token) {
                case EmoteToken.Text t  -> renderTextToken(doc, t.content(), textStyle);
                case EmoteToken.Emote e -> {
                    ImageIcon icon = imageCache.get(e.url());
                    if (icon != null) {
                        insertIcon(doc, icon, e.name(), textStyle);
                    } else {
                        doc.insertString(doc.getLength(), e.name(), textStyle);
                    }
                }
            }
        }
    }

    /**
     * Renderiza un token de texto detectando y marcando las URLs como clicables.
     * El texto antes y después de cada URL se inserta con el estilo normal.
     * Las URLs se insertan acortadas con el atributo "link" para ChatOverlay.
     */
    private void renderTextToken(StyledDocument doc, String content,
                                  SimpleAttributeSet textStyle)
            throws BadLocationException {
        Matcher m    = URL_PATTERN.matcher(content);
        int     last = 0;

        while (m.find()) {
            if (m.start() > last) {
                doc.insertString(doc.getLength(),
                        content.substring(last, m.start()), textStyle);
            }
            String url      = m.group();
            String shortUrl = shortenUrl(url, 40);
            insertClickableUrl(doc, shortUrl, url, textStyle);
            last = m.end();
        }

        if (last < content.length()) {
            doc.insertString(doc.getLength(), content.substring(last), textStyle);
        }
    }

    // ── Inserción de elementos en el documento ────────────────────────────────

    /**
     * Inserta un icono centrado verticalmente en el documento.
     * El nombre de estilo incluye nanoTime() para garantizar unicidad.
     */
    private void insertIcon(StyledDocument doc, ImageIcon icon,
                             String altText, AttributeSet baseStyle)
            throws BadLocationException {
        CenteredIcon centered  = new CenteredIcon(icon, LINE_HEIGHT);
        Style        iconStyle = doc.addStyle(altText + System.nanoTime(), null);
        StyleConstants.setIcon(iconStyle, centered);
        doc.insertString(doc.getLength(), " ", iconStyle);
    }

    /**
     * Inserta una URL clicable en el documento.
     * El texto visible puede ser la URL acortada; la URL completa se guarda
     * en el atributo "link" para que ChatOverlay la abra al hacer clic.
     *
     * @param visible  Texto mostrado al usuario (puede ser URL acortada).
     * @param fullUrl  URL completa para abrir al hacer clic.
     * @param baseStyle Estilo base sobre el que aplicar el estilo de enlace.
     */
    private void insertClickableUrl(StyledDocument doc, String visible,
                                     String fullUrl, AttributeSet baseStyle)
            throws BadLocationException {
        SimpleAttributeSet linkStyle = new SimpleAttributeSet(baseStyle);
        StyleConstants.setForeground(linkStyle, LINK_COLOR);
        StyleConstants.setUnderline(linkStyle, true);
        linkStyle.addAttribute("link", fullUrl);
        doc.insertString(doc.getLength(), visible, linkStyle);
    }

    /**
     * Inserta los botones de aprobar (✔) y rechazar (✖) una recompensa.
     * Los botones llaman a {@link TwitchEventSub#updateRedemption} y se
     * deshabilitan mutuamente al pulsarse para evitar doble acción.
     *
     * Solo se insertan si el EventSub está activo ({@link #setEventSub}).
     */
    private void insertRewardButtons(StyledDocument doc,
                                      String rewardId, String redemptionId)
            throws BadLocationException {
        JButton[] buttons = new JButton[2];

        buttons[0] = buildRewardButton("✔", new Color(50, 220, 120));
        buttons[1] = buildRewardButton("✖", new Color(255, 80, 80));

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
            Style style = doc.addStyle(
                    btn.getText() + "-" + redemptionId + System.nanoTime(), null);
            StyleConstants.setComponent(style, btn);
            doc.insertString(doc.getLength(), " ", style);
        }
    }

    /** Construye un botón de acción de recompensa con el estilo del overlay. */
    private JButton buildRewardButton(String label, Color color) {
        JButton btn = new JButton(label);
        btn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 11));
        btn.setForeground(color);
        btn.setBackground(new Color(30, 30, 35));
        btn.setBorder(BorderFactory.createLineBorder(color, 1));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setMargin(new Insets(1, 4, 1, 4));
        return btn;
    }

    // ── Colores y estilos ─────────────────────────────────────────────────────

    /** Construye el AttributeSet base con la fuente del chat. */
    private SimpleAttributeSet buildBaseStyle() {
        SimpleAttributeSet style = new SimpleAttributeSet();
        StyleConstants.setFontFamily(style, CHAT_FONT.getFamily());
        StyleConstants.setFontSize(style, CHAT_FONT.getSize());
        return style;
    }

    /**
     * Resuelve el color del nombre de usuario.
     * Si tiene un color válido en formato "#RRGGBB", lo usa.
     * Si no, genera uno determinístico a partir del hash del nombre.
     */
    private Color resolveUserColor(String hexColor, String username) {
        if (hexColor != null && hexColor.startsWith("#") && hexColor.length() == 7) {
            try { return Color.decode(hexColor); }
            catch (NumberFormatException ignored) {}
        }
        return NAME_COLOR_PALETTE[Math.abs(username.hashCode()) % NAME_COLOR_PALETTE.length];
    }

    // ── Carga de recursos ─────────────────────────────────────────────────────

    /**
     * Carga un icono desde los resources del jar, escalado proporcionalmente
     * al tamaño de icono configurado.
     *
     * @param path Ruta del recurso (ej. "/icons/twitch.png").
     * @return ImageIcon escalado, o null si no se puede cargar.
     */
    private ImageIcon loadResourceIcon(String path) {
        try {
            var url = getClass().getResource(path);
            if (url == null) return null;
            BufferedImage img         = ImageIO.read(url);
            int           scaledWidth = (int)((double) img.getWidth() / img.getHeight() * iconSize);
            return new ImageIcon(img.getScaledInstance(scaledWidth, iconSize, Image.SCALE_SMOOTH));
        } catch (Exception e) {
            System.err.println("[EmoteRenderer] No se pudo cargar icono: " + path);
            return null;
        }
    }
}