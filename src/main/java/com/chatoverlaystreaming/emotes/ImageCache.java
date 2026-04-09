package com.chatoverlaystreaming.emotes;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

public class ImageCache {

    private static final int MAX_SIZE    = 300;
    private static final int EMOTE_PX   = 24; // tamaño de renderizado

    private final Map<String, ImageIcon> cache = new LinkedHashMap<>(MAX_SIZE, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ImageIcon> e) {
            return size() > MAX_SIZE;
        }
    };

    public synchronized ImageIcon get(String url) {
        return cache.computeIfAbsent(url, this::download);
    }

    private ImageIcon download(String url) {
        try {
            BufferedImage img = ImageIO.read(URI.create(url).toURL());
            if (img == null) return null;
            Image scaled = img.getScaledInstance(EMOTE_PX, EMOTE_PX, Image.SCALE_SMOOTH);
            return new ImageIcon(scaled);
        } catch (Exception e) {
            System.err.println("[ImageCache] No se pudo descargar: " + url);
            return null;
        }
    }
}