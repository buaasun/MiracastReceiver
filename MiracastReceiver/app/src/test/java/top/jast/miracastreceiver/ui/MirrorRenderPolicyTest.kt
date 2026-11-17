package top.jast.miracastreceiver.ui

import org.junit.Assert.*
import org.junit.Test

class MirrorRenderPolicyTest {
    private fun owner(): Class<*> = try {
        Class.forName("top.jast.miracastreceiver.ui.MirrorRenderPolicyKt")
    } catch (_: ClassNotFoundException) {
        fail("缺少兼容渲染策略，不能在0度镜像时选择TextureView")
        throw AssertionError()
    }

    private fun call(name: String, vararg args: Any?): Any? {
        val functions = owner().declaredMethods.filter { it.name == name }
        assertEquals("渲染策略入口必须唯一", 1, functions.size)
        return functions.single().invoke(null, *args)
    }

    private fun output(source: MirrorSurfaceSource?, normal: Any?, texture: Any?,
                       rotation: Int = 0, crop: Float = 1f, compatibility: Boolean = false,
                       published: Any? = null): Any? {
        // 旧实现仍能编译，用真实返回值暴露初始绑定错误，而不是只检查新API存在。
        val method = owner().declaredMethods.single { it.name == "mirrorOutputSurface" }
        return if (method.parameterCount == 7) {
            call("mirrorOutputSurface", source, normal, texture, rotation, crop, compatibility, published)
        } else {
            call("mirrorOutputSurface", source, normal, texture, rotation, crop, compatibility)
        }
    }

    @Test fun compatibilityUsesTextureAtZeroDegreesForMiracast() {
        val normal = Any()
        val texture = Any()
        assertEquals(true, call("mirrorNeedsTexture", MirrorSurfaceSource.MIRACAST, 0, 1f, true))
        assertSame(texture, output(MirrorSurfaceSource.MIRACAST, normal, texture, compatibility = true))
    }

    @Test fun initialCompatibilityWaitsForTextureInsteadOfInitializingSurfaceView() {
        assertNull(output(MirrorSurfaceSource.MIRACAST, Any(), null, compatibility = true))
    }

    @Test fun compatibilityKeepsOnlyTheAlreadyPublishedSurfaceWhileTextureIsUnready() {
        val published = Any()
        assertSame(published, output(MirrorSurfaceSource.MIRACAST, Any(), null,
            compatibility = true, published = published))
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
        assertSame(normal, output(MirrorSurfaceSource.MIRACAST, normal, null,
            compatibility = true, published = normal))
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
        assertNull(output(null, Any(), Any(), rotation = 90, compatibility = true, published = Any()))
    }

    @Test fun absentSurfacesRemainUnavailable() {
        assertNull(output(MirrorSurfaceSource.MIRACAST, null, null, compatibility = true))
    }
}
