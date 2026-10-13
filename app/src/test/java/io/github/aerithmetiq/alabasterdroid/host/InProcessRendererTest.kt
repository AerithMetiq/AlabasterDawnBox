package io.github.aerithmetiq.alabasterdroid.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InProcessRendererTest {

    @Test
    fun `a library folder names its ABI's word size`() {
        assertEquals(true, InProcessRenderer.is32BitLibDir("/product/app/webview/lib/arm"))
        assertEquals(true, InProcessRenderer.is32BitLibDir("/system/app/webview/lib/x86/"))
        assertEquals(false, InProcessRenderer.is32BitLibDir("/data/app/~~x/com.google.android.webview-y/lib/arm64"))
        assertEquals(false, InProcessRenderer.is32BitLibDir("/system/app/WebViewGoogle/lib/x86_64"))
    }

    @Test
    fun `a folder that does not say is not guessed at`() {
        assertNull(InProcessRenderer.is32BitLibDir(null))
        assertNull(InProcessRenderer.is32BitLibDir("/product/app/webview/lib"))
    }
}
