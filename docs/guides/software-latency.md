# Software latency diagnostics

`InkLowLatencyPanel.latencyDiagnostics` and the `InkLowLatencySurface` parameter
enable bounded observations of the direct desktop authoring path. They default
to `null`. No touch screen or stylus is required to implement or test them: the
native-window suites inject synthetic input through the actual adapters.

These timings describe software work. Device sampling, driver/toolkit delivery
before our capture boundary, deferred submission, compositor presentation and
physical screen response remain unmeasured. JSON exports explicitly leave
`presentationCompletionNanos` and `physicalPenToPhotonNanos` null.

## Capture and export

Use a distinct `InkLatencyDiagnostics` per panel. Construct, attach, clear and
snapshot it on AWT EDT. A snapshot is immutable and can be serialized/written by
the application's worker. Do not write a file in the input or drawing callback.

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/SoftwareLatency.kt"
```

The default capacity is 512 records **per ring**; valid capacities are 1–65,536.
The input and frame rings independently overwrite their oldest observations.
Snapshots expose overwrite counts, so a long run cannot silently appear to be
complete. Exported median/P95/P99 statistics describe retained records, use
nearest-rank percentiles, and do not weight a merged packet by its sample count.
An even median rounds down to a nanosecond. Failed frames are excluded from
statistics. Empty/unavailable metrics are null rather than zero.

The collector retains no coordinates, pressure, stroke content or native handles.
Its capacity bounds completed observations; pending packets remain in the
existing lossless native input queue. Instrumentation adds clock reads and
allocations. Keep diagnostics disabled for ordinary use, and measure its overhead
against a disabled control before using a capture for a performance claim.

## Measurement boundaries

All elapsed times use differences of the same JVM's monotonic
[`System.nanoTime`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#nanoTime()).
Device event ticks and `InkPointerSample.uptimeMillis` are used by the stroke
engine, never subtracted from JVM nanoseconds. The nanoTime origin may be negative.

| Observation | Boundary and included work |
| --- | --- |
| `oldestQueueWaitNanos`, `newestQueueWaitNanos` | Our native queue enqueue to merged consumer entry on EDT. Preserves the oldest/newest arrival and original packet count across merging. Excludes device sampling and native decoding before enqueue. |
| `normalizationNanos` | EDT coordinate, axis and event-time mapping before listener delivery. Available for native queued input. |
| `handlingNanos` | Grouped listener handling: session input/modeling, prediction work, canonical finish, host completion callback and render scheduling. |
| `renderQueueNanos` | First coalesced render request enqueue to Skia delegate entry. Null for incidental paints and invalidated request tickets. |
| `engineAdvanceNanos` | Scheduled session advances since the previous delegate, including animation timers. Begin/finish modeling belongs to handling. |
| `drawNanos` | Renderer preparation and background/live drawing. `SWING_SOFTWARE` includes software raster work; `SKIA_LAYER` measures recording on the heavyweight backend. |
| `swingTransferAndDrawNanos` | Delegate return to `SkiaSwingLayer.paint` return: Skiko readPixels, native-to-JVM copy, Java2D drawImage and cleanup together. Null on SkiaLayer. |
| `frameRequestNanos` | Complete synchronous `paintImmediately`/`renderImmediately` call. On Swing this also includes ancestor-buffer/client request work outside the layer's paint. Null for incidental paints. |
| `oldestInputToDrawNanos`, `newestInputToDrawNanos` | Supplied input age at delegate drawing/recording completion. |
| `oldestInputToRequestReturnNanos`, `newestInputToRequestReturnNanos` | Supplied input age when the immediate paint request returns. Includes queueing when native arrival is known. |

Durations overlap: do not add them into a supposed end-to-end total.
`completed` means the observed drawing and enclosing synchronous scopes returned
normally; it does not establish presentation completion or success.
The pinned [SkiaSwingLayer](https://github.com/JetBrains/skiko/blob/v0.150.1/skiko/src/awtMain/kotlin/org/jetbrains/skiko/swing/SkiaSwingLayer.kt),
[software redrawer](https://github.com/JetBrains/skiko/blob/v0.150.1/skiko/src/awtMain/kotlin/org/jetbrains/skiko/swing/SoftwareSwingRedrawer.kt)
and [software painter](https://github.com/JetBrains/skiko/blob/v0.150.1/skiko/src/awtMain/kotlin/org/jetbrains/skiko/swing/SoftwareSwingPainter.kt)
define the transfer boundary. The diagnostics do not replace Skiko or force a
toolkit flush/commit.

`NATIVE_QUEUE` input ages begin at our native queue. A supplied custom
`InkInputSource` starts at `LISTENER` delivery; its unavailable queue and
normalization times stay null. `MIXED` marks a frame containing different start
boundaries. Native AWT mouse queueing starts in our mouse callback and excludes
earlier AWT delivery. Queue timing begins after a native frame has been decoded
and owned, rather than at the original hardware observation.

Input `packetCount` counts original merged queue entries; `eventCount` counts
normalized callbacks. Frame `inputEventCount` and `realSampleCount` count supplied
input since the previous delegate, including ignored observations. Predictions
are excluded from real sample counts. These counters do not assert that every
sample produced geometry or became visible. Coalesced frames retain oldest and
newest ages. Repaints without new input have null input ages.

Clear, diagnostics replacement, disable, detach and close invalidate in-flight
attribution. Clear also invalidates already captured queue timestamps; packets
that survive a clear can still be delivered, with unknown queue timing. Completed
observations remain available until clear or ring eviction. The existing
`lastInputToRenderNanos` keeps its narrower handler-to-delegate semantics.
Compare captures with matching input origins and render paths; use raw records
to separate mixed boundaries before making timing comparisons.

## Reproduce without a tablet

Run the deterministic queue/clock/lifecycle tests:

```sh
./gradlew :byteink-compose:test --tests '*InkLatencyDiagnosticsTest' --tests '*NativeInkPacketQueueTest'
```

For native Wayland, use JBR 25 and the same private compositor fixture as the
[authoring verification](authoring.md). The synthetic tablet case compares
diagnostics off/on with 34 real observations per trace, forecasts disabled, and
exact finished pixels. It captures the normal immediate request without a
test-only commit or physical device:

```sh
bash byteink-compose/src/test/wayland/run.sh -PbyteinkTestJavaHome=/absolute/path/to/jbr25
BYTEINK_WAYLAND_TEST_SCALE=2 bash byteink-compose/src/test/wayland/run.sh -PbyteinkTestJavaHome=/absolute/path/to/jbr25
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a ./gradlew :byteink-compose:desktopPenTest -PbyteinkTestJavaHome=/absolute/path/to/jbr25
```

JSON raw records and percentiles are written under
`byteink-compose/build/reports/software-latency/`. Wayland report names include
scale; the desktop report labels its custom 32-event listener burst. Reports
identify synthetic input and leave physical/presentation timing unavailable.
These small diagnostic captures verify the instrumentation and are not
steady-state latency benchmarks or competitor comparisons. Xvfb and headless
Weston do not reproduce a physical display's scheduling.

Measuring physical contact-to-visible-mark or moving-pen-to-ink lag still needs a
real pen/tablet/display and an external timing method such as a high-speed camera,
with capture resolution and uncertainty recorded. See the
[benchmark measurement program](benchmarks.md#evidence-required-for-each-claim).

[Full API reference](../reference/authoring.md#inklatencydiagnostics)
