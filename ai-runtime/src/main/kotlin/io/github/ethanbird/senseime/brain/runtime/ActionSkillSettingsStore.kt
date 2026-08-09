package io.github.ethanbird.senseime.brain.runtime

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import org.json.JSONObject

data class ActionSkillPreference(
    val skillId: String,
    val enabled: Boolean,
)

/** Cross-process durable user choices for direct, zero-model-token skills. */
class ActionSkillSettingsStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "agent/action-skills")
    private val file = AtomicFile(File(root, "settings.v1.json"))
    private val lockFile = File(root, "settings.lock")

    fun isEnabled(skillId: String): Boolean = load().getOrDefault(emptyMap())[skillId] ?: true

    fun load(): Result<Map<String, Boolean>> = runCatching {
        withLock {
            val document = readOrNull() ?: return@withLock emptyMap()
            val enabled = document.getJSONObject("enabled")
            buildMap {
                enabled.keys().forEach { skillId -> put(skillId, enabled.getBoolean(skillId)) }
            }
        }
    }

    fun setEnabled(skillId: String, enabled: Boolean): Result<Unit> = runCatching {
        require(skillId.matches(io.github.ethanbird.senseime.brain.api.ActionSkillDescriptor.ID_PATTERN))
        withLock {
            val document = readOrNull() ?: JSONObject()
                .put("schema_version", SCHEMA_VERSION)
                .put("enabled", JSONObject())
            document.getJSONObject("enabled").put(skillId, enabled)
            write(document)
        }
    }

    private fun readOrNull(): JSONObject? = try {
        JSONObject(file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }).also {
            require(it.getInt("schema_version") == SCHEMA_VERSION)
        }
    } catch (_: FileNotFoundException) {
        null
    }

    private fun write(document: JSONObject) {
        val stream = file.startWrite()
        try {
            stream.write(document.toString().toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun <T> withLock(block: () -> T): T = synchronized(MUTEX) {
        if (!root.exists() && !root.mkdirs() && !root.isDirectory) error("Unable to create Action Skill settings")
        RandomAccessFile(lockFile, "rw").channel.use { channel -> channel.lock().use { block() } }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val MUTEX = Any()
    }
}
