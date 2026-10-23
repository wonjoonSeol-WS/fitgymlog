package io.fitgymlog.app.coach

import io.fitgymlog.app.BuildConfig
import io.fitgymlog.app.exchangeCancellable
import io.fitgymlog.app.links.WebLinks
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
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
    override fun toString() = "Connection($baseUrl)"

    companion object {
        fun baseUrlOf(input: String): String? = localBaseUrlOf(input) ?: WebLinks.normalize(input)?.trimEnd('/')?.takeIf { '?' !in it && '#' !in it }

        /** Local servers such as Ollama take no key. */
        fun keyOptional(baseUrl: String): Boolean = localBaseUrlOf(baseUrl) != null
    }
}

/** [imageUrl] is a data: URL sent alongside the text, for models that read pictures. */
data class ChatMessage(val role: String, val content: String, val imageUrl: String? = null)

enum class CoachFailure { UNAUTHORIZED, NOT_FOUND, REJECTED, RATE_LIMITED, SERVER, OFFLINE, TIMEOUT, BAD_REPLY }

class CoachException(val failure: CoachFailure, message: String, cause: Throwable? = null, val providerMessage: String? = null) : Exception(message, cause)

/** Anthropic's /v1/models answers 400 without a version header, which its OpenAI-compatible chat does not need. */
internal fun isAnthropic(baseUrl: String): Boolean = runCatching { URL(baseUrl).host }.getOrNull()?.let { it == "api.anthropic.com" } == true

/** One of the response formats an OpenAI-compatible server may accept, strictest first. */
internal enum class ReplyFormat { SCHEMA, JSON, TEXT }

class ChatClient(private val timeoutMillis: Int = 120_000) {
    private val formats = ConcurrentHashMap<String, ReplyFormat>()

    suspend fun models(connection: Connection): List<String> {
        val body = request(connection, "models", payload = null)
        return parse(body) { json -> json["data"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.sorted() }
    }

    /** Servers answer a response format they don't support with 400 or 422 in their own words, so a strict schema always gets one step down; the format that worked is kept. */
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
            } catch (_: FormatRejected) {
                format = ReplyFormat.entries[format.ordinal + 1]
            }
        }
    }

    private fun chatPayload(model: String, messages: List<ChatMessage>, format: ReplyFormat, schemaName: String, schema: JsonObject) = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    val image = message.imageUrl
                    if (image == null) {
                        put("content", message.content)
                    } else {
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", message.content) })
                            add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", image) }) })
                        })
                    }
                })
            }
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
                instanceFollowRedirects = false
                if (connection.apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${connection.apiKey}")
                setRequestProperty("User-Agent", "FitGymLog/${BuildConfig.VERSION_NAME}")
                if (isAnthropic(connection.baseUrl)) setRequestProperty("anthropic-version", ANTHROPIC_VERSION)
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
        return http.exchangeCancellable { exchange(it, payload, connection.apiKey) }
    }

    private fun exchange(http: HttpURLConnection, payload: JsonObject?, apiKey: String): String {
        try {
            payload?.let { body -> http.outputStream.use { it.write(body.toString().toByteArray()) } }
            val status = http.responseCode
            val body = (if (status in 200..299) http.inputStream else http.errorStream)?.use { it.readCapped() }.orEmpty()
            val text = if (status in 200..299) body else body.replace(apiKey, "***")
            when {
                status in 200..299 -> return text
                status == 400 || status == 422 -> throw if (payload.formatMayBeRejected(text)) FormatRejected(text) else failure(CoachFailure.REJECTED, status, text)
                status == 401 || status == 403 -> throw failure(CoachFailure.UNAUTHORIZED, status, text)
                status == 404 -> throw failure(CoachFailure.NOT_FOUND, status, text)
                status == 429 -> throw failure(CoachFailure.RATE_LIMITED, status, text)
                else -> throw failure(CoachFailure.SERVER, status, text)
            }
        } catch (error: SocketTimeoutException) {
            throw CoachException(CoachFailure.TIMEOUT, error.message.orEmpty(), error)
        } catch (error: IOException) {
            throw CoachException(CoachFailure.OFFLINE, error.message.orEmpty(), error)
        }
    }

    private fun JsonObject?.formatMayBeRejected(error: String): Boolean {
        val format = this?.get("response_format")?.jsonObject?.get("type")?.jsonPrimitive?.content ?: return false
        return format == "json_schema" || FORMAT_ERROR.containsMatchIn(error)
    }

    private fun InputStream.readCapped(): String {
        val bytes = readNBytes(MAX_BODY_BYTES + 1)
        if (bytes.size > MAX_BODY_BYTES) throw CoachException(CoachFailure.BAD_REPLY, "Reply over $MAX_BODY_BYTES bytes")
        return bytes.decodeToString()
    }

    private fun failure(kind: CoachFailure, status: Int, body: String) = CoachException(kind, "HTTP $status: ${body.take(300)}", providerMessage = messageIn(body))

    private fun messageIn(body: String): String? = runCatching {
        val json = lenient.parseToJsonElement(body).jsonObject
        val error = json["error"]
        ((error as? JsonObject)?.get("message") ?: error ?: json["message"])?.jsonPrimitive?.contentOrNull
    }.getOrNull()?.trim()?.take(MAX_MESSAGE_CHARS)?.ifEmpty { null }

    private fun <T> parse(body: String, read: (JsonObject) -> T): T = try {
        read(lenient.parseToJsonElement(body).jsonObject)
    } catch (error: Exception) {
        throw CoachException(CoachFailure.BAD_REPLY, body.take(300), error)
    }

    private class FormatRejected(body: String) : Exception(body.take(300))

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val MAX_BODY_BYTES = 8_000_000
        const val MAX_MESSAGE_CHARS = 300
        const val ANTHROPIC_VERSION = "2023-06-01"
        val FORMAT_ERROR = Regex("response_format|json_schema|json_object", RegexOption.IGNORE_CASE)
        val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** Models asked for JSON without a schema may wrap it in prose, a code fence or a <think> block. */
internal fun jsonIn(reply: String): String {
    val answer = reply.substringAfterLast("</think>")
    val start = answer.indexOf('{')
    val end = answer.lastIndexOf('}')
    return if (start >= 0 && end > start) answer.substring(start, end + 1) else answer
}
