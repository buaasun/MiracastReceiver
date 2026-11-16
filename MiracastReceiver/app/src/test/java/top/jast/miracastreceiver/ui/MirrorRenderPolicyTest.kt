package top.jast.miracastreceiver.ui

import org.junit.Assert.*
import org.junit.Test

class MirrorRenderPolicyTest {
    private fun call(name: String, vararg args: Any?): Any? {
        val owner = try {
            Class.forName("top.jast.miracastreceiver.ui.MirrorRenderPolicyKt")
        } catch (_: ClassNotFoundException) {
            fail("缺少兼容渲染策略，不能在0度镜像时选择TextureView")
            throw AssertionError()
        }
        val functions = owner.declaredMethods.filter { it.name == name }
        assertEquals("渲染策略入口必须唯一", 1, functions.size)
        return functions.single().invoke(null, *args)
    }

    private fun output(source: MirrorSurfaceSource?, normal: Any?, texture: Any?,
                       rotation: Int = 0, crop: Float = 1f, compatibility: Boolean = false): Any? =
        call("mirrorOutputSurface", source, normal, texture, rotation, crop, compatibility)

    @Test fun compatibilityUsesTextureAtZeroDegreesForMiracast() {
        val normal = Any()
        val texture = Any()
        assertEquals(true, call("mirrorNeedsTexture", MirrorSurfaceSource.MIRACAST, 0, 1f, true))
        assertSame(texture, output(MirrorSurfaceSource.MIRACAST, normal, texture, compatibility = true))
    }

    @Test fun ordinaryMiracastKeepsSurfaceViewAtZeroDegrees() {
        val normal = Any()
        assertSame(normal, output(MirrorSurfaceSource.MIRACAST, normal, Any()))
    }

    @Test fun compatibilityDoesNotChangeAirPlayRendering() {
        val normal = Any()
        assertEquals(false, call("mirrorNeedsTexture", MirrorSurfaceSource.AIRPLAY, 0, 1f, true))
        assertSame(normal, output(MirrorSurfaceSource.AIRPLAY, normal, Any(), compatibility = true))
    }

    @Test fun unreadyTextureKeepsTheExistingSurfaceInsteadOfPublishingNull() {
        val normal = Any()
        assertSame(normal, output(MirrorSurfaceSource.MIRACAST, normal, null, compatibility = true))
        assertSame(normal, output(MirrorSurfaceSource.AIRPLAY, normal, null, rotation = 90))
    }

    @Test fun rotationAndCropStillUseTextureWithoutCompatibility() {
        val texture = Any()
        for (source in MirrorSurfaceSource.values()) {
            assertSame(texture, output(source, Any(), texture, rotation = 90))
            assertSame(texture, output(source, Any(), texture, crop = 0.8f))
        }
    }

    @Test fun missingMirrorSessionNeverPublishesAnOutput() {
        assertNull(output(null, Any(), Any(), rotation = 90, compatibility = true))
    }

    @Test fun absentSurfacesRemainUnavailable() {
        assertNull(output(MirrorSurfaceSource.MIRACAST, null, null, compatibility = true))
    }
}
