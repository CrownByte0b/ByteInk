package com.vivenotes.byteink.compose;

import java.util.List;

interface NativePenBridge extends AutoCloseable {
    int BEGIN = 0, MOVE = 1, FINISH = 2, CANCEL = 3, CANCEL_ALL = 4;
    int MOUSE = 0, PEN = 1, TOUCH = 2;
    int PRESSURE = 1, TILT = 2;
    record Point(double x, double y, long ticks, float pressure, float tiltX, float tiltY, int axes) {}
    record Frame(long pointerId, int phase, int tool, List<Point> points) {
        public Frame { points = List.copyOf(points); }
    }
    @Override void close();
}
