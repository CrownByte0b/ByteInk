package com.vivenotes.byteink.compose;

import java.awt.Cursor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pen cursors on native Wayland: the cursor-shape-v1 shape for an AWT cursor, and the Xcursor names
 * a theme may give that shape when the compositor has no cursor-shape-v1.
 */
final class PenCursors {
    /** No cursor at all: an AWT custom cursor named after CSS's {@code none}. */
    static final int HIDDEN = 0;
    static final int DEFAULT = 1;
    /** cursor-shape-v1 version 1 shapes 1..34, by the CSS cursor names the protocol takes them from. */
    private static final String[] CSS = {
        "default", "context-menu", "help", "pointer", "progress", "wait", "cell", "crosshair", "text",
        "vertical-text", "alias", "copy", "move", "no-drop", "not-allowed", "grab", "grabbing",
        "e-resize", "n-resize", "ne-resize", "nw-resize", "s-resize", "se-resize", "sw-resize", "w-resize",
        "ew-resize", "ns-resize", "nesw-resize", "nwse-resize", "col-resize", "row-resize",
        "all-scroll", "zoom-in", "zoom-out",
    };
    /** Older Xcursor names of each shape, tried after its CSS name. */
    private static final String[][] LEGACY = {
        {"left_ptr", "arrow", "top_left_arrow"}, {}, {"question_arrow", "whats_this"},
        {"hand2", "pointing_hand", "hand1", "hand"}, {"left_ptr_watch", "half-busy"}, {"watch"}, {"plus"},
        {"cross", "tcross"}, {"xterm", "ibeam"}, {}, {"link", "dnd-link"}, {"dnd-copy"}, {"fleur", "dnd-move"},
        {"dnd-no-drop"}, {"crossed_circle", "forbidden"}, {"openhand"}, {"closedhand", "fleur"},
        {"right_side"}, {"top_side"}, {"top_right_corner"}, {"top_left_corner"}, {"bottom_side"},
        {"bottom_right_corner"}, {"bottom_left_corner"}, {"left_side"},
        {"sb_h_double_arrow", "h_double_arrow"}, {"sb_v_double_arrow", "v_double_arrow"},
        {"fd_double_arrow", "size_bdiag"}, {"bd_double_arrow", "size_fdiag"},
        {"sb_h_double_arrow", "split_h"}, {"sb_v_double_arrow", "split_v"}, {"fleur"}, {}, {},
    };

    private PenCursors() {}

    /**
     * The shape of {@code cursor}: AWT's predefined cursors as JBR maps them for its own pointer, and
     * a custom cursor by its name when that is a CSS cursor name. Anything else is the default arrow.
     */
    static int shape(Cursor cursor) {
        if (cursor == null) return DEFAULT;
        return switch (cursor.getType()) {
            case Cursor.CROSSHAIR_CURSOR -> 8;
            case Cursor.TEXT_CURSOR -> 9;
            case Cursor.WAIT_CURSOR -> 6;
            case Cursor.SW_RESIZE_CURSOR -> 24;
            case Cursor.SE_RESIZE_CURSOR -> 23;
            case Cursor.NW_RESIZE_CURSOR -> 21;
            case Cursor.NE_RESIZE_CURSOR -> 20;
            case Cursor.N_RESIZE_CURSOR -> 19;
            case Cursor.S_RESIZE_CURSOR -> 22;
            case Cursor.W_RESIZE_CURSOR -> 25;
            case Cursor.E_RESIZE_CURSOR -> 18;
            case Cursor.HAND_CURSOR -> 4;
            case Cursor.MOVE_CURSOR -> 13;
            case Cursor.CUSTOM_CURSOR -> named(cursor.getName());
            default -> DEFAULT;
        };
    }

    /** The shape a CSS cursor keyword names, ignoring case and with '_' for '-'; the default arrow otherwise. */
    static int named(String name) {
        if (name == null) return DEFAULT;
        String keyword = name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (keyword.equals("none")) return HIDDEN;
        for (int i = 0; i < CSS.length; i++) if (CSS[i].equals(keyword)) return i + 1;
        return DEFAULT;
    }

    /** Theme cursor names to try for {@code shape}, best first, ending with the default arrow's. */
    static List<String> themeNames(int shape) {
        List<String> names = new ArrayList<>();
        if (shape > DEFAULT && shape <= CSS.length) {
            names.add(CSS[shape - 1]);
            names.addAll(List.of(LEGACY[shape - 1]));
        }
        names.add(CSS[0]);
        names.addAll(List.of(LEGACY[0]));
        return names;
    }
}
