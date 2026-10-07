package dev.phoneagent.app

import android.content.Context
import android.util.AtomicFile
import dev.phoneagent.core.Session
import dev.phoneagent.core.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class FileSessionStore(context: Context) : SessionStore {
    private val directory = File(context.filesDir, "sessions").apply { mkdirs() }
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid session ID" }
        return File(directory, "$id.json")
    }
    override suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        lock.withLock {
            val safe = session.copy(trace = session.trace.takeLast(200))
            val atomic = AtomicFile(file(safe.id))
            val stream = atomic.startWrite()
            try {
                stream.write(json.encodeToString(safe).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) { atomic.failWrite(stream); throw error }
        }
    }
    override suspend fun load(id: String): Session? = withContext(Dispatchers.IO) {
        lock.withLock {
            val atomic = AtomicFile(file(id))
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return@withLock null
            json.decodeFromString<Session>(atomic.openRead().use { String(it.readBytes(), Charsets.UTF_8) })
        }
    }
    override suspend fun list(): List<Session> = withContext(Dispatchers.IO) {
        lock.withLock {
            directory.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
                runCatching { json.decodeFromString<Session>(AtomicFile(it).openRead().use { input -> String(input.readBytes(), Charsets.UTF_8) }) }.getOrNull()
            }.sortedByDescending { it.updatedAt }.take(30)
        }
    }
}
