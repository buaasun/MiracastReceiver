package top.jast.miracastreceiver.ui

internal fun mirrorNeedsTexture(source: MirrorSurfaceSource?, rotationDegrees: Int,
                                cropWidth: Float, compatibilityMode: Boolean): Boolean =
    source != null && (rotationDegrees != 0 || cropWidth < 0.99f ||
        (source == MirrorSurfaceSource.MIRACAST && compatibilityMode))

/** Texture尚未就绪时保留旧Surface，避免把短暂视图切换误当成输出丢失。 */
internal fun <T : Any> mirrorOutputSurface(source: MirrorSurfaceSource?, surfaceView: T?, textureView: T?,
                                         rotationDegrees: Int, cropWidth: Float, compatibilityMode: Boolean): T? =
    when {
        source == null -> null
        textureView != null && mirrorNeedsTexture(source, rotationDegrees, cropWidth, compatibilityMode) -> textureView
        else -> surfaceView
    }
