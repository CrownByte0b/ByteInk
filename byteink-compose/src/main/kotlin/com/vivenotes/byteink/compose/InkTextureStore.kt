package com.vivenotes.byteink.compose

import org.jetbrains.skia.Image

/**
 * Supplies decoded textures by BrushPaint's client texture ID. Images are borrowed: the renderer
 * retains its own shader reference and never closes the supplied image. Load images before drawing.
 * A missing image makes that paint unavailable; the next compatible paint preference is tried.
 * Call InkMeshRenderer.clearCache after replacing an image associated with an existing ID.
 */
public fun interface InkTextureStore {
    public operator fun get(clientTextureId: String): Image?
}
