package io.fitgymlog.app.coach

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ChatClientTest {
    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private val requests = mutableListOf<Request>()
    private var respond: (Request) -> Pair<Int, String> = { 200 to "{}" }
    private val socket = ServerSocket(0)
    private val server = thread(isDaemon = true) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            client.use {
                val input = it.getInputStream().bufferedReader()
                val (method, path) = input.readLine().split(" ")
                val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }
                    .associate { line -> line.substringBefore(':').lowercase() to line.substringAfter(':').trim() }
                val body = CharArray(headers["content-length"]?.toInt() ?: 0).also { chars -> input.read(chars) }.concatToString()
                val request = Request(method, path, headers, body)
                synchronized(requests) { requests += request }
                val (status, text) = respond(request)
                val bytes = text.toByteArray()
                it.getOutputStream().write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray() + bytes)
            }
        }
    }
    private val connection = Connection("http://127.0.0.1:${socket.localPort}/v1", "sk-test")
    private val client = ChatClient(timeoutMillis = 5_000)
    private val schema = buildJsonObject { put("type", "object") }

    @After
    fun stop() {
        socket.close()
        server.join(1_000)
    }

    private fun reply(content: String) = """{"choices":[{"message":{"role":"assistant","content":${Json.encodeToString(String.serializer(), content)}}}]}"""

    private fun formatOf(request: Request): String? =
        Json.parseToJsonElement(request.body).jsonObject["response_format"]?.jsonObject?.get("type")?.jsonPrimitive?.content

    @Test
    fun `lists the models the key can use, sending the key as a bearer token`() = runTest {
        respond = { 200 to """{"object":"list","data":[{"id":"gpt-6-luna"},{"id":"gpt-5.6-luna"}]}""" }

        assertEquals(listOf("gpt-5.6-luna", "gpt-6-luna"), client.models(connection))
        assertEquals("GET /v1/models", requests.single().let { "${it.method} ${it.path}" })
        assertEquals("Bearer sk-test", requests.single().headers["authorization"])
    }

    @Test
    fun `a server that rejects a schema is asked for plain JSON, and keeps getting that`() = runTest {
        respond = { request -> if (formatOf(request) == "json_schema") 400 to """{"error":"response_format not supported"}""" else 200 to reply("""{"ok":true}""") }
        val messages = listOf(ChatMessage("user", "hi"))

        assertEquals("""{"ok":true}""", client.complete(connection, "m", messages, "targets", schema))
        client.complete(connection, "m", messages, "targets", schema)

        assertEquals(listOf("json_schema", "json_object", "json_object"), requests.map(::formatOf))
        assertEquals("POST /v1/chat/completions", requests.first().let { "${it.method} ${it.path}" })
    }

    @Test
    fun `a server that rejects every format is finally asked without one`() = runTest {
        respond = { request -> if (formatOf(request) != null) 400 to "{}" else 200 to reply("ok") }

        assertEquals("ok", client.complete(connection, "m", listOf(ChatMessage("user", "hi")), "targets", schema))
        assertEquals(listOf("json_schema", "json_object", null), requests.map(::formatOf))
    }

    @Test
    fun `failures say what went wrong`() = runTest {
        val cases = mapOf(401 to CoachFailure.UNAUTHORIZED, 404 to CoachFailure.NOT_FOUND, 429 to CoachFailure.RATE_LIMITED, 503 to CoachFailure.SERVER)
        cases.forEach { (status, expected) ->
            respond = { status to """{"error":{"message":"nope"}}""" }
            try {
                client.models(connection)
                fail("HTTP $status should fail")
            } catch (error: CoachException) {
                assertEquals(expected, error.failure)
            }
        }
        respond = { 200 to "not json" }
        val badReply = runCatching { client.models(connection) }.exceptionOrNull() as CoachException
        assertEquals(CoachFailure.BAD_REPLY, badReply.failure)
    }

    @Test
    fun `nothing listening is reported as offline`() = runTest {
        val closed = Connection("http://127.0.0.1:1/v1", "sk-test")

        val error = runCatching { client.models(closed) }.exceptionOrNull() as CoachException

        assertEquals(CoachFailure.OFFLINE, error.failure)
    }

    @Test
    fun `only https addresses are accepted as a base`() {
        assertEquals("https://api.openai.com/v1", Connection.baseUrlOf(" https://api.openai.com/v1/ "))
        assertEquals("https://api.deepseek.com", Connection.baseUrlOf("api.deepseek.com"))
        assertEquals("https://openrouter.ai/api/v1", Connection.baseUrlOf("http://openrouter.ai/api/v1"))
        assertNull(Connection.baseUrlOf("not a url"))
    }

    @Test
    fun `JSON is found inside prose or a code fence`() {
        assertEquals("""{"a":1}""", jsonIn("Sure! ```json\n{\"a\":1}\n```"))
        assertEquals("plain", jsonIn("plain"))
    }
}
