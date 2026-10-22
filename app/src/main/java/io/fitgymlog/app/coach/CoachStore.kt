package io.fitgymlog.app.coach

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import io.fitgymlog.app.LOG_TAG
import io.fitgymlog.app.catching
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CoachData(
    val memories: List<Memory> = emptyList(),
    val chat: List<ChatTurn> = emptyList(),
    val reviews: List<Review> = emptyList(),
)

/** Memories, the chat and workout reviews, kept in one file on this phone and never synced. */
class CoachStore(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "coach.json"))
    private val writing = Mutex()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<CoachData> = _data.asStateFlow()

    suspend fun update(transform: (CoachData) -> CoachData) = writing.withLock {
        _data.update { transform(it).trimmed() }
        withContext(Dispatchers.IO) { save(_data.value) }
    }

    suspend fun remember(topic: MemoryTopic, text: String) = update {
        it.copy(memories = it.memories + Memory(UUID.randomUUID().toString(), topic, text.trim(), LocalDate.now().toString()))
    }

    suspend fun forget(id: String) = update { data -> data.copy(memories = data.memories.filterNot { it.id == id }) }

    suspend fun editMemory(id: String, text: String) = update { data ->
        data.copy(memories = data.memories.map { if (it.id == id) it.copy(text = text.trim()) else it })
    }

    suspend fun keepReview(review: Review) = update { data -> data.copy(reviews = data.reviews.filterNot { it.workoutUuid == review.workoutUuid } + review) }

    private fun load(): CoachData = catching {
        if (!file.baseFile.exists()) CoachData() else json.decodeFromString(CoachData.serializer(), file.readFully().decodeToString())
    }.onFailure { Log.w(LOG_TAG, "Coach data unreadable, starting empty", it) }.getOrDefault(CoachData())

    private fun save(data: CoachData) {
        val stream = file.startWrite()
        try {
            stream.write(json.encodeToString(CoachData.serializer(), data).toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun CoachData.trimmed() = copy(chat = chat.takeLast(MAX_CHAT_TURNS), reviews = reviews.takeLast(MAX_REVIEWS))

    private companion object {
        const val MAX_CHAT_TURNS = 200
        const val MAX_REVIEWS = 60
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
