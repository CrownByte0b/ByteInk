package wiki

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.geometry.AffineTransform
import com.vivenotes.byteink.compose.InkMeshRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.automaticColorOr
import com.vivenotes.byteink.kit.automaticInkFor
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

fun renderInk(page: List<PageStroke>, output: File): Int {
    val scene = InkScene(page.map { projection ->
        InkSceneStroke(
            stroke = projection.stroke,
            strokeToScene = projection.strokeToPageTransform(),
            colorArgb = automaticColorOr(
                projection.stroke.brush.colorIntArgb,
                projection.colorFollowsTheme,
                automaticInkFor(isDark = false),
            ),
        )
    })
    val renderer = InkMeshRenderer()
    try {
        InkSceneRasterCache(cacheCapacity = 3, pixelBudgetBytes = 64L * 1024 * 1024).use { cache ->
            Surface.makeRasterN32Premul(256, 256).use { surface ->
                surface.canvas.clear(0xffffffff.toInt())
                val drawn = cache.draw(
                    canvas = surface.canvas.asComposeCanvas(),
                    scene = scene,
                    renderer = renderer,
                    viewport = Rect(0f, 0f, 256f, 256f),
                    sceneToCanvas = AffineTransform.IDENTITY,
                    rasterScale = 1f,
                )
                surface.makeImageSnapshot().use { image ->
                    requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                        output.absoluteFile.parentFile.mkdirs()
                        output.writeBytes(data.bytes)
                    }
                }
                return drawn
            }
        }
    } finally {
        renderer.close()
    }
}
