package cc.hafa.reader.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class AvatarTest {
    @Test
    fun `a Google photo is asked for at the size shown`() {
        assertEquals("https://lh3.googleusercontent.com/a/abc=s88-c", avatarPhotoAddress("https://lh3.googleusercontent.com/a/abc=s96-c", 88))
        assertEquals("https://lh3.googleusercontent.com/a/abc=s88-c", avatarPhotoAddress("https://lh3.googleusercontent.com/a/abc=s96", 88))
        assertEquals("https://lh3.googleusercontent.com/a/abc=s88-c", avatarPhotoAddress("https://lh3.googleusercontent.com/a/abc", 88))
    }

    @Test
    fun `other photos are left as they are`() {
        assertEquals("https://example.com/me.png", avatarPhotoAddress("https://example.com/me.png", 88))
        assertEquals("https://lh3.googleusercontent.com/a/abc=w100-h100", avatarPhotoAddress("https://lh3.googleusercontent.com/a/abc=w100-h100", 88))
    }

    @Test
    fun `only https photos are loaded`() {
        assertEquals("", avatarPhotoAddress("http://lh3.googleusercontent.com/a/abc=s96-c", 88))
        assertEquals("", avatarPhotoAddress("", 88))
        assertEquals("", avatarPhotoAddress("data:image/png;base64,AAAA", 88))
    }
}
