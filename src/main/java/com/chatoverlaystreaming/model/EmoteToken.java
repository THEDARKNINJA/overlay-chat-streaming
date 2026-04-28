package com.chatoverlaystreaming.model;

/**
 * Token que representa una unidad de contenido en un mensaje de chat.
 *
 * Los mensajes se descomponen en listas de EmoteToken antes de renderizarse.
 * Cada token es o bien texto plano o bien un emote con imagen.
 *
 * Es una sealed interface con dos implementaciones como records:
 * <ul>
 *   <li>{@link Text} — fragmento de texto plano, incluyendo caracteres Unicode
 *       de emojis que la fuente Segoe UI Emoji renderiza directamente.</li>
 *   <li>{@link Emote} — emote con imagen descargada de una URL o cargada
 *       desde disco (prefijo "local:") e insertada en el documento.</li>
 * </ul>
 *
 * El uso de sealed interface permite exhaustividad en el switch de EmoteRenderer:
 * <pre>
 *   switch (token) {
 *       case EmoteToken.Text  t -> doc.insertString(..., t.content(), style);
 *       case EmoteToken.Emote e -> insertIcon(..., imageCache.get(e.url()), ...);
 *   }
 * </pre>
 */
public sealed interface EmoteToken permits EmoteToken.Text, EmoteToken.Emote {

    /**
     * Fragmento de texto plano dentro de un mensaje.
     * Puede contener caracteres Unicode de emojis (ej. "😀") que la fuente
     * Segoe UI Emoji renderiza sin necesidad de imagen adicional.
     *
     * @param content Texto a mostrar.
     */
    record Text(String content) implements EmoteToken {}

    /**
     * Emote con imagen dentro de un mensaje.
     *
     * La URL puede ser:
     * <ul>
     *   <li>Una URL HTTP de la CDN de Twitch o BTTV — {@link com.chatoverlaystreaming.emotes.ImageCache}
     *       la descarga y cachea.</li>
     *   <li>Una URL con prefijo {@code "local:"} — para emojis custom de YouTube
     *       cargados desde disco por {@link com.chatoverlaystreaming.emotes.YouTubeEmojiCache}
     *       y ya presentes en el ImageCache compartido.</li>
     * </ul>
     *
     * @param name Nombre del emote (ej. ":hand-pink-waving:", "PogChamp").
     *             Se usa como texto alternativo si la imagen no está disponible.
     * @param url  URL de la imagen o clave "local:key" para imágenes locales.
     */
    record Emote(String name, String url) implements EmoteToken {}
}