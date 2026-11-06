package com.vivenotes.byteink.kit

/** The polygon rules used by ViveNotes selection/replay on every backend. */
public object InkPolygonMath {
    public fun inside(point: InkPoint, polygon: List<InkPoint>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var previous = polygon.last()
        polygon.forEach { current ->
            if ((current.y > point.y) != (previous.y > point.y)) {
                val crossingX = (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x
                if (point.x < crossingX) inside = !inside
            }
            previous = current
        }
        return inside
    }

    public fun convex(polygon: List<InkPoint>): Boolean {
        if (polygon.size < 3) return false
        var sign = 0
        polygon.indices.forEach { i ->
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]; val c = polygon[(i + 2) % polygon.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross != 0f) {
                val turn = if (cross > 0f) 1 else -1
                if (sign == 0) sign = turn else if (sign != turn) return false
            }
        }
        return sign != 0
    }

    public fun near(point: InkPoint, polygon: List<InkPoint>, edgeTolerance: Float): Boolean {
        if (inside(point, polygon)) return true
        if (polygon.isEmpty()) return false
        val squared = edgeTolerance.coerceAtLeast(0f).let { it * it }
        if (squared == 0f) return false
        var previous = polygon.last()
        polygon.forEach { current ->
            if (squaredDistance(point, previous, current) <= squared) return true
            previous = current
        }
        return false
    }

    public fun squaredDistance(point: InkPoint, start: InkPoint, end: InkPoint): Float {
        val dx = end.x - start.x; val dy = end.y - start.y
        val length = dx * dx + dy * dy
        if (length == 0f) { val x = point.x - start.x; val y = point.y - start.y; return x * x + y * y }
        val fraction = (((point.x - start.x) * dx + (point.y - start.y) * dy) / length).coerceIn(0f, 1f)
        val x = point.x - (start.x + fraction * dx); val y = point.y - (start.y + fraction * dy)
        return x * x + y * y
    }
}
