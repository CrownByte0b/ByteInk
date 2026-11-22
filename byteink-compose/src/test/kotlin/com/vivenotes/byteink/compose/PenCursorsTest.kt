package com.vivenotes.byteink.compose

import java.awt.Cursor
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import kotlin.test.*

class PenCursorsTest {
    private class Named(name: String) : Cursor(name)

    @Test fun awtPredefinedCursorsTakeTheShapesJbrGivesItsOwnPointer() {
        val expected = mapOf(
            Cursor.DEFAULT_CURSOR to 1, Cursor.CROSSHAIR_CURSOR to 8, Cursor.TEXT_CURSOR to 9, Cursor.WAIT_CURSOR to 6,
            Cursor.SW_RESIZE_CURSOR to 24, Cursor.SE_RESIZE_CURSOR to 23, Cursor.NW_RESIZE_CURSOR to 21,
            Cursor.NE_RESIZE_CURSOR to 20, Cursor.N_RESIZE_CURSOR to 19, Cursor.S_RESIZE_CURSOR to 22,
            Cursor.W_RESIZE_CURSOR to 25, Cursor.E_RESIZE_CURSOR to 18, Cursor.HAND_CURSOR to 4, Cursor.MOVE_CURSOR to 13,
        )
        for ((type, shape) in expected) {
            assertEquals(shape, PenCursors.shape(Cursor.getPredefinedCursor(type)), "AWT cursor type $type")
        }
        assertEquals(PenCursors.DEFAULT, PenCursors.shape(null))
    }

    @Test fun customCursorsNamedAfterCssKeywordsTakeThatShapeAndNoneHidesTheCursor() {
        assertEquals(17, PenCursors.shape(Named("grabbing")))
        assertEquals(32, PenCursors.shape(Named("all-scroll")))
        assertEquals(29, PenCursors.shape(Named("NWSE_RESIZE")), "case and underscores do not matter")
        assertEquals(28, PenCursors.shape(Named(" nesw-resize ")))
        assertEquals(PenCursors.HIDDEN, PenCursors.shape(Named("None")))
        assertEquals(PenCursors.DEFAULT, PenCursors.shape(Named("brush outline")), "an unknown name is the arrow")
        assertEquals(PenCursors.DEFAULT, PenCursors.named(null))
    }

    /** The table is cursor-shape-v1's own: every version 1 entry by its name, '-' for '_'. */
    @Test fun everyVersionOneShapeOfThePinnedProtocolHasItsCssName() {
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/test/wayland/cursor-shape-v1.xml"))
        val entries = (0 until xml.getElementsByTagName("entry").length).map { xml.getElementsByTagName("entry").item(it) as Element }
            .filter { (it.parentNode as Element).getAttribute("name") == "shape" && it.getAttribute("since").ifEmpty { "1" } == "1" }
        assertEquals(34, entries.size)
        for (entry in entries) {
            val name = entry.getAttribute("name").replace('_', '-')
            assertEquals(entry.getAttribute("value").toInt(), PenCursors.named(name), name)
            assertEquals(name, PenCursors.themeNames(entry.getAttribute("value").toInt()).first(), "theme names start with the CSS name")
        }
    }

    @Test fun themeNamesFallBackToTheDefaultArrowLast() {
        val text = PenCursors.themeNames(9)
        assertEquals(listOf("text", "xterm", "ibeam"), text.take(3))
        assertEquals(listOf("default", "left_ptr", "arrow", "top_left_arrow"), text.takeLast(4))
        assertEquals(listOf("default", "left_ptr", "arrow", "top_left_arrow"), PenCursors.themeNames(PenCursors.DEFAULT))
        assertEquals(PenCursors.themeNames(PenCursors.DEFAULT), PenCursors.themeNames(99), "an unknown shape draws the arrow")
    }
}
