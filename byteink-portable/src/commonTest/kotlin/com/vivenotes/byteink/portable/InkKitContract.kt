package com.vivenotes.byteink.portable

import com.vivenotes.byteink.kit.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.*

abstract class InkKitContract {
    protected abstract suspend fun engine(): InkEngine
    protected abstract suspend fun fixture(): String
    private val line = listOf(InkSample(10f,30f,0,InkTool.MOUSE), InkSample(90f,30f,80,InkTool.MOUSE))

    @Test fun everyAndroidRowAndOperationReplaysWithOriginalRecordsIntact() = runTest {
        val engine = engine(); val kit = InkKit(engine)
        val cases = Json.parseToJsonElement(fixture()).jsonObject.getValue("cases").jsonArray
        var encoded = 0; var projections = 0; var coverageChecks = 0
        for (element in cases) {
            val case = element.jsonObject; val name = case.string("id")
            val rows = case.getValue("strokes").jsonArray.map { strokeRow(it.jsonObject) }
            val erases = case.getValue("erases").jsonArray.map { eraseRow(it.jsonObject) }
            val moves = case.getValue("moves").jsonArray.map { moveRow(it.jsonObject) }
            val expected = case.getValue("geometry").jsonObject.getValue("replayed").jsonArray.map { it.jsonObject }
            // Check the factory itself, including comments, optional fields and smoothing metadata.
            assertContentEquals(Base64.decode(case.string("familyProto")), engine.familyProto(InkStyle(
                case.string("family"), case.string("stabilization").toInt())), "$name family protobuf")
            kit.load(case.string("pageId"), rows, erases, moves).use { page ->
                assertEquals(rows, page.storedStrokes, "$name original strokes")
                assertEquals(erases, page.storedErases, "$name original erases")
                assertEquals(moves, page.storedMoves, "$name original moves")
                assertTrue(page.unreadable.isEmpty(), "$name ${page.unreadable}")
                assertTrue(page.ignoredOperations.isEmpty(), "$name ${page.ignoredOperations}")
                val known = expected.map { it.string("id") }.toSet()
                assertEquals(rows.map { it.id }.filterNot { it in known }.toSet(), page.erasedAway.toSet(), "$name erased rows")
                // Split's component order is explicitly unspecified by Ink. Preserve row order and
                // compare each row's geometric pieces by their page-space bounds.
                assertEquals(expected.map { it.string("id") }.distinct(), page.strokes.map { it.id }.distinct(), "$name draw order")
                assertEquals(expected.size, page.strokes.size, "$name projection count")
                val actual = page.strokes.sortedWith(compareBy({ it.id }, { it.pageBounds?.left }, { it.pageBounds?.top }))
                val ordered = expected.sortedWith(compareBy({ it.string("id") }, { it.getValue("pageBounds").jsonArray[0].jsonPrimitive.float },
                    { it.getValue("pageBounds").jsonArray[1].jsonPrimitive.float }))
                actual.zip(ordered).forEach { (projection, reference) ->
                    checkProjection(projection, reference, name)
                    projections++
                    coverageChecks += reference.getValue("coverage").jsonArray.size
                }
                for (source in page.sourceStrokes) {
                    val expectedBytes = Base64.decode(case.getValue("reencoded").jsonObject.getValue(source.id).jsonPrimitive.content)
                    assertContentEquals(expectedBytes, source.stroke.encodeInputs(), "$name ${source.id} Android reencoding")
                    encoded++
                }
            }
        }
        assertEquals(280, cases.size); assertEquals(840, encoded)
        println("portable-kit: Android cases=${cases.size}, encodings=$encoded, projections=$projections, coverage=$coverageChecks")
    }

    @Test fun presetsAndExplicitWritersKeepCapturedMetadata() = runTest {
        val engine = engine(); val kit = InkKit(engine)
        for (kind in PenKind.entries) for (lineType in LineType.entries) {
            val pen = PenPreset(kind, lineType, 0x80334455.toInt(), true, 3f, 9, 8)
            engine.createStroke(InkStyle.forPen(pen), line).use { stroke ->
                val row = kit.encode(stroke, "page", 3, pen, 123)
                assertEquals(123, row.createdAt); assertEquals(8, row.stabilization)
                assertTrue(row.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
                assertEquals(InkBrushCatalog.penFamilyId(lineType == LineType.Solid, kind == PenKind.Fountain, 9), row.brushFamily)
                assertEquals(0x80334455.toInt(), row.colorArgb); assertEquals(true, row.colorFollowsTheme)
                kit.decode(row)!!.use { decoded -> assertContentEquals(stroke.encodeInputs(), decoded.encodeInputs()) }
            }
        }
        val highlighter = HighlighterSettings()
        engine.createStroke(InkStyle.forHighlighter(highlighter), line).use { stroke ->
            val row = kit.encode(stroke, "page", 4, highlighter, 456)
            assertEquals(0, row.stabilization); assertEquals(false, row.colorFollowsTheme)
            assertEquals(highlighter.colorArgb, row.colorArgb)
        }
    }

    @Test fun partialEraseObjectEraseAndUndoPreserveSourcesAndProtectLaterInk() = runTest {
        val engine = engine(); val kit = InkKit(engine)
        val old = row(kit, "old", 0); val later = row(kit, "later", 1)
        val normal = kit.reloadedEraseMask(listOf(InkSample(50f,0f,0),InkSample(50f,60f,60)),10f)!!.use {
            kit.encodeErase(it, "cut", "page", InkEraseMode.Normal, 1, listOf("old")) }
        val objectErase = kit.reloadedEraseMask(listOf(InkSample(20f,30f,0)),2f)!!.use {
            kit.encodeErase(it, "remove-left", "page", InkEraseMode.Object, 2, listOf("old")) }
        kit.load("page", listOf(old,later), listOf(objectErase,normal)).use { page ->
            assertEquals(1, page.strokes.count { it.id == "old" })
            val survivor = page.strokes.first { it.id == "old" }.stroke
            assertFalse(survivor.contains(20f,30f)); assertFalse(survivor.contains(50f,30f)); assertTrue(survivor.contains(80f,30f))
            assertTrue(page.strokes.first { it.id == "later" }.stroke.contains(50f,30f))
            assertTrue(page.sourceStrokes.first { it.id == "old" }.stroke.contains(20f,30f))
            assertEquals(listOf(objectErase,normal), page.storedErases)
        }
        kit.load("page", listOf(old), listOf(normal, objectErase.copy(deletedAt=10))).use { assertEquals(2,it.strokes.size) }
        kit.load("page", listOf(old), listOf(normal.copy(deletedAt=10), objectErase.copy(deletedAt=10))).use {
            assertEquals(1,it.strokes.size); assertTrue(it.strokes.single().stroke.contains(50f,30f)) }
    }

    @Test fun highlighterDiscardIsNeverSplitByEraseReplay() = runTest {
        val engine = engine(); val kit = InkKit(engine)
        val highlight = row(kit,"highlight",0,InkStyle.forHighlighter(HighlighterSettings()))
        val erase = kit.reloadedEraseMask(listOf(InkSample(50f,0f,0),InkSample(50f,70f,70)),10f)!!.use {
            kit.encodeErase(it,"cut","page",InkEraseMode.Normal,1,listOf("highlight")) }
        kit.load("page",listOf(highlight),listOf(erase)).use { page ->
            assertEquals(1,page.strokes.size)
            assertTrue(page.strokes.single().stroke.geometry().any { it.outlines.isNotEmpty() })
            assertFalse(page.strokes.single().stroke.contains(50f,30f))
        }
    }

    @Test fun unknownAndDamagedRowsArePreservedAndForeignOperationsCannotEraseThisPage() = runTest {
        val engine = engine(); val kit = InkKit(engine); val original = row(kit,"known",0)
        val future = original.copy(id="future",enc="future/encoding",points=byteArrayOf(7,8,9))
        val damaged = original.copy(id="bad",points=byteArrayOf(1,2,3))
        val unknownFamily = original.copy(id="fallback",brushFamily="future-family",brushVersion=999,seq=1)
        val erased = kit.reloadedEraseMask(line,30f)!!.use {
            kit.encodeErase(it,"unknown-mode","page",InkEraseMode.Normal,1,listOf("known")).copy(mode="FutureMode") }
        val foreign = erased.copy(id="foreign",mode="Normal",pageId="other")
        val bytes = future.points.copyOf()
        kit.load("page",listOf(original,future,damaged,unknownFamily),listOf(erased,foreign)).use { page ->
            assertEquals(setOf("future","bad"),page.unreadable.toSet())
            assertEquals(setOf("unknown-mode","foreign"),page.ignoredOperations.toSet())
            assertEquals(listOf(original,future,damaged,unknownFamily),page.storedStrokes)
            assertTrue(page.strokes.first { it.id=="known" }.stroke.contains(50f,30f))
            val fallback = page.strokes.first { it.id=="fallback" }
            assertEquals(999,fallback.brushVersion)
            val copied = kit.encodeCopy(fallback,fallback.stroke,"copy","page",2,3)
            assertEquals("future-family",copied.brushFamily); assertEquals(999,copied.brushVersion)
        }
        assertContentEquals(bytes,future.points)
    }

    @Test fun pageAndStrokeCopiesHaveIndependentOwnedLifetimes() = runTest {
        val engine = engine(); val kit = InkKit(engine)
        val page = kit.load("page",listOf(row(kit,"stroke",0)))
        val borrowed = page.strokes.single().stroke
        val geometry = borrowed.geometry(); val kept = borrowed.copy()
        page.close(); page.close()
        assertFailsWith<IllegalStateException> { borrowed.geometry() }
        assertTrue(kept.contains(50f,30f)); assertTrue(geometry.any { it.meshes.isNotEmpty() })
        kept.close(); kept.close(); assertFailsWith<IllegalStateException> { kept.bounds }
        assertEquals(1,page.storedStrokes.size)
    }

    @Test fun replayReleasesIntermediateHandlesAndCleansUpWhenTheEngineFails() = runTest {
        val tracked = TrackingEngine(engine()); val kit = InkKit(tracked)
        val original = row(kit,"stroke",0)
        val erase = kit.reloadedEraseMask(listOf(InkSample(50f,0f,0),InkSample(50f,60f,60)),10f)!!.use {
            kit.encodeErase(it,"cut","page",InkEraseMode.Normal,1,listOf("stroke")) }
        kit.load("page", listOf(original), listOf(erase,erase.copy(id="cut-again",createdAt=2))).use { page ->
            assertEquals(2,page.strokes.size)
            assertEquals(1 + page.strokes.size, tracked.open, "Only source and final projections stay owned")
        }
        assertEquals(0,tracked.open)
        tracked.failOnId = true
        assertFailsWith<IllegalStateException> { kit.load("page", listOf(original,original.copy(id="fail",points=byteArrayOf(42)))) }
        assertEquals(0,tracked.open,"A failure must close rows decoded before it")
    }

    @Test fun savedRecordsAreSnapshotsAndEmptyMasksDoNotEraseRows() = runTest {
        val engine = engine(); val kit = InkKit(engine); val original = row(kit,"stroke",0)
        val targets = mutableListOf("stroke")
        val erase = kit.eraseMask(emptyList(),10f).use { kit.encodeErase(it,"empty","page",InkEraseMode.Normal,1,targets) }
        val bytes = original.points.copyOf()
        kit.load("page",listOf(original),listOf(erase)).use { page ->
            assertEquals(1,page.strokes.size); assertTrue(page.erasedAway.isEmpty())
            original.points.fill(0); erase.points.fill(0); targets.clear()
            assertContentEquals(bytes,page.storedStrokes.single().points)
            assertEquals(listOf("stroke"),page.storedErases.single().targetIds)
            assertTrue(page.strokes.single().stroke.contains(50f,30f))
        }
    }

    @Test fun samplesKeepStylusChannelsAndRejectInvalidValues() = runTest {
        val engine = engine(); val style = InkStyle(InkBrushCatalog.calligraphy(5),2)
        val input = listOf(InkSample(10f,20f,0,InkTool.STYLUS,.2f,.4f,.8f,.01f),
            InkSample(40f,20f,16,InkTool.STYLUS,.8f,.5f,.9f,.01f))
        engine.createStroke(style,input).use { stroke ->
            assertEquals(input,stroke.inputs())
            engine.decodeStroke(style,stroke.encodeInputs()).use { decoded ->
                assertEquals(2,decoded.inputs().size)
                assertTrue(decoded.inputs().all { it.tool==InkTool.STYLUS && it.pressure!=null && it.tiltRadians!=null && it.orientationRadians!=null })
                assertContentEquals(stroke.encodeInputs(),decoded.encodeInputs())
            }
        }
        assertFailsWith<IllegalArgumentException> { InkSample(Float.NaN,0f,0) }
        assertFailsWith<IllegalArgumentException> { InkSample(0f,0f,-1) }
        assertFailsWith<IllegalArgumentException> { InkSample(0f,0f,0,pressure=1.1f) }
        assertFailsWith<IllegalArgumentException> { engine.createStroke(style,listOf(input[1],input[0])) }
        assertFailsWith<IllegalArgumentException> { engine.createStroke(style,listOf(input[0],input[1].copy(pressure=null))) }
    }

    @Test fun drawOrderUsesUnicodeCodePointsAndOperationsRejectInvalidTransforms() = runTest {
        val engine = engine(); val kit = InkKit(engine)
        val bmp = row(kit,"\ufffd",0); val supplementary = row(kit,"\ud800\udc00",0)
        val path = listOf(InkPoint(0f,0f),InkPoint(100f,0f),InkPoint(100f,100f),InkPoint(0f,100f))
        val move = StoredInkMove("bad","page",Float.NaN,0f,points=InkMoveCodec.encodePath(path),enc=InkMoveCodec.ENCODING,
            createdAt=1,targetIds=listOf(bmp.id))
        val overflow = move.copy(id="overflow",dxDp=0f,scaleX=Float.MAX_VALUE,createdAt=2)
        kit.load("page",listOf(supplementary,bmp),moves=listOf(move,overflow)).use { page ->
            assertEquals(listOf(bmp.id,supplementary.id),page.strokes.map { it.id })
            assertEquals(listOf("bad","overflow"),page.ignoredOperations)
            assertTrue(page.strokes.all { it.transform==InkTransform() })
        }
        assertFailsWith<IllegalArgumentException> { InkTransform(scaleX=0f) }
        assertFailsWith<IllegalArgumentException> { InkStyle(size=0f) }
    }

    private fun row(kit: InkKit,id: String,seq: Int,style: InkStyle=InkStyle(size=4f)): StoredInkStroke =
        kit.engine.createStroke(style,line).use { kit.encodeStroke(it,id,"page",seq,style.familyId,style.stabilization,false,seq.toLong()) }
}

private class TrackingEngine(private val delegate: InkEngine) : InkEngine {
    var open = 0
    var failOnId = false
    override fun familyProto(style: InkStyle): ByteArray = delegate.familyProto(style)
    override fun createStroke(style: InkStyle,inputs: List<InkSample>): InkStroke = track(delegate.createStroke(style,inputs))
    override fun decodeStroke(style: InkStyle,androidInputs: ByteArray): InkStroke {
        if (failOnId && androidInputs.contentEquals(byteArrayOf(42))) error("Engine failure")
        return track(delegate.decodeStroke(style,androidInputs))
    }
    private fun track(stroke: InkStroke): InkStroke = Tracked(stroke).also { open++ }
    private inner class Tracked(val stroke: InkStroke) : InkStroke(stroke.style) {
        private var closed = false
        override val bounds: InkBounds? get() = stroke.bounds
        override fun geometry(): List<InkGeometry> = stroke.geometry()
        override fun inputs(): List<InkSample> = stroke.inputs()
        override fun encodeInputs(): ByteArray = stroke.encodeInputs()
        override fun contains(x: Float,y: Float): Boolean = stroke.contains(x,y)
        override fun coverage(box: InkBounds): Float = stroke.coverage(box)
        override fun copy(): InkStroke = track(stroke.copy())
        override fun intersects(other: InkStroke,thisToPage: InkTransform,otherToPage: InkTransform): Boolean =
            stroke.intersects((other as Tracked).stroke,thisToPage,otherToPage)
        override fun subtract(mask: InkStroke,thisToPage: InkTransform): InkStroke = track(stroke.subtract((mask as Tracked).stroke,thisToPage))
        override fun split(thisToPage: InkTransform): List<InkStroke> = stroke.split(thisToPage).map(::track)
        override fun enclosedBy(path: List<InkPoint>,thisToPage: InkTransform,edgeTolerance: Float): Boolean =
            stroke.enclosedBy(path,thisToPage,edgeTolerance)
        override fun close() { if (!closed) { closed=true; stroke.close(); open-- } }
    }
}

private fun checkProjection(actual: InkProjection, expected: JsonObject, name: String) {
    val transform = expected.getValue("transform").jsonArray.map { it.jsonPrimitive.float }
    close(floatArrayOf(actual.transform.offsetX,actual.transform.offsetY,actual.transform.scaleX,actual.transform.scaleY),transform,"$name transform")
    val bounds = actual.pageBounds!!
    close(floatArrayOf(bounds.left,bounds.top,bounds.right,bounds.bottom),expected.getValue("pageBounds").jsonArray.map { it.jsonPrimitive.float },"$name bounds")
    for (element in expected.getValue("coverage").jsonArray) {
        val probe = element.jsonObject
        val box = probe.getValue("box").jsonArray.map { it.jsonPrimitive.float }
        val coverage = actual.stroke.coverage(InkBounds(box[0],box[1],box[2],box[3]))
        assertTrue(near(coverage,probe.float("value")),"$name coverage $coverage != ${probe.float("value")}")
        assertEquals(probe.getValue("above01").jsonPrimitive.boolean,coverage>0.1f,"$name coverage > .1")
        assertEquals(probe.getValue("above50").jsonPrimitive.boolean,coverage>0.5f,"$name coverage > .5")
    }
    val groups = actual.stroke.geometry(); val golden = expected.getValue("groups").jsonArray
    assertEquals(golden.size,groups.size,"$name groups")
    groups.forEachIndexed { g, group ->
        val ref=golden[g].jsonObject
        val meshes=ref.getValue("meshes").jsonArray; val outlines=ref.getValue("outlines").jsonArray
        assertEquals(meshes.size,group.meshes.size,"$name meshes"); assertEquals(outlines.size,group.outlines.size,"$name outlines")
        group.meshes.forEachIndexed { i,mesh ->
            val reference=meshes[i].jsonObject
            assertEquals(reference.int("vertexCount")*2,mesh.positions.size,"$name vertex count")
            assertEquals(reference.int("triangleCount")*3,mesh.triangles.size,"$name triangle count")
            val unmatched=reference.getValue("positions").jsonArray.map { it.jsonArray.map { v -> v.jsonPrimitive.float } }.toMutableList()
            for (v in mesh.positions.indices step 2) {
                val index=unmatched.indexOfFirst { near(mesh.positions[v],it[0]) && near(mesh.positions[v+1],it[1]) }
                assertTrue(index>=0,"$name missing vertex ${mesh.positions[v]},${mesh.positions[v+1]}")
                unmatched.removeAt(index)
            }
            for (index in mesh.triangles) assertTrue(index in 0 until mesh.positions.size/2,"$name invalid triangle")
        }
        group.outlines.forEachIndexed { i,outline ->
            val flat=outlines[i].jsonArray.flatMap { it.jsonArray.map { value -> value.jsonPrimitive.float } }
            assertEquals(flat.size,outline.size,"$name outline count")
            val offset=(flat.indices step 2).firstOrNull { start -> outline.indices.all { v -> near(outline[v],flat[(v+start)%flat.size]) } }
            assertNotNull(offset,"$name closed boundary differs")
        }
    }
}

private fun near(a: Float,b: Float): Boolean = abs(a.toDouble()-b) <= 1e-4 + abs(b.toDouble())*1e-6
private fun close(actual: FloatArray,expected: List<Float>,name: String) {
    assertEquals(expected.size,actual.size,name)
    actual.indices.forEach { assertTrue(near(actual[it],expected[it]),"$name[$it]: ${actual[it]} != ${expected[it]}") }
}
private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.int
private fun JsonObject.float(name: String): Float = getValue(name).jsonPrimitive.float
private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.long
private fun JsonObject.nullString(name: String): String? = getValue(name).jsonPrimitive.contentOrNull
private fun JsonObject.deleted(): Long? = getValue("deletedAt").jsonPrimitive.longOrNull
private fun JsonObject.targets(): List<String> = getValue("targetIds").jsonArray.map { it.jsonPrimitive.content }
private fun strokeRow(o: JsonObject): StoredInkStroke = StoredInkStroke(o.string("id"),o.string("pageId"),o.int("seq"),
    o.string("brushFamily"),o.int("brushVersion"),o.float("sizeDp"),o.int("colorArgb"),
    o["colorFollowsTheme"]!!.jsonPrimitive.intOrNull?.let { it!=0 },o.float("epsilon"),o.int("stabilization"),
    o.float("minX"),o.float("minY"),o.float("maxX"),o.float("maxY"),Base64.decode(o.string("points")),o.string("enc"),
    o.long("createdAt"),o.nullString("groupId"),o.deleted())
private fun eraseRow(o: JsonObject): StoredInkErase = StoredInkErase(o.string("id"),o.string("pageId"),o.string("mode"),
    o.float("sizeDp"),Base64.decode(o.string("points")),o.string("enc"),o.long("createdAt"),o.deleted(),o.targets())
private fun moveRow(o: JsonObject): StoredInkMove = StoredInkMove(o.string("id"),o.string("pageId"),o.float("dxDp"),o.float("dyDp"),
    o.float("scaleX"),o.float("scaleY"),o.float("anchorX"),o.float("anchorY"),Base64.decode(o.string("points")),
    o.string("enc"),o.long("createdAt"),o.deleted(),o.targets())
