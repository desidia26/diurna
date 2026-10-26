package app.diurna.ai

import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.MessageParam
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ClaudeRequestTest {
    @Test fun textOnlyIsASingleUserMessage() {
        val p = ClaudeRequest.params("claude-test", "burrito bowl")
        assertEquals("claude-test", p.model().asString())
        val m = p.messages().single()
        assertEquals(MessageParam.Role.USER, m.role())
        assertEquals("burrito bowl", m.content().asString())
        assertTrue(p.outputConfig().isPresent)
    }

    @Test fun photoGoesFirstAsBase64JpegThenTheText() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 4, 5, 6)
        val p = ClaudeRequest.params("claude-test", "chicken salad, half eaten", jpeg)
        val blocks = p.messages().single().content().asBlockParams()
        assertEquals(2, blocks.size)
        val source = blocks[0].asImage().source().asBase64()
        assertEquals(Base64ImageSource.MediaType.IMAGE_JPEG, source.mediaType())
        assertArrayEquals(jpeg, Base64.getDecoder().decode(source.data()))
        assertEquals("chicken salad, half eaten", blocks[1].asText().text())
        // The prompt and structured output are the same with or without a photo.
        assertEquals(EstimatePrompt.SYSTEM, p.system().get().asString())
        assertTrue(p.outputConfig().get().format().isPresent)
    }
}
