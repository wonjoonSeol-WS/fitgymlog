package io.fitgymlog.app.coach

import java.io.InputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatClientTest {
    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val requests = CopyOnWriteArrayList<Request>()

    @Volatile
    private var respond: (Request) -> Pair<Int, String> = { 200 to "{}" }

    @Volatile
    private var location: String? = null
    private val socket = ServerSocket(0)
    private val server = thread(isDaemon = true) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            runCatching {
                client.use {
                    val input = it.getInputStream().buffered()
                    val (method, path) = input.line().split(" ")
                    val headers = generateSequence { input.line().takeIf(String::isNotEmpty) }
                        .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                    val body = input.readNBytes(headers["content-length"]?.toInt() ?: 0).decodeToString()
                    val request = Request(method, path, headers, body)
                    requests += request
                    val (status, text) = respond(request)
                    val bytes = text.toByteArray()
                    val redirect = location?.let { url -> "Location: $url\r\n" }.orEmpty()
                    it.getOutputStream().write("HTTP/1.1 $status X\r\n${redirect}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
                }
            }
        }
    }
    private val connection = Connection("http://127.0.0.1:${socket.localPort}/v1", "sk-test")
    private val client = ChatClient(timeoutMillis = 5_000)
    private val schema = buildJsonObject { put("type", "object") }
    private val hi = listOf(ChatMessage("user", "hi"))

    @After
    fun stop() {
        socket.close()
        server.join(1_000)
    }

    private fun InputStream.line(): String = generateSequence { read().takeIf { it >= 0 } }
        .takeWhile { it != '\n'.code }
        .map { it.toChar() }
        .joinToString("")
        .trimEnd('\r')

    private fun reply(content: String) = """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), content)}}}]}"""

    private fun formatOf(request: Request): String? =
        Json.parseToJsonElement(request.body).jsonObject["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    private suspend fun failureOf(block: suspend () -> Unit): CoachFailure? = (runCatching { block() }.exceptionOrNull() as? CoachException)?.failure

    @Test
    fun `lists the models the key can use, sending the key as a bearer token under the app's own name`() = runTest {
        respond = { 200 to """{"object":"list","data":[{"id":"gpt-6-luna"},{"id":"gpt-5.6-luna"}]}""" }

        assertEquals(listOf("gpt-5.6-luna", "gpt-6-luna"), client.models(connection))
        assertEquals("GET /v1/models", requests.single().let { "${it.method} ${it.path}" })
        assertEquals("Bearer sk-test", requests.single().headers["authorization"])
        assertNull(requests.single().headers["anthropic-version"])
        assertTrue(requests.single().headers["user-agent"].orEmpty().startsWith("FitGymLog/"))
    }

    @Test
    fun `a server that rejects a schema is asked for plain JSON, and keeps getting that for that model only`() = runTest {
        respond = { request -> if (formatOf(request) == "json_schema") 400 to """{"error":"response_format json_schema not supported"}""" else 200 to reply("""{"ok":true}""") }

        assertEquals("""{"ok":true}""", client.complete(connection, "m", hi, "targets", schema))
        client.complete(connection, "m", hi, "targets", schema)
        client.complete(connection, "other", hi, "targets", schema)

        assertEquals(listOf("json_schema", "json_object", "json_object", "json_schema", "json_object"), requests.map(::formatOf))
        assertEquals("POST /v1/chat/completions", requests.first().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `a message with a picture is sent as text and image parts, and others stay plain text`() = runTest {
        respond = { 200 to reply("""{"ok":true}""") }

        client.complete(connection, "m", listOf(ChatMessage("system", "s"), ChatMessage("user", "read", "data:image/jpeg;base64,AAAA")), "body", schema)

        val messages = Json.parseToJsonElement(requests.single().body).jsonObject.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals("s", messages.first().getValue("content").jsonPrimitive.content)
        val parts = messages.last().getValue("content").jsonArray.map { it.jsonObject }
        assertEquals(listOf("text", "image_url"), parts.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("read", parts.first().getValue("text").jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AAAA", parts.last().getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `a server that rejects every format is finally asked without one`() = runTest {
        respond = { request -> if (formatOf(request) != null) 422 to """{"error":"unknown field response_format"}""" else 200 to reply("ok") }

        assertEquals("ok", client.complete(connection, "m", hi, "targets", schema))
        assertEquals(listOf("json_schema", "json_object", null), requests.map(::formatOf))
    }

    @Test
    fun `a 400 in other words steps down from the schema once, then is reported as refused`() = runTest {
        respond = { 400 to """{"error":{"message":"This model's maximum context length is 8192 tokens"}}""" }

        assertEquals(CoachFailure.REJECTED, failureOf { client.complete(connection, "m", hi, "targets", schema) })
        assertEquals(listOf("json_schema", "json_object"), requests.map(::formatOf))
    }

    @Test
    fun `failures say what went wrong`() = runTest {
        val cases = mapOf(401 to CoachFailure.UNAUTHORIZED, 403 to CoachFailure.UNAUTHORIZED, 404 to CoachFailure.NOT_FOUND, 429 to CoachFailure.RATE_LIMITED, 503 to CoachFailure.SERVER)
        cases.forEach { (status, expected) ->
            respond = { status to """{"error":{"message":"nope"}}""" }
            assertEquals("HTTP $status", expected, failureOf { client.models(connection) })
        }
        respond = { 200 to "not json" }
        assertEquals(CoachFailure.BAD_REPLY, failureOf { client.models(connection) })
        respond = { 200 to "x".repeat(8_000_001) }
        assertEquals(CoachFailure.BAD_REPLY, failureOf { client.models(connection) })
    }

    @Test
    fun `a failure carries the provider's own words, preferring the error's message`() = runTest {
        val bodies = mapOf(
            """{"type":"error","error":{"type":"invalid_request_error","message":"This API key is not scoped to a workspace"},"message":"outer"}""" to "This API key is not scoped to a workspace",
            """{"error":"model not found"}""" to "model not found",
            """{"message":"slow down"}""" to "slow down",
            """{"error":{"message":"  "}}""" to null,
            "<html>bad gateway</html>" to null,
        )
        bodies.forEach { (body, expected) ->
            respond = { 400 to body }
            assertEquals(body, expected, (runCatching { client.models(connection) }.exceptionOrNull() as CoachException).providerMessage)
        }
    }

    @Test
    fun `nothing listening is reported as offline, and a server too slow to answer as a timeout`() = runTest {
        assertEquals(CoachFailure.OFFLINE, failureOf { client.models(Connection("http://127.0.0.1:1/v1", "sk-test")) })

        respond = { Thread.sleep(2_000); 200 to "{}" }
        assertEquals(CoachFailure.TIMEOUT, failureOf { ChatClient(timeoutMillis = 200).models(connection) })
    }

    @Test
    fun `cancelling a call closes the connection instead of waiting for the server`() = runBlocking {
        respond = { Thread.sleep(5_000); 200 to "{}" }
        val started = System.nanoTime()

        val call = launch(Dispatchers.Default) { client.models(connection) }
        delay(300)
        call.cancelAndJoin()

        assertTrue(System.nanoTime() - started < 2_000_000_000)
    }

    @Test
    fun `a redirect is not followed, so the key never goes to another address`() = runTest {
        location = "http://127.0.0.1:${socket.localPort}/elsewhere"
        respond = { request -> if (request.path == "/v1/models") 302 to "" else 200 to """{"data":[]}""" }

        assertEquals(CoachFailure.SERVER, failureOf { client.models(connection) })
        assertEquals(listOf("/v1/models"), requests.map { it.path })
    }

    @Test
    fun `a model on this phone or the local network keeps http and needs no key, and every other address is made https`() {
        assertEquals("http://192.168.0.10:11434/v1", Connection.baseUrlOf("http://192.168.0.10:11434/v1/"))
        assertEquals("http://localhost:11434/v1", Connection.baseUrlOf("localhost:11434/v1"))
        assertEquals("http://[::1]:8080", Connection.baseUrlOf("http://[::1]:8080"))
        assertEquals("http://100.101.102.103:11434", Connection.baseUrlOf("http://100.101.102.103:11434"))
        assertEquals("http://desktop.local:1234/v1", Connection.baseUrlOf("http://Desktop.local:1234/v1"))
        assertEquals("https://192.168.0.10/v1", Connection.baseUrlOf("https://192.168.0.10/v1"))
        assertEquals("https://172.32.0.1/v1", Connection.baseUrlOf("http://172.32.0.1/v1"))
        assertEquals("https://8.8.8.8", Connection.baseUrlOf("http://8.8.8.8"))
        assertEquals("https://api.openai.com/v1", Connection.baseUrlOf("http://api.openai.com/v1"))
        assertNull(Connection.baseUrlOf("http://192.168.0.10:11434/v1?a=1"))
        assertNull(Connection.baseUrlOf("http://me@192.168.0.10:11434/v1"))
        assertTrue(Connection.keyOptional("http://10.0.0.2:11434/v1"))
        assertTrue(Connection.keyOptional("https://192.168.0.10/v1"))
        assertEquals("https://desktop:8443/v1", Connection.baseUrlOf("https://desktop:8443/v1"))
        assertFalse(Connection.keyOptional("https://api.openai.com/v1"))
        assertTrue(coachReady(CoachSettings(enabled = true, baseUrl = "http://10.0.0.2:11434/v1", model = "llama"), keySavedFor = null))
        assertFalse(coachReady(CoachSettings(enabled = true, baseUrl = "https://api.openai.com/v1", model = "gpt"), keySavedFor = null))
    }

    @Test
    fun `local means loopback, private, link-local and Tailscale ranges, single names and local suffixes, up to their edges`() {
        val local = listOf(
            "127.0.0.1", "10.255.0.1", "172.16.0.5", "172.31.255.255", "192.168.1.1", "169.254.10.1", "100.64.0.1", "100.127.255.255",
            "::1", "[fd00::1]", "fc00::2", "fe80::1", "feb0::1", "desktop", "localhost.", "app.localhost", "nas.lan", "router.home.arpa", "llm.internal",
        )
        val remote = listOf(
            "172.15.0.1", "172.32.0.1", "192.169.0.1", "169.255.0.1", "100.63.255.255", "100.128.0.1", "11.0.0.1", "256.1.1.1",
            "2001:db8::1", "fe7f::1", "134744072", "1.2.3", "localhost.example.com", "llm.internal.example.com", "",
        )

        local.forEach { assertTrue(it, isLocalHost(it)) }
        remote.forEach { assertFalse(it, isLocalHost(it)) }
    }

    @Test
    fun `without a key no authorization is sent`() = runTest {
        respond = { 200 to """{"data":[{"id":"llama"}]}""" }

        client.models(connection.copy(apiKey = ""))

        assertNull(requests.single().headers["authorization"])
    }

    @Test
    fun `addresses are made https, and ones with a query or fragment are refused`() {
        assertEquals("https://api.openai.com/v1", Connection.baseUrlOf(" https://api.openai.com/v1/ "))
        assertEquals("https://api.deepseek.com", Connection.baseUrlOf("api.deepseek.com"))
        assertEquals("https://openrouter.ai/api/v1", Connection.baseUrlOf("http://openrouter.ai/api/v1"))
        assertNull(Connection.baseUrlOf("https://x.io/v1?a=1"))
        assertNull(Connection.baseUrlOf("https://x.io/v1#top"))
        assertNull(Connection.baseUrlOf("not a url"))
    }

    @Test
    fun `only Anthropic's own address gets its version header`() {
        assertTrue(isAnthropic("https://api.anthropic.com/v1"))
        assertFalse(isAnthropic("https://api.openai.com/v1"))
        assertFalse(isAnthropic("https://api.anthropic.com.example.org/v1"))
    }

    @Test
    fun `a provider error that repeats the key shows it masked`() = runTest {
        respond = { 401 to """{"error":{"message":"Incorrect API key provided: sk-test"}}""" }

        val error = runCatching { client.models(connection) }.exceptionOrNull() as CoachException

        assertEquals("Incorrect API key provided: ***", error.providerMessage)
        assertFalse(error.message.orEmpty().contains("sk-test"))
    }

    @Test
    fun `a connection never prints its key`() {
        assertFalse(connection.toString().contains("sk-test"))
    }

    @Test
    fun `JSON is found inside prose, a code fence or after a think block`() {
        assertEquals("""{"a":1}""", jsonIn("Sure! ```json\n{\"a\":1}\n```"))
        assertEquals("""{"b":2}""", jsonIn("<think>maybe {\"a\":1}?</think>\n{\"b\":2}"))
        assertEquals("""{"c":3}""", jsonIn("<think>{\"a\":1}</think>{\"b\":2}<think>again</think>{\"c\":3}"))
        assertEquals("plain", jsonIn("plain"))
    }
}
