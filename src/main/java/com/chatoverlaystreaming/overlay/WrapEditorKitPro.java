package com.chatoverlaystreaming.overlay;

import javax.swing.text.*;
import java.awt.*;

public class WrapEditorKitPro extends StyledEditorKit {

    private final ViewFactory defaultFactory = new WrapColumnFactoryPro();

    @Override
    public ViewFactory getViewFactory() {
        return defaultFactory;
    }

    static class WrapColumnFactoryPro implements ViewFactory {
        @Override
        public View create(Element elem) {
            String kind = elem.getName();

            if (kind != null) {
                return switch (kind) {
                    case AbstractDocument.ContentElementName -> new SmartWrapLabelView(elem);
                    case AbstractDocument.ParagraphElementName -> new ParagraphView(elem);
                    case AbstractDocument.SectionElementName -> new BoxView(elem, View.Y_AXIS);
                    case StyleConstants.ComponentElementName -> new ComponentView(elem);
                    case StyleConstants.IconElementName -> new IconView(elem);
                    default -> new LabelView(elem);
                };
            }
            return new LabelView(elem);
        }
    }

    static class SmartWrapLabelView extends LabelView {

        private static final char[] BREAK_CHARS =
                "/-_.?&=".toCharArray();

        public SmartWrapLabelView(Element elem) {
            super(elem);
        }

        @Override
        public float getMinimumSpan(int axis) {
            if (axis == View.X_AXIS) {
                return 0;
            }
            return super.getMinimumSpan(axis);
        }

        @Override
        public View breakView(int axis, int p0, float pos, float len) {
            if (axis != View.X_AXIS) {
                return super.breakView(axis, p0, pos, len);
            }

            int p1 = getEndOffset();
            int breakPoint = findBreakSpot(p0, p1);

            if (breakPoint <= p0 || breakPoint >= p1) {
                return super.breakView(axis, p0, pos, len);
            }

            return createFragment(p0, breakPoint);
        }

        private int findBreakSpot(int p0, int p1) {
            try {
                Document doc = getDocument();
                String text = doc.getText(p0, p1 - p0);

                // 1) Si contiene espacios, deja que Swing haga el wrap normal
                if (text.contains(" ")) {
                    return -1; // no forzar wrap
                }

                // 2) Si contiene ":" o "_" o cualquier separador estructural, NO cortar
                if (text.contains(":") || text.contains("_")) {
                    return -1;
                }

                // 3) Si es demasiado largo, cortar por la mitad
                if (text.length() > 30) {
                    return p0 + (text.length() / 2);
                }

                return -1;

            } catch (Exception e) {
                return -1;
            }
        }

    }
}
