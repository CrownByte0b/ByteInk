package com.vivenotes.byteink.portable

import com.vivenotes.byteink.kit.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.test.*
import kotlin.time.TimeSource

/** Verification-only wire envelope, not a library storage format. Both runtimes exchange files. */
abstract class RoundTripContract {
    protected abstract val phase: String
    protected abstract val runtimeDescription: String
    protected abstract suspend fun engine(): InkEngine
    protected abstract suspend fun read(name: String): String
    protected abstract suspend fun write(name: String, text: String)
    protected open fun additionalRows(kit: InkKit): List<StoredInkStroke> = emptyList()

    @Test fun storedRowsAndOperationsCrossRuntime() = runTest {
        val kit = InkKit(engine())
        when (phase) {
            "seed" -> {
                val data = authored(kit,"jvm")
                write("jvm.json", envelope(kit,data,"jvm").toString())
                write("jvm-performance.json", performance(kit).toString())
            }
            "wasm" -> {
                val seed = Json.parseToJsonElement(read("jvm.json")).jsonObject
                assertEquals("jvm",seed.getValue("origin").jsonPrimitive.content)
                val original = records(seed)
                verify(kit,original,seed)
                val authored = authored(kit,"wasm")
                val bridgeMove = kit.encodeMove(InkLassoMove(lasso, setOf("jvm-1","wasm-1"),emptySet(),-3f,8f),
                    "wasm-moves-both-origins",pageId,clock+3000)
                val combined = Records(original.strokes+authored.strokes+additionalRows(kit),
                    original.erases+authored.erases, original.moves+authored.moves+bridgeMove)
                val returned = JsonObject(envelope(kit,combined,"wasm")+
                    ("opaqueApplicationData" to seed.getValue("opaqueApplicationData")))
                assertEquals(seed.getValue("opaqueApplicationData"),returned.getValue("opaqueApplicationData"))
                write("wasm.json",returned.toString())
                write("wasm-performance.json",performance(kit).toString())
            }
            "return" -> {
                val seedEnvelope = Json.parseToJsonElement(read("jvm.json")).jsonObject
                val seed = records(seedEnvelope)
                val returned = Json.parseToJsonElement(read("wasm.json")).jsonObject
                assertEquals("wasm",returned.getValue("origin").jsonPrimitive.content)
                val data = records(returned)
                assertEquals(seedEnvelope.getValue("opaqueApplicationData"),returned.getValue("opaqueApplicationData"))
                assertEquals(seed.strokes,data.strokes.take(seed.strokes.size),"All original JVM fields and blobs")
                assertEquals(seed.erases,data.erases.take(seed.erases.size),"Original erase rows")
                assertEquals(seed.moves,data.moves.take(seed.moves.size),"Original move rows")
                assertTrue(data.strokes.any { it.id.startsWith("wasm-") })
                verify(kit,data,returned)
                write("accepted.json",json("direction" to "JVM -> Wasm -> JVM", "strokes" to data.strokes.size,
                    "erases" to data.erases.size,"moves" to data.moves.size,
                    "states" to returned.getValue("states").jsonArray.size,"originalBlobsPreserved" to true).toString())
            }
            "local" -> {
                val data = authored(kit,"local")
                verify(kit,data,Json.parseToJsonElement(envelope(kit,data,"local").toString()).jsonObject)
            }
            else -> error("Unknown round-trip phase: $phase")
        }
        println("PORTABLE_ROUND_TRIP_PASS $phase")
    }

    private data class Records(val strokes: List<StoredInkStroke>,val erases: List<StoredInkErase>,val moves: List<StoredInkMove>)
    private val pageId = "exchange"
    // Above JavaScript's exact-integer range: Kotlin JSON/Long storage must preserve this too.
    private val clock = 9007199254740995L
    private val lasso = listOf(InkPoint(-100f,-100f),InkPoint(600f,-100f),InkPoint(600f,800f),InkPoint(-100f,800f))

    private fun authored(kit: InkKit,prefix: String): Records {
        val families = listOf(InkBrushCatalog.MARKER,InkBrushCatalog.PRESSURE_PEN,InkBrushCatalog.DASHED_LINE,
            InkBrushCatalog.HIGHLIGHTER)+(0..5).map(InkBrushCatalog::calligraphy)
        val rows = families.mapIndexed { i,family ->
            val y = 30f+i*40
            val samples = List(9) { j -> InkSample(10f+j*12,y+j%2,j*7L,InkTool.STYLUS,
                .1f+j*.1f,.3f+j*.01f,.8f+j*.02f,.01f) }
            kit.engine.createStroke(InkStyle(family,i%6,0x805070c0.toInt(),12f),samples).use { stroke ->
                kit.encodeStroke(stroke,"$prefix-$i",pageId,i,family,i%6,if(i%2==0) null else true,
                    clock+i,if(i%2==0) "group-$prefix" else null)
            }
        }.toMutableList()
        rows += rows[0].copy(id="$prefix-tombstone",deletedAt=clock+100,brushVersion=77)
        rows += rows[1].copy(id="$prefix-future-family",seq=20,brushFamily="future-family",brushVersion=999)
        rows += rows[0].copy(id="$prefix-opaque",enc="future/encoding",points=byteArrayOf(-1,0,7,23),seq=21)
        rows += rows[0].copy(id="$prefix-damaged",points=byteArrayOf(1,2,3),seq=22)
        fun erase(id: String,mode: InkEraseMode,x: Float,from: Float,to: Float,time: Long): StoredInkErase =
            kit.eraseMask(listOf(InkSample(x,from,0),InkSample(x,to,20)),10f).use {
                kit.encodeErase(it,"$prefix-$id",pageId,mode,time,listOf("$prefix-0")) }
        val erases = listOf(erase("cut",InkEraseMode.Normal,58f,0f,60f,clock+100),
            erase("object",InkEraseMode.Object,20f,25f,35f,clock+101)).let { it+it[0].copy(id="$prefix-future-op",mode="FutureMode") }
        val targets = rows.map { it.id }.toSet()
        val move = kit.encodeMove(InkLassoMove(lasso,targets,emptySet(),7f,11f),"$prefix-move",pageId,clock+102)
        val resize = kit.encodeResize(InkLassoResize(lasso,targets,emptySet(),InkPoint(5f,10f),1.25f,.75f),
            "$prefix-resize",pageId,clock+103)
        return Records(rows,erases,listOf(move,resize,move.copy(id="$prefix-undone",deletedAt=clock+200)))
    }

    private fun states(data: Records): List<Records> = listOf(data,
        data.copy(erases=data.erases.map { it.copy(deletedAt=clock+5000) },moves=data.moves.map { it.copy(deletedAt=clock+5000) }))+
        data.erases.filter { it.deletedAt==null }.map { erase -> data.copy(erases=data.erases.map {
            if(it.id==erase.id) it.copy(deletedAt=clock+5000) else it }) }+
        data.moves.filter { it.deletedAt==null }.map { move -> data.copy(moves=data.moves.map {
            if(it.id==move.id) it.copy(deletedAt=clock+5000) else it }) }

    private fun snapshot(kit: InkKit,data: Records): JsonObject = kit.load(pageId,data.strokes,data.erases,data.moves).use { page ->
        assertEquals(data.strokes,page.storedStrokes); assertEquals(data.erases,page.storedErases); assertEquals(data.moves,page.storedMoves)
        page.sourceStrokes.forEach { source ->
            assertContentEquals(data.strokes.single { it.id==source.id }.points,source.stroke.encodeInputs(),"${source.id} input bytes")
        }
        json("unreadable" to page.unreadable,"ignored" to page.ignoredOperations,"erased" to page.erasedAway,
            "projections" to page.strokes.sortedWith(compareBy({it.id},{it.pageBounds?.left},{it.pageBounds?.top})).map { p ->
                json("id" to p.id,"version" to p.brushVersion,"theme" to p.colorFollowsTheme,"group" to p.groupId,
                    "transform" to listOf(p.transform.scaleX,p.transform.scaleY,p.transform.offsetX,p.transform.offsetY),
                    "bounds" to p.pageBounds?.let { listOf(it.left,it.top,it.right,it.bottom) },
                    "coverage" to listOf(InkBounds(0f,0f,40f,60f),InkBounds(40f,0f,80f,60f),InkBounds(-50f,-50f,500f,700f)).map(p.stroke::coverage),
                    "groups" to p.stroke.geometry().map { g -> json("outlines" to g.outlines,
                        "meshes" to g.meshes.map { m -> json("positions" to m.positions,"triangles" to m.triangles) }) })
            })
    }

    private fun envelope(kit: InkKit,data: Records,origin: String): JsonObject = json("origin" to origin,
        "strokes" to data.strokes.map(::strokeJson),"erases" to data.erases.map(::eraseJson),"moves" to data.moves.map(::moveJson),
        "opaqueApplicationData" to json("unknownColumn" to "kept by application envelope", "bytes" to "AAH/", "clock" to clock),
        "states" to states(data).map { snapshot(kit,it) })

    private fun records(value: JsonObject): Records = Records(value.getValue("strokes").jsonArray.map { strokeRow(it.jsonObject) },
        value.getValue("erases").jsonArray.map { eraseRow(it.jsonObject) },value.getValue("moves").jsonArray.map { moveRow(it.jsonObject) })

    private fun verify(kit: InkKit,data: Records,value: JsonObject) {
        assertEquals(data,records(value),"Every DTO field and original blob survives the verification envelope")
        val actual = states(data).map { snapshot(kit,it) }; val expected = value.getValue("states").jsonArray
        assertEquals(expected.size,actual.size)
        actual.indices.forEach { compare(actual[it],expected[it],"state[$it]") }
        assertNotEquals(actual[0],actual[1],"Undo must change projected geometry")
    }

    private fun compare(actual: JsonElement,expected: JsonElement,path: String) {
        when {
            actual is JsonObject && expected is JsonObject -> { assertEquals(expected.keys,actual.keys,path)
                actual.forEach { (key,value) -> if(key=="triangles") assertEquals(expected.getValue(key),value,"$path.$key")
                    else compare(value,expected.getValue(key),"$path.$key") } }
            actual is JsonArray && expected is JsonArray -> { assertEquals(expected.size,actual.size,path)
                actual.indices.forEach { compare(actual[it],expected[it],"$path[$it]") } }
            actual is JsonPrimitive && expected is JsonPrimitive && actual.floatOrNull!=null && expected.floatOrNull!=null -> {
                val a=actual.double; val e=expected.double
                assertTrue(abs(a-e)<=.0001+abs(e)*.000001,"$path: $a != $e")
            }
            else -> assertEquals(expected,actual,path)
        }
    }

    /** Same deterministic public-API workload, warm-ups excluded; no timing pass/fail threshold. */
    private fun performance(kit: InkKit): JsonObject {
        val style=InkStyle(InkBrushCatalog.PRESSURE_PEN,2,size=6f)
        val samples=List(120) { i -> InkSample(i.toFloat(),30f+(i%17),i*4L,InkTool.STYLUS,.1f+(i%9)*.1f) }
        val encoded=kit.engine.createStroke(style,samples).use { it.encodeInputs() }
        fun measure(block:()->Unit): JsonObject {
            repeat(30) { block() }
            val values=List(100) { val start=TimeSource.Monotonic.markNow();block();start.elapsedNow().inWholeNanoseconds/1e6 }.sorted()
            return json("p50Millis" to values[49],"p95Millis" to values[94],"samplesMillis" to values)
        }
        val data=authored(kit,"perf")
        return json("runtime" to runtimeDescription,"warmups" to 30,"iterations" to 100,"inputSamples" to samples.size,
            "createAndGeometry" to measure { kit.engine.createStroke(style,samples).use { it.geometry() } },
            "decodeAndEncode" to measure { kit.engine.decodeStroke(style,encoded).use { assertContentEquals(encoded,it.encodeInputs()) } },
            "replay" to measure { kit.load(pageId,data.strokes,data.erases,data.moves).use { check(it.strokes.isNotEmpty()) } })
    }
}

private fun element(value: Any?): JsonElement = when(value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is ByteArray -> JsonPrimitive(Base64.encode(value))
    is FloatArray -> JsonArray(value.map(::JsonPrimitive))
    is IntArray -> JsonArray(value.map(::JsonPrimitive))
    is Iterable<*> -> JsonArray(value.map(::element))
    else -> error("Unsupported verification value: $value")
}
private fun json(vararg values: Pair<String,Any?>): JsonObject = JsonObject(values.associate { it.first to element(it.second) })
private fun strokeJson(r: StoredInkStroke): JsonObject = json("id" to r.id,"pageId" to r.pageId,"seq" to r.seq,
    "brushFamily" to r.brushFamily,"brushVersion" to r.brushVersion,"sizeDp" to r.sizeDp,"colorArgb" to r.colorArgb,
    "colorFollowsTheme" to r.colorFollowsTheme?.let { if(it) 1 else 0 },"epsilon" to r.epsilon,"stabilization" to r.stabilization,
    "minX" to r.minX,"minY" to r.minY,"maxX" to r.maxX,"maxY" to r.maxY,"points" to r.points,"enc" to r.enc,
    "createdAt" to r.createdAt,"groupId" to r.groupId,"deletedAt" to r.deletedAt)
private fun eraseJson(r: StoredInkErase): JsonObject = json("id" to r.id,"pageId" to r.pageId,"mode" to r.mode,
    "sizeDp" to r.sizeDp,"points" to r.points,"enc" to r.enc,"createdAt" to r.createdAt,"deletedAt" to r.deletedAt,"targetIds" to r.targetIds)
private fun moveJson(r: StoredInkMove): JsonObject = json("id" to r.id,"pageId" to r.pageId,"dxDp" to r.dxDp,"dyDp" to r.dyDp,
    "scaleX" to r.scaleX,"scaleY" to r.scaleY,"anchorX" to r.anchorX,"anchorY" to r.anchorY,"points" to r.points,
    "enc" to r.enc,"createdAt" to r.createdAt,"deletedAt" to r.deletedAt,"targetIds" to r.targetIds)
