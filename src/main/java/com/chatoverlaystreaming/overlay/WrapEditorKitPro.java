package com.chatoverlaystreaming.overlay;

import javax.swing.text.*;
import java.awt.*;

/**
 * EditorKit personalizado para el JTextPane del chat que mejora el wrap
 * de líneas largas sin espacios (URLs, cadenas de emojis con ":", etc.).
 *
 * Problema que resuelve: el wrap por defecto de Swing no corta palabras
 * sin espacios, lo que hace que URLs largas o textos como "LOOOOOOL"
 * se salgan del panel y rompan el layout de los mensajes siguientes.
 *
 * Estrategia de wrap:
 *   - Si el texto tiene espacios, Swing hace el wrap normal (no intervenimos).
 *   - Si contiene ":" o "_" (claves de emojis, rutas), no cortamos para no
 *     romper tokens como ":hand-pink-waving:" a mitad.
 *   - Si supera 30 caracteres sin espacios ni separadores, cortamos por la mitad.
 */
public class WrapEditorKitPro extends StyledEditorKit {

    private final ViewFactory factory = new WrapColumnFactory();

    @Override
    public ViewFactory getViewFactory() {
        return factory;
    }

    /**
     * Factory que sustituye el LabelView estándar por SmartWrapLabelView
     * para el contenido de texto, manteniendo el comportamiento por defecto
     * para el resto de tipos de elementos (párrafos, iconos, componentes).
     */
    static class WrapColumnFactory implements ViewFactory {
        @Override
        public View create(Element elem) {
            String kind = elem.getName();
            if (kind == null) return new LabelView(elem);

            return switch (kind) {
                case AbstractDocument.ContentElementName   -> new SmartWrapLabelView(elem);
                case AbstractDocument.ParagraphElementName -> new ParagraphView(elem);
                case AbstractDocument.SectionElementName   -> new BoxView(elem, View.Y_AXIS);
                case StyleConstants.ComponentElementName   -> new ComponentView(elem);
                case StyleConstants.IconElementName        -> new IconView(elem);
                default                                    -> new LabelView(elem);
            };
        }
    }

    /**
     * LabelView con lógica de wrap inteligente para textos sin espacios.
     *
     * Devuelve un span mínimo de 0 en el eje X para que el layout
     * pueda comprimir el contenido si es necesario, evitando que desborde.
     */
    static class SmartWrapLabelView extends LabelView {

        SmartWrapLabelView(Element elem) {
            super(elem);
        }

        /**
         * Span mínimo en X = 0 para permitir que el layout comprima el contenido.
         * En Y se delega al comportamiento estándar.
         */
        @Override
        public float getMinimumSpan(int axis) {
            return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
        }

        /**
         * Decide dónde cortar el texto cuando no cabe en el ancho disponible.
         * Solo actúa en el eje X; en Y delega al comportamiento estándar.
         */
        @Override
        public View breakView(int axis, int p0, float pos, float len) {
            if (axis != View.X_AXIS) {
                return super.breakView(axis, p0, pos, len);
            }

            int p1         = getEndOffset();
            int breakPoint = findBreakPoint(p0, p1);

            // Si no encontramos un punto de corte propio, delegar a Swing
            if (breakPoint <= p0 || breakPoint >= p1) {
                return super.breakView(axis, p0, pos, len);
            }

            return createFragment(p0, breakPoint);
        }

        /**
         * Calcula el punto de corte óptimo para un rango de texto.
         *
         * Reglas (en orden de prioridad):
         *   1. Si hay espacios → Swing gestiona el wrap, devolver -1.
         *   2. Si hay ":" o "_" → no cortar (son separadores de tokens de emojis).
         *   3. Si supera 30 caracteres → cortar por la mitad.
         *   4. En cualquier otro caso → no forzar corte, devolver -1.
         *
         * @param p0 Offset de inicio del rango en el documento.
         * @param p1 Offset de fin del rango en el documento.
         * @return Offset del punto de corte, o -1 si no se debe forzar corte.
         */
        private int findBreakPoint(int p0, int p1) {
            try {
                String text = getDocument().getText(p0, p1 - p0);

                if (text.contains(" "))                      return -1;
                if (text.contains(":") || text.contains("_")) return -1;
                if (text.length() > 30)                      return p0 + text.length() / 2;

                return -1;
            } catch (BadLocationException e) {
                return -1;
            }
        }
    }
}