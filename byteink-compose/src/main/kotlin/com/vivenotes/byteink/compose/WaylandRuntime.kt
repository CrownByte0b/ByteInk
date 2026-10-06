package com.vivenotes.byteink.compose

import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import javax.swing.SwingUtilities

/** JBR owns the connection and surface. All peer access stays on the AWT event thread. */
internal object WaylandRuntime {
    fun isWayland(): Boolean = Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit"

    class WindowSurface(val display: Long, val surface: Long, val window: Window, private val peer: Any) {
        fun surfaceUnitsPerLocalUnit(): Double {
            check(EventQueue.isDispatchThread())
            val method = peer.javaClass.getMethod("javaUnitsToSurfaceUnits", Int::class.javaPrimitiveType)
            method.isAccessible = true
            return (method.invoke(peer, 65536) as Int) / 65536.0
        }
    }

    fun windowSurface(component: Component): WindowSurface = findWindowSurface(component)
        ?: error("The Wayland window surface has not been configured yet")

    fun findWindowSurface(component: Component): WindowSurface? {
        check(EventQueue.isDispatchThread())
        val window = (component as? Window) ?: SwingUtilities.getWindowAncestor(component)
            ?: error("Wayland input requires a component in a visible AWT window")
        check(window.isShowing && component.isShowing) { "Subscribe after showing the Wayland window" }
        try {
            val displayClass = Class.forName("sun.awt.wl.WLDisplay")
            val getInstance = displayClass.getMethod("getInstance").also { it.isAccessible = true }
            val getDisplay = displayClass.getMethod("getDisplayPtr").also { it.isAccessible = true }
            val display = getDisplay.invoke(getInstance.invoke(null)) as Long
            val mapField = Class.forName("sun.awt.wl.WLToolkit").getDeclaredField("wlSurfaceToPeerMap")
                .also { it.isAccessible = true }
            val peers = mapField.get(null) as Map<*, *>
            val peer = synchronized(peers) { peers.values.firstOrNull { candidate ->
                candidate != null && candidate.javaClass.getMethod("getTarget").also { it.isAccessible = true }.invoke(candidate) === window
            } } ?: return null
            val getSurface = peer.javaClass.getMethod("getSurface").also { it.isAccessible = true }
            val surfaceObject = getSurface.invoke(peer) ?: return null
            val getPointer = surfaceObject.javaClass.getMethod("getWlSurfacePtr").also { it.isAccessible = true }
            val surface = getPointer.invoke(surfaceObject) as Long
            check(display != 0L) { "The Wayland display is not live" }
            if (surface == 0L) return null
            return WindowSurface(display, surface, window, peer)
        } catch (failure: ReflectiveOperationException) {
            throw UnsupportedOperationException("Native Wayland ink requires JBR with --add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED", failure)
        } catch (failure: java.lang.reflect.InaccessibleObjectException) {
            throw UnsupportedOperationException("Native Wayland ink requires --add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED", failure)
        }
    }
}
