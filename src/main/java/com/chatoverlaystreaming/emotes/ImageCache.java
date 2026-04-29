package com.chatoverlaystreaming.emotes;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Caché LRU de imágenes de emotes e iconos descargadas desde URLs remotas.
 *
 * Descarga y escala las imágenes bajo demanda y las mantiene en memoria hasta
 * un máximo de {@value #MAX_SIZE} entradas. Cuando se supera el límite, se
 * elimina automáticamente la entrada menos usada recientemente (LRU).
 *
 * Todas las operaciones son thread-safe mediante sincronización en el mapa.
 *
 * URLs locales:
 *   Las URLs con prefijo {@code "local:"} no se descargan — corresponden a
 *   emojis custom de YouTube cargados desde disco por {@link YouTubeEmojiCache}
 *   e inyectados directamente con {@link #put}. Si {@link #get} recibe una
 *   URL local que aún no está en caché, devuelve null en lugar de intentar
 *   descargarla (lo que causaría una excepción de URI inválida).
 *
 * Escalado:
 *   Las imágenes se escalan al {@code emoteHeight} configurado manteniendo
 *   la proporción original (ancho proporcional, alto fijo).
 */
public class ImageCache {

    // ── Constantes ────────────────────────────────────────────────────────────

    /** Número máximo de imágenes en caché antes de eliminar la menos usada. */
    private static final int MAX_SIZE = 300;

    // ── Estado ────────────────────────────────────────────────────────────────

    /** Alto en píxeles al que se escalan todas las imágenes descargadas. */
    private final int emoteHeight;

    /**
     * Mapa LRU: URL → ImageIcon escalado.
     * LinkedHashMap con accessOrder=true mantiene el orden de acceso,
     * y removeEldestEntry elimina la entrada más antigua cuando se supera MAX_SIZE.
     */
    private final Map<String, ImageIcon> cache =
            new LinkedHashMap<>(MAX_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ImageIcon> e) {
                    return size() > MAX_SIZE;
                }
            };

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param emoteHeight Alto en píxeles al que escalar las imágenes descargadas.
     *                    Normalmente coincide con el iconSize del config.
     */
    public ImageCache(int emoteHeight) {
        this.emoteHeight = emoteHeight;
    }

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Devuelve el ImageIcon para la URL dada, descargándolo si no está en caché.
     * Las URLs con prefijo "local:" devuelven null si no están ya en caché
     * (deben inyectarse previamente con {@link #put}).
     *
     * @param url URL de la imagen, o clave "local:key" para imágenes locales.
     * @return ImageIcon escalado, o null si la descarga falla o la URL es local y no está cacheada.
     */
    public synchronized ImageIcon get(String url) {
        return cache.computeIfAbsent(url, this::download);
    }

    /**
     * Inyecta directamente un ImageIcon en el caché bajo la URL/clave dada.
     * Usado por {@link YouTubeEmojiCache} para registrar emojis locales con
     * clave "local:key" antes de que EmoteRenderer los solicite.
     *
     * @param url  Clave bajo la que almacenar el icono.
     * @param icon ImageIcon ya cargado y escalado.
     */
    public synchronized void put(String url, ImageIcon icon) {
        cache.put(url, icon);
    }

    // ── Descarga ──────────────────────────────────────────────────────────────

    /**
     * Descarga y escala la imagen de la URL dada.
     * Devuelve null si la URL es local (prefijo "local:"), si la descarga falla,
     * o si la imagen no se puede decodificar.
     *
     * El ancho se calcula proporcionalmente al alto configurado para preservar
     * la relación de aspecto original del emote.
     *
     * @param url URL HTTP/HTTPS de la imagen.
     * @return ImageIcon escalado, o null si falla.
     */
    private ImageIcon download(String url) {
        if (url == null || url.startsWith("local:")) return null;

        try {
            BufferedImage img = ImageIO.read(URI.create(url).toURL());
            if (img == null) return null;

            int scaledWidth = (int)((double) img.getWidth() / img.getHeight() * emoteHeight);
            Image scaled    = img.getScaledInstance(scaledWidth, emoteHeight, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);

        } catch (Exception e) {
            System.err.println("[ImageCache] No se pudo descargar: " + url);
            return null;
        }
    }
}