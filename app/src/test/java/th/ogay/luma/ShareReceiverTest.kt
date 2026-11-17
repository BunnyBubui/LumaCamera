package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareReceiverTest {
    @Test fun endingComesFromTheTypeFirst() {
        assertEquals("mp4", ShareReceiverActivity.extFor("video/mp4", "clip.xyz"))
        assertEquals("jpg", ShareReceiverActivity.extFor("image/jpeg", null))
        assertEquals("heic", ShareReceiverActivity.extFor("image/heif", "a.jpg"))
    }
    @Test fun endingFallsBackToTheNameThenToADefault() {
        assertEquals("avi", ShareReceiverActivity.extFor("video/x-msvideo", "movie.AVI"))
        assertEquals("mp4", ShareReceiverActivity.extFor("video/unknown", "movie"))
        assertEquals("jpg", ShareReceiverActivity.extFor(null, null))
    }
    @Test fun kindFromNameTellsPicturesFromVideos() {
        assertEquals("video", ShareReceiverActivity.kindFromName("a.MOV"))
        assertEquals("image", ShareReceiverActivity.kindFromName("a.jpeg"))
        assertNull(ShareReceiverActivity.kindFromName("a.pdf"))
        assertNull(ShareReceiverActivity.kindFromName(null))
    }
}
