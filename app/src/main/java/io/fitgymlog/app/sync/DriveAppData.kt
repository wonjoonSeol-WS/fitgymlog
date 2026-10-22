package io.fitgymlog.app.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Google sign-in limited to the app's own hidden Drive folder. */
class DriveAuth(private val context: Context) {
    sealed interface Start {
        data class NeedsConsent(val intent: PendingIntent) : Start
        data class Granted(val token: String) : Start
    }

    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()

    private val client get() = Identity.getAuthorizationClient(context)

    suspend fun start(): Start {
        val result = client.authorize(request).await()
        val consent = result.pendingIntent
        return if (result.hasResolution() && consent != null) Start.NeedsConsent(consent) else Start.Granted(checkNotNull(result.accessToken))
    }

    fun finish(data: Intent?): String? = runCatching { client.getAuthorizationResultFromIntent(data).accessToken }.getOrNull()

    /** A token without showing anything, or null when the user has to sign in again. */
    suspend fun silentToken(): String? = (runCatching { start() }.getOrNull() as? Start.Granted)?.token

    suspend fun forget(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    private companion object {
        const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    }
}

class DriveAuthException : IOException("Google Drive rejected the sign-in")

/** The one sync file in the user's Drive app data folder, over the Drive v3 REST API. */
class DriveAppData(private val token: String) {
    data class RemoteFile(val id: String)

    suspend fun find(): RemoteFile? = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8").replace("+", "%20")
        val files = JSONObject(request("GET", "$FILES?spaces=appDataFolder&fields=files(id)&q=$query")).getJSONArray("files")
        if (files.length() == 0) null else RemoteFile(files.getJSONObject(0).getString("id"))
    }

    suspend fun download(file: RemoteFile): String = withContext(Dispatchers.IO) { request("GET", "$FILES/${file.id}?alt=media") }

    suspend fun create(content: String): Unit = withContext(Dispatchers.IO) {
        val boundary = "fitgymlog-${UUID.randomUUID()}"
        val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
        val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$metadata\r\n" +
            "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$content\r\n--$boundary--\r\n"
        request("POST", "$UPLOAD?uploadType=multipart", body, "multipart/related; boundary=$boundary")
    }

    /** HttpURLConnection has no PATCH, and Google APIs accept the override header instead. */
    suspend fun update(file: RemoteFile, content: String): Unit = withContext(Dispatchers.IO) {
        request("POST", "$UPLOAD/${file.id}?uploadType=media", content, "application/json; charset=UTF-8", mapOf("X-HTTP-Method-Override" to "PATCH"))
    }

    suspend fun accountEmail(): String? = withContext(Dispatchers.IO) {
        runCatching { JSONObject(request("GET", "$API/about?fields=user(emailAddress)")).getJSONObject("user").getString("emailAddress") }.getOrNull()
    }

    private fun request(method: String, url: String, body: String? = null, contentType: String? = null, headers: Map<String, String> = emptyMap()): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $token")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType)
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) throw DriveAuthException()
            if (code >= 400) {
                val detail = connection.errorStream?.use { it.readBytes().decodeToString() }.orEmpty().take(ERROR_PREVIEW)
                throw IOException("Google Drive returned $code $detail")
            }
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val FILE_NAME = "fitgymlog-sync.json"
        const val API = "https://www.googleapis.com/drive/v3"
        const val FILES = "$API/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        const val TIMEOUT_MILLIS = 15_000
        const val ERROR_PREVIEW = 300
    }
}
