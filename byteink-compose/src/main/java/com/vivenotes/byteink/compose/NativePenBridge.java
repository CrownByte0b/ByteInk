package com.vivenotes.byteink.compose;

import java.util.List;

interface NativePenBridge extends AutoCloseable {
    /** HOVER is a tool above the surface without contact; HOVER_EXIT ends it (only native Wayland reports these). */
    int BEGIN = 0, MOVE = 1, FINISH = 2, CANCEL = 3, CANCEL_ALL = 4, HOVER = 5, HOVER_EXIT = 6;
    int MOUSE = 0, PEN = 1, TOUCH = 2;
    int PRESSURE = 1, TILT = 2;
    record Point(double x, double y, long ticks, float pressure, float tiltX, float tiltY, int axes) {}
    record Frame(long pointerId, int phase, int tool, List<Point> points) {
        public Frame { points = List.copyOf(points); }
    }
    /**
     * Sets the cursor-shape-v1 {@code shape} that tools over this bridge's surface show, for a window
     * drawn at integer {@code scale}. Any thread. Only native Wayland leaves a tool's cursor to the
     * client; elsewhere the pen moves the system pointer, so this does nothing.
     */
    default void cursor(int shape, int scale) {}
    @Override void close();
}
