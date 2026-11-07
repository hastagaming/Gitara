package io.gitara.workflow

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.random.Random

class WorkflowStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "workflow-runs")

    fun workflowsDir(repoDir: File): File = File(repoDir, WORKFLOWS_PATH)

    fun discover(repoDir: File): List<WorkflowParseResult> {
        val files = workflowsDir(repoDir).listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".yml") || it.name.endsWith(".yaml")) }
            ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { file ->
            try {
                if (file.length() > MAX_WORKFLOW_BYTES) {
                    WorkflowParseResult.Failed(file.name, "File is larger than 256 KB")
                } else {
                    WorkflowParser.parse(file.name, file.readText(Charsets.UTF_8))
                }
            } catch (e: IOException) {
                WorkflowParseResult.Failed(file.name, "Cannot read file: ${e.message}")
            }
        }
    }

    fun createTemplate(repoDir: File, baseName: String, content: String, extras: List<Pair<String, String>>): File {
        val dir = workflowsDir(repoDir)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.absolutePath}")
        var file = File(dir, "$baseName.yml")
        var counter = 2
        while (file.exists()) {
            file = File(dir, "$baseName-$counter.yml")
            counter++
        }
        for ((relative, text) in extras) {
            val target = File(repoDir, relative)
            if (target.exists()) continue
            target.parentFile?.mkdirs()
            target.writeText(text, Charsets.UTF_8)
        }
        file.writeText(content, Charsets.UTF_8)
        return file
    }

    fun deleteWorkflow(repoDir: File, fileName: String): Boolean {
        if (fileName.contains('/') || fileName.contains('\\')) return false
        return File(workflowsDir(repoDir), fileName).delete()
    }

    fun newRunId(): String =
        String.format(Locale.ROOT, "%013d-%04x", System.currentTimeMillis(), Random.nextInt(0x10000))

    fun logFile(repoId: Int, runId: String): File = File(repoRunsDir(repoId), "$runId.log")

    fun saveRun(record: RunRecord) {
        val dir = repoRunsDir(record.repoId)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.absolutePath}")
        val steps = JSONArray()
        for (step in record.steps) {
            steps.put(
                JSONObject()
                    .put("name", step.name)
                    .put("status", step.status.name)
                    .put("duration_ms", step.durationMs)
            )
        }
        val json = JSONObject()
            .put("id", record.id)
            .put("workflow_file", record.workflowFile)
            .put("workflow_name", record.workflowName)
            .put("status", record.status.name)
            .put("started_at", record.startedAt)
            .put("finished_at", record.finishedAt)
            .put("summary", record.summary)
            .put("steps", steps)
        val target = File(dir, "${record.id}.json")
        val temp = File(dir, "${record.id}.json.tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.writeText(json.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    fun loadRuns(repoId: Int, limit: Int = MAX_RUNS_KEPT): List<RunRecord> {
        val files = repoRunsDir(repoId).listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return emptyList()
        return files.sortedByDescending { it.name }.take(limit).mapNotNull { readRun(repoId, it) }
    }

    fun pruneRuns(repoId: Int, keep: Int = MAX_RUNS_KEPT) {
        val files = repoRunsDir(repoId).listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
        files.sortedByDescending { it.name }.drop(keep).forEach { file ->
            val id = file.name.removeSuffix(".json")
            file.delete()
            File(file.parentFile, "$id.log").delete()
        }
    }

    fun readLog(repoId: Int, runId: String, maxBytes: Long = MAX_LOG_BYTES): String {
        val file = logFile(repoId, runId)
        if (!file.isFile) return ""
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val start = if (length > maxBytes) length - maxBytes else 0L
                raf.seek(start)
                val buffer = ByteArray((length - start).toInt())
                raf.readFully(buffer)
                val text = String(buffer, Charsets.UTF_8)
                if (start > 0) "… earlier output truncated …\n" + text.substringAfter('\n', text) else text
            }
        } catch (e: IOException) {
            ""
        }
    }

    private fun repoRunsDir(repoId: Int): File = File(root, repoId.toString())

    private fun readRun(repoId: Int, file: File): RunRecord? {
        return try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            val steps = ArrayList<StepRecord>()
            val array = json.optJSONArray("steps") ?: JSONArray()
            for (i in 0 until array.length()) {
                val s = array.getJSONObject(i)
                steps.add(
                    StepRecord(
                        name = s.optString("name"),
                        status = enumOr(s.optString("status"), StepStatus.FAILED),
                        durationMs = s.optLong("duration_ms")
                    )
                )
            }
            RunRecord(
                id = json.getString("id"),
                repoId = repoId,
                workflowFile = json.optString("workflow_file"),
                workflowName = json.optString("workflow_name"),
                status = enumOr(json.optString("status"), RunStatus.FAILED),
                startedAt = json.optLong("started_at"),
                finishedAt = json.optLong("finished_at"),
                steps = steps,
                summary = json.optString("summary")
            )
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val WORKFLOWS_PATH = ".gitara/workflows"
        const val MAX_WORKFLOW_BYTES = 256L * 1024L
        const val MAX_RUNS_KEPT = 30
        const val MAX_LOG_BYTES = 768L * 1024L
    }
}