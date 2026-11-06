package com.vivenotes.byteink.consumer

import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.portable.*

/** Exercises the preview facade through Maven metadata and through includeBuild. */
fun main() {
    val engine = JvmInkEngine()
    val kit = InkKit(engine)
    val pen = PenPreset(kind = PenKind.Fountain, thickness = 4f)
    val row = engine.createStroke(InkStyle.forPen(pen), listOf(InkSample(10f,30f,0),InkSample(90f,30f,80))).use {
        kit.encode(it,"page",0,pen,1)
    }
    val erase = kit.reloadedEraseMask(listOf(InkSample(50f,0f,0),InkSample(50f,60f,60)),10f)!!.use {
        kit.encodeErase(it,"cut","page",InkEraseMode.Normal,2,listOf(row.id))
    }
    val path = listOf(InkPoint(0f,0f),InkPoint(48f,0f),InkPoint(48f,65f),InkPoint(0f,65f))
    val move = kit.encodeMove(InkLassoMove(path,setOf(row.id),emptySet(),20f,10f),"move","page",3)
    val unknown = row.copy(id="future",enc="future/encoding",points=byteArrayOf(42))
    val saved = row.points.copyOf()
    val held = kit.load("page",listOf(row,unknown),listOf(erase),listOf(move)).use { page ->
        check(page.strokes.size==2 && page.unreadable==listOf("future"))
        check(page.strokes.count { it.transform.offsetX==20f && it.transform.offsetY==10f }==1)
        check(page.storedStrokes[0].points.contentEquals(saved) && page.storedStrokes[1]==unknown)
        page.strokes[0].stroke.copy()
    }
    held.use {
        check(it.geometry().any { group -> group.meshes.isNotEmpty() })
        check(it.coverage(checkNotNull(it.bounds)) > .99f)
    }
    kit.load("page",listOf(row),listOf(erase.copy(deletedAt=4)),listOf(move.copy(deletedAt=4))).use {
        check(it.strokes.single().stroke.contains(50f,30f))
    }
    println("byteink-portable-consumer: passed")
}
