package top.jast.miracastreceiver.ui

internal fun mirrorNeedsTexture(source: MirrorSurfaceSource?, rotationDegrees: Int,
                                cropWidth: Float, compatibilityMode: Boolean): Boolean =
    source != null && (rotationDegrees != 0 || cropWidth < 0.99f ||
        (source == MirrorSurfaceSource.MIRACAST && compatibilityMode))

/** 兼容模式初次等待Texture；过渡时只保留调用方核验过的已发布Surface。 */
internal fun <T : Any> mirrorOutputSurface(source: MirrorSurfaceSource?, surfaceView: T?, textureView: T?,
                                         rotationDegrees: Int, cropWidth: Float, compatibilityMode: Boolean,
                                         published: T? = null): T? =
    when {
        source == null -> null
        textureView != null && mirrorNeedsTexture(source, rotationDegrees, cropWidth, compatibilityMode) -> textureView
        source == MirrorSurfaceSource.MIRACAST && compatibilityMode -> published
        else -> surfaceView
    }
