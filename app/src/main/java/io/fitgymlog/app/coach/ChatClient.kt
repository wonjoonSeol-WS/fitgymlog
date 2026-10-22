package io.fitgymlog.app.coach

import io.fitgymlog.app.exchangeCancellable
import io.fitgymlog.app.links.WebLinks
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class Connection(val baseUrl: String, val apiKey: String) {
    companion object {
        /** The https base the provider documents, such as https://api.openai.com/v1, or null when it is not one. */
        fun baseUrlOf(input: String): String? = WebLinks.normalize(input)?.trimEnd('/')?.takeIf { '?' !in it && '#' !in it }
    }
}

data class ChatMessage(val role: String, val content: String)

enum class CoachFailure { UNAUTHORIZED, NOT_FOUND, RATE_LIMITED, SERVER, OFFLINE, BAD_REPLY }

class CoachException(val failure: CoachFailure, message: String, cause: Throwable? = null) : Exception(message, cause)

/** One of the response formats an OpenAI-compatible server may accept, strictest first. */
internal enum class ReplyFormat { SCHEMA, JSON, TEXT }

class ChatClient(private val timeoutMillis: Int = 60_000) {
    private val formats = ConcurrentHashMap<String, ReplyFormat>()

    suspend fun models(connection: Connection): List<String> {
        val body = request(connection, "models", payload = null)
        return parse(body) { json -> json["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted() }
    }

    /**
     * Servers differ in which response formats they accept and answer an unknown one with 400, so a
     * rejected format falls back to the next one and the one that worked is kept for that server and model.
     */
    suspend fun complete(connection: Connection, model: String, messages: List<ChatMessage>, schemaName: String, schema: JsonObject): String {
        val key = "${connection.baseUrl}|$model"
        var format = formats[key] ?: ReplyFormat.SCHEMA
        while (true) {
            try {
                val body = request(connection, "chat/completions", chatPayload(model, messages, format, schemaName, schema))
                formats[key] = format
                return parse(body) { json ->
                    json["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.contentOrNull!!
                }
            } catch (rejected: FormatRejected) {
                format = ReplyFormat.entries.getOrNull(format.ordinal + 1) ?: throw CoachException(CoachFailure.BAD_REPLY, rejected.message.orEmpty())
            }
        }
    }

    private fun chatPayload(model: String, messages: List<ChatMessage>, format: ReplyFormat, schemaName: String, schema: JsonObject) = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            messages.forEach { message -> add(buildJsonObject { put("role", message.role); put("content", message.content) }) }
        })
        when (format) {
            ReplyFormat.SCHEMA -> put("response_format", buildJsonObject {
                put("type", "json_schema")
                put("json_schema", buildJsonObject { put("name", schemaName); put("strict", true); put("schema", schema) })
            })
            ReplyFormat.JSON -> put("response_format", buildJsonObject { put("type", "json_object") })
            ReplyFormat.TEXT -> Unit
        }
    }

    private suspend fun request(connection: Connection, path: String, payload: JsonObject?): String {
        val http = try {
            (URL("${connection.baseUrl}/$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = timeoutMillis
                setRequestProperty("Authorization", "Bearer ${connection.apiKey}")
                setRequestProperty("Accept", "application/json")
                if (payload != null) {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
        } catch (error: IOException) {
            throw CoachException(CoachFailure.OFFLINE, error.message.orEmpty(), error)
        }
        return http.exchangeCancellable { exchange(it, payload) }
    }

    private fun exchange(http: HttpURLConnection, payload: JsonObject?): String {
        try {
            payload?.let { body -> http.outputStream.use { it.write(body.toString().toByteArray()) } }
            val status = http.responseCode
            val text = (if (status in 200..299) http.inputStream else http.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
            when {
                status in 200..299 -> return text
                status == 400 || status == 422 -> throw if (payload?.containsKey("response_format") == true) FormatRejected(text) else failure(CoachFailure.BAD_REPLY, status, text)
                status == 401 || status == 403 -> throw failure(CoachFailure.UNAUTHORIZED, status, text)
                status == 404 -> throw failure(CoachFailure.NOT_FOUND, status, text)
                status == 429 -> throw failure(CoachFailure.RATE_LIMITED, status, text)
                else -> throw failure(CoachFailure.SERVER, status, text)
            }
        } catch (error: IOException) {
            throw CoachException(CoachFailure.OFFLINE, error.message.orEmpty(), error)
        } finally {
            http.disconnect()
        }
    }

    private fun failure(kind: CoachFailure, status: Int, body: String) = CoachException(kind, "HTTP $status: ${body.take(300)}")

    private fun <T> parse(body: String, read: (JsonObject) -> T): T = try {
        read(lenient.parseToJsonElement(body).jsonObject)
    } catch (error: Exception) {
        throw CoachException(CoachFailure.BAD_REPLY, body.take(300), error)
    }

    private class FormatRejected(body: String) : Exception(body.take(300))

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** Models asked for JSON without a schema, or told only in words, may wrap it in prose or a code fence. */
internal fun jsonIn(reply: String): String {
    val start = reply.indexOf('{')
    val end = reply.lastIndexOf('}')
    return if (start >= 0 && end > start) reply.substring(start, end + 1) else reply
}
