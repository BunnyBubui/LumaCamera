package th.ogay.luma

import org.junit.Assert.assertEquals
import org.junit.Test

class McpTest {
    @Test fun answerIsFoundInAnEventStream() {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}\n\n"
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{}}", Mcp.pick(body, true, 3))
    }
    @Test fun plainJsonIsReturnedAsIs() {
        assertEquals("{\"id\":1}", Mcp.pick(" {\"id\":1} \n", false, 1))
        assertEquals(null, Mcp.pick("", false, 1))
    }
    @Test fun idOneDoesNotMatchIdTen() {
        val body = "data: {\"id\":10,\"result\":{\"a\":1}}\n\ndata: {\"id\":1,\"result\":{\"a\":2}}\n\n"
        assertEquals("{\"id\":1,\"result\":{\"a\":2}}", Mcp.pick(body, true, 1))
    }
    @Test fun toolNamesAreSafe() {
        assertEquals("s0_search_music", Mcp.safeName(0, "search.music"))
        assertEquals(64, Mcp.safeName(2, "x".repeat(100)).length)
    }
}
