package io.gitara.workflow

import android.content.Context
import android.os.SystemClock
import io.gitara.box.BoxLayout
import io.gitara.box.BoxManager
import io.gitara.box.BoxProcess
import io.gitara.box.BoxRuntime
import io.gitara.box.ProcessOutcome
import io.gitara.box.deleteTree
import io.gitara.secrets.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.sheimi.sgit.database.models.Repo
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

data class EngineSnapshot(
    val live: Map<Int, LiveRun> = emptyMap(),
    val finishedTick: Long = 0L
)

sealed class StartResult {
    class Started(val runId: String) : StartResult()
    object AlreadyRunning : StartResult()
    class Failed(val message: String) : StartResult()
}

internal class RunHandle {
    val cancelled = AtomicBoolean(false)
}

private class Masker {
    private val values = LinkedHashSet<String>()

    @Volatile
    private var sorted: List<String> = emptyList()

    @Synchronized
    fun add(value: String) {
        var changed = false
        for (candidate in listOf(value) + value.split('\n', '\r')) {
            val v = candidate.trim()
            if (v.length >= 4 && values.add(v)) changed = true
        }
        if (changed) sorted = values.sortedByDescending { it.length }
    }

    fun mask(text: String): String {
        var out = text
        for (v in sorted) {
            if (out.contains(v)) out = out.replace(v, "***")
        }
        return out
    }
}

private class LogWriter(file: File, private val masker: Masker) {
    private val out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
    private var written = 0L
    private var capped = false

    @Synchronized
    fun line(text: String) {
        if (capped) return
        try {
            val masked = masker.mask(text)
            written += masked.length + 1
            if (written > MAX_LOG_CHARS) {
                out.write("… log limit reached, further output is discarded\n")
                out.flush()
                capped = true
                return
            }
            out.write(masked)
            out.write("\n")
            out.flush()
        } catch (e: IOException) {
            capped = true
        }
    }

    @Synchronized
    fun close() {
        try {
            out.close()
        } catch (e: IOException) {
            // nothing left to do
        }
    }

    private companion object {
        const val MAX_LOG_CHARS = 8_000_000L
    }
}

private class JobResult(val status: String, val outputs: Map<String, String>)

private class StepResult(
    val ok: Boolean,
    val cancelled: Boolean,
    val outputs: Map<String, String>,
    val message: String?
)

private class Scope(
    val inputs: Map<String, Any?>,
    val env: LinkedHashMap<String, String>,
    val needs: Map<String, Any?>,
    var jobStatus: String,
    val deadline: Long
) {
    val steps = LinkedHashMap<String, Any?>()
}

private val ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
private val HEREDOC = Regex("^([^=<]+)<<(.+)$")
private val ANNOTATION = Regex("^::(error|warning|notice)(?: [^:]*)?::(.*)$")

private fun parseKeyValues(text: String): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val lines = text.split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i].trimEnd('\r')
        i++
        if (line.isBlank()) continue
        val heredoc = HEREDOC.find(line)
        if (heredoc != null) {
            val key = heredoc.groupValues[1]
            val delimiter = heredoc.groupValues[2]
            val sb = StringBuilder()
            var first = true
            while (i < lines.size && lines[i].trimEnd('\r') != delimiter) {
                if (!first) sb.append('\n')
                sb.append(lines[i].trimEnd('\r'))
                first = false
                i++
            }
            i++
            out[key] = sb.toString()
        } else {
            val eq = line.indexOf('=')
            if (eq > 0) out[line.substring(0, eq)] = line.substring(eq + 1)
        }
    }
    return out
}

private class Runner(
    private val appContext: Context,
    private val repo: Repo,
    private val repoDir: File,
    private val definition: WorkflowDefinition,
    private val runId: String,
    inputValues: Map<String, String>,
    private val handle: RunHandle,
    private val startedAt: Long,
    private val store: WorkflowStore,
    private val publish: (List<StepRecord>) -> Unit
) {
    private val repoId = repo.getID()
    private val layout = BoxLayout(appContext)
    private val masker = Masker()
    private val runHostDir = File(layout.runDir, runId)
    private val records = ArrayList<StepRecord>()
    private val totalSteps: Int
    private var cursor = 0
    private var fileCounter = 0
    private var secrets: Map<String, String> = emptyMap()
    private lateinit var log: LogWriter
    private val actionCache = HashMap<String, ActionDef>()
    private val repoName = repo.getDiaplayName().orEmpty().removeSuffix(" (external)")
    private val branch = currentBranch()

    private val inputsContext: Map<String, Any?> = LinkedHashMap<String, Any?>().also { map ->
        for ((name, def) in definition.inputs) {
            map[name] = inputValues[name]?.takeIf { it.isNotBlank() } ?: def.default ?: ""
        }
    }

    private val gitaraContext: Map<String, Any?> = mapOf(
        "repository" to repoName,
        "workspace" to "/workspace",
        "run_id" to runId,
        "workflow" to definition.name,
        "ref_name" to branch,
        "event_name" to "manual",
        "actor" to "gitara"
    )

    private val runnerContext: Map<String, Any?> = mapOf(
        "os" to "Linux",
        "arch" to "ARM64",
        "name" to "gitara-box",
        "temp" to "/tmp"
    )

    private val baseEnv: Map<String, String> = linkedMapOf(
        "CI" to "true",
        "GITARA" to "true",
        "GITARA_WORKSPACE" to "/workspace",
        "GITARA_REPOSITORY" to repoName,
        "GITARA_REF_NAME" to branch,
        "GITARA_RUN_ID" to runId,
        "GITARA_WORKFLOW" to definition.name,
        "GITHUB_WORKSPACE" to "/workspace",
        "GITHUB_REPOSITORY" to repoName,
        "GITHUB_REF_NAME" to branch,
        "GITHUB_RUN_ID" to runId
    )

    init {
        for (job in definition.jobs) {
            for (step in job.steps) {
                records.add(StepRecord("${job.label} / ${step.label}", StepStatus.PENDING, 0L))
            }
        }
        totalSteps = records.size
    }

    fun initialRecords(): List<StepRecord> = records.toList()

    fun execute() {
        runHostDir.mkdirs()
        val logFile = store.logFile(repoId, runId)
        logFile.parentFile?.mkdirs()
        log = LogWriter(logFile, masker)
        var finalStatus = RunStatus.SUCCESS
        var summary = ""
        try {
            secrets = SecretStore(appContext).resolve(repoId)
            secrets.values.forEach { masker.add(it) }

            log.line("Workflow : ${definition.name} (${definition.fileName})")
            log.line("Box      : Alpine Linux in proot, workspace at /workspace")
            log.line("Secrets  : ${secrets.size} available")

            val results = LinkedHashMap<String, JobResult>()
            for (job in definition.jobs) {
                if (handle.cancelled.get()) break
                results[job.id] = runJob(job, results)
            }

            val failed = results.filter { it.value.status == "failure" }.keys
            finalStatus = when {
                handle.cancelled.get() || results.values.any { it.status == "cancelled" } -> RunStatus.CANCELLED
                failed.isNotEmpty() -> RunStatus.FAILED
                else -> RunStatus.SUCCESS
            }
            summary = when (finalStatus) {
                RunStatus.CANCELLED -> "Cancelled"
                RunStatus.FAILED -> "Failed: " + failed.joinToString(", ")
                else -> "All ${results.count { it.value.status == "success" }} job(s) passed"
            }
        } catch (t: Throwable) {
            Timber.e(t, "Workflow run failed unexpectedly")
            log.line("✕ Unexpected error: ${t.message ?: t.javaClass.simpleName}")
            for (i in records.indices) {
                if (records[i].status == StepStatus.RUNNING) records[i] = records[i].copy(status = StepStatus.FAILED)
            }
            finalStatus = RunStatus.FAILED
            summary = t.message ?: "Unexpected error"
        } finally {
            for (i in records.indices) {
                if (records[i].status == StepStatus.PENDING) records[i] = records[i].copy(status = StepStatus.SKIPPED)
            }
            publish(records.toList())
            log.line("")
            log.line("Run ${finalStatus.name.lowercase(Locale.ROOT)}: $summary")
            log.close()
            deleteTree(runHostDir)
            try {
                store.saveRun(
                    RunRecord(
                        id = runId,
                        repoId = repoId,
                        workflowFile = definition.fileName,
                        workflowName = definition.name,
                        status = finalStatus,
                        startedAt = startedAt,
                        finishedAt = System.currentTimeMillis(),
                        steps = records.toList(),
                        summary = summary
                    )
                )
                store.pruneRuns(repoId)
            } catch (e: Exception) {
                Timber.e(e, "Could not save the run record")
            }
        }
    }

    private fun context(scope: Scope): ExprContext {
        val vars = HashMap<String, Any?>()
        vars["env"] = scope.env
        vars["secrets"] = secrets
        vars["inputs"] = scope.inputs
        vars["steps"] = scope.steps
        vars["needs"] = scope.needs
        vars["gitara"] = gitaraContext
        vars["github"] = gitaraContext
        vars["job"] = mapOf("status" to scope.jobStatus)
        vars["runner"] = runnerContext
        return ExprContext(vars) { scope.jobStatus }
    }

    private fun setRecord(index: Int?, status: StepStatus, duration: Long) {
        if (index == null || index !in records.indices) return
        records[index] = records[index].copy(status = status, durationMs = duration)
        publish(records.toList())
    }

    private fun runJob(job: JobDef, results: Map<String, JobResult>): JobResult {
        val needsContext = LinkedHashMap<String, Any?>()
        for (id in job.needs) {
            val r = results[id]
            needsContext[id] = mapOf("result" to (r?.status ?: "skipped"), "outputs" to (r?.outputs ?: emptyMap<String, String>()))
        }
        val needsOk = job.needs.all { results[it]?.status == "success" }
        val deadline = SystemClock.elapsedRealtime() + (job.timeoutMinutes ?: 360) * 60_000L
        val scope = Scope(inputsContext, LinkedHashMap(), needsContext, if (needsOk) "success" else "failure", deadline)

        log.line("")
        log.line("▶ Job: ${job.label}")

        val runIt = try {
            val ctx = context(scope)
            for ((k, v) in definition.env) scope.env[k] = Expr.interpolate(v, ctx)
            for ((k, v) in job.env) scope.env[k] = Expr.interpolate(v, ctx)
            Expr.shouldRun(job.condition, ctx)
        } catch (e: ExprError) {
            log.line("✕ Invalid expression in job '${job.id}': ${e.message}")
            repeat(job.steps.size) { setRecord(cursor++, StepStatus.FAILED, 0L) }
            return JobResult("failure", emptyMap())
        }
        if (!runIt) {
            log.line("– Job skipped")
            repeat(job.steps.size) { setRecord(cursor++, StepStatus.SKIPPED, 0L) }
            return JobResult("skipped", emptyMap())
        }

        for (step in job.steps) {
            val index = cursor++
            runStep(index, step, scope, 0)
        }
        if (handle.cancelled.get()) scope.jobStatus = "cancelled"

        val outputs = LinkedHashMap<String, String>()
        for ((name, expression) in job.outputs) {
            outputs[name] = try {
                Expr.interpolate(expression, context(scope))
            } catch (e: ExprError) {
                ""
            }
        }
        return JobResult(scope.jobStatus, outputs)
    }

    private fun runStep(recordIndex: Int?, step: StepDef, scope: Scope, depth: Int) {
        val indent = "  ".repeat(depth)
        val ctx = context(scope)
        val label = try {
            step.name?.let { Expr.interpolate(it, ctx) }?.takeIf { it.isNotBlank() } ?: step.label
        } catch (e: ExprError) {
            step.label
        }

        if (handle.cancelled.get()) scope.jobStatus = "cancelled"
        if (scope.jobStatus == "cancelled") {
            setRecord(recordIndex, StepStatus.SKIPPED, 0L)
            return
        }

        val shouldRun = try {
            Expr.shouldRun(step.condition, ctx)
        } catch (e: ExprError) {
            log.line("$indent✕ Invalid 'if' in \"$label\": ${e.message}")
            setRecord(recordIndex, StepStatus.FAILED, 0L)
            scope.jobStatus = "failure"
            return
        }
        if (!shouldRun) {
            log.line("$indent– Skipped: $label")
            setRecord(recordIndex, StepStatus.SKIPPED, 0L)
            step.id?.let { scope.steps[it] = mapOf("outputs" to emptyMap<String, String>(), "outcome" to "skipped", "conclusion" to "skipped") }
            return
        }

        setRecord(recordIndex, StepStatus.RUNNING, 0L)
        if (recordIndex != null) {
            log.line("")
            log.line("▶ Step ${recordIndex + 1}/$totalSteps: $label")
        } else {
            log.line("$indent▶ $label")
        }

        val begin = SystemClock.elapsedRealtime()
        val result = try {
            if (step.uses != null) execUses(step, scope, ctx, depth) else execRun(step, scope, ctx, depth)
        } catch (e: ExprError) {
            StepResult(false, false, emptyMap(), e.message ?: "Invalid expression")
        }
        val elapsed = SystemClock.elapsedRealtime() - begin
        val took = formatElapsed(elapsed)
        val continueOnError = try {
            Expr.interpolate(step.continueOnError, ctx).trim().equals("true", ignoreCase = true)
        } catch (e: ExprError) {
            false
        }

        step.id?.let {
            val outcome = when {
                result.ok -> "success"
                result.cancelled -> "cancelled"
                else -> "failure"
            }
            val conclusion = if (result.ok || (continueOnError && !result.cancelled)) "success" else outcome
            scope.steps[it] = mapOf("outputs" to result.outputs, "outcome" to outcome, "conclusion" to conclusion)
        }

        when {
            result.cancelled -> {
                log.line("$indent✕ Cancelled after $took")
                setRecord(recordIndex, StepStatus.CANCELLED, elapsed)
                scope.jobStatus = "cancelled"
            }
            result.ok -> {
                log.line("$indent✓ Succeeded in $took")
                setRecord(recordIndex, StepStatus.SUCCESS, elapsed)
            }
            else -> {
                result.message?.let { log.line("$indent✕ $it") }
                if (continueOnError) {
                    log.line("$indent✕ Failed after $took (continue-on-error)")
                    setRecord(recordIndex, StepStatus.FAILED, elapsed)
                } else {
                    log.line("$indent✕ Failed after $took")
                    setRecord(recordIndex, StepStatus.FAILED, elapsed)
                    scope.jobStatus = "failure"
                }
            }
        }
    }

    private fun failure(message: String) = StepResult(false, false, emptyMap(), message)

    private fun execRun(step: StepDef, scope: Scope, ctx: ExprContext, depth: Int): StepResult {
        val script = Expr.interpolate(step.script.orEmpty(), ctx)
        val relative = step.workdir?.let { Expr.interpolate(it, ctx) }?.takeIf { it.isNotBlank() } ?: "."
        val guestDir = resolveGuestDir(relative) ?: return failure("Working directory '$relative' is missing or outside the workspace")

        val n = ++fileCounter
        val scriptFile = File(runHostDir, "step-$n.sh")
        val outFile = File(runHostDir, "out-$n")
        val envFile = File(runHostDir, "env-$n")
        val pathFile = File(runHostDir, "path-$n")
        scriptFile.writeText(script + "\n")
        outFile.writeText("")
        envFile.writeText("")
        pathFile.writeText("")

        val shell = step.shell?.let { Expr.interpolate(it, ctx) }?.takeIf { it.isNotBlank() } ?: "sh"
        val argv = shellArgv(shell, "/run/gitara/step-$n.sh") ?: return failure("Unsupported shell '$shell'")

        val env = LinkedHashMap<String, String>(baseEnv)
        env.putAll(scope.env)
        for ((k, v) in step.env) env[k] = Expr.interpolate(v, ctx)
        env["GITARA_OUTPUT"] = "/run/gitara/out-$n"
        env["GITARA_ENV"] = "/run/gitara/env-$n"
        env["GITARA_PATH"] = "/run/gitara/path-$n"
        env["GITHUB_OUTPUT"] = "/run/gitara/out-$n"
        env["GITHUB_ENV"] = "/run/gitara/env-$n"
        env["GITHUB_PATH"] = "/run/gitara/path-$n"

        val remaining = scope.deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) return failure("The job timeout was reached")
        val minutes = step.timeoutMinutes?.let { Expr.interpolate(it, ctx).toDoubleOrNull() }
        val limit = if (minutes != null && minutes > 0) minOf(remaining, (minutes * 60_000).toLong()) else remaining

        val command = BoxRuntime.command(
            layout,
            listOf(repoDir.path to "/workspace", runHostDir.path to "/run/gitara"),
            guestDir,
            env,
            argv
        )
        val outcome = BoxProcess.run(command, BoxRuntime.hostEnvironment(layout), appContext.filesDir, limit, { handle.cancelled.get() }) {
            handleLine(it)
        }

        val outputs = try {
            parseKeyValues(outFile.readText())
        } catch (e: IOException) {
            emptyMap()
        }
        try {
            for ((k, v) in parseKeyValues(envFile.readText())) {
                if (ENV_NAME.matches(k)) scope.env[k] = v
            }
            val extra = pathFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
            if (extra.isNotEmpty()) {
                scope.env["PATH"] = (extra + (scope.env["PATH"] ?: BoxRuntime.GUEST_PATH)).joinToString(":")
            }
        } catch (e: IOException) {
            // the step did not write these files
        }

        return when (outcome) {
            is ProcessOutcome.Exit -> if (outcome.code == 0) {
                StepResult(true, false, outputs, null)
            } else {
                StepResult(false, false, outputs, "Process exited with code ${outcome.code}")
            }
            ProcessOutcome.TimedOut -> StepResult(false, false, outputs, "The step timed out")
            ProcessOutcome.Cancelled -> StepResult(false, true, outputs, null)
            is ProcessOutcome.Failure -> StepResult(false, false, outputs, outcome.message)
        }
    }

    private fun execUses(step: StepDef, scope: Scope, ctx: ExprContext, depth: Int): StepResult {
        if (depth >= 8) return failure("Actions are nested too deeply")
        val uses = Expr.interpolate(step.uses.orEmpty(), ctx).trim()
        if (!uses.startsWith("./")) return failure("Only local actions are supported: uses: ./path/to/action")

        val base = repoDir.canonicalFile
        val dir = try {
            File(base, uses.removePrefix("./")).canonicalFile
        } catch (e: IOException) {
            return failure("Cannot resolve $uses")
        }
        if (!(dir.path == base.path || dir.path.startsWith(base.path + File.separator))) {
            return failure("The action path is outside the repository")
        }
        val file = listOf("action.yml", "action.yaml", "action.toml").map { File(dir, it) }.firstOrNull { it.isFile }
            ?: return failure("No action.yml, action.yaml or action.toml found in $uses")
        val action = try {
            actionCache.getOrPut(file.path) { WorkflowParser.parseAction(file.name, file.readText(Charsets.UTF_8)) }
        } catch (e: WorkflowException) {
            return failure("Invalid action $uses: ${e.message}")
        }

        val given = LinkedHashMap<String, String>()
        for ((k, v) in step.withArgs) given[k] = Expr.interpolate(v, ctx)
        val values = LinkedHashMap<String, Any?>()
        for ((name, def) in action.inputs) {
            val value = given[name] ?: def.default?.let { Expr.interpolate(it, ctx) }
            if (value == null) {
                if (def.required) return failure("Input '$name' is required by $uses")
                values[name] = ""
            } else {
                values[name] = value
            }
        }
        for (key in given.keys) {
            if (key !in action.inputs) log.line("${"  ".repeat(depth)}! Unknown input '$key' for $uses")
        }

        val inner = Scope(values, LinkedHashMap(scope.env), scope.needs, "success", scope.deadline)
        for (inside in action.steps) runStep(null, inside, inner, depth + 1)

        val outputs = LinkedHashMap<String, String>()
        for ((name, expression) in action.outputs) {
            outputs[name] = try {
                Expr.interpolate(expression, context(inner))
            } catch (e: ExprError) {
                ""
            }
        }
        return when (inner.jobStatus) {
            "success" -> StepResult(true, false, outputs, null)
            "cancelled" -> StepResult(false, true, outputs, null)
            else -> StepResult(false, false, outputs, "Action $uses failed")
        }
    }

    private fun handleLine(line: String) {
        if (line.startsWith("::add-mask::")) {
            masker.add(line.removePrefix("::add-mask::"))
            return
        }
        val annotation = ANNOTATION.find(line)
        if (annotation != null) {
            val text = annotation.groupValues[2]
            log.line(
                when (annotation.groupValues[1]) {
                    "error" -> "✕ $text"
                    "warning" -> "! $text"
                    else -> "i $text"
                }
            )
            return
        }
        log.line(line)
    }

    private fun shellArgv(spec: String, file: String): List<String>? {
        val s = spec.trim()
        return when (s) {
            "sh" -> listOf("/bin/sh", "-e", file)
            "ash" -> listOf("/bin/ash", "-e", file)
            "bash" -> listOf("/bin/bash", "-e", "-o", "pipefail", file)
            "python", "python3" -> listOf("/usr/bin/python3", file)
            "node" -> listOf("/usr/bin/node", file)
            else -> if (s.contains("{0}")) s.split(Regex("\\s+")).map { it.replace("{0}", file) } else null
        }
    }

    private fun resolveGuestDir(relative: String): String? {
        val base = repoDir.canonicalFile
        val target = try {
            File(base, relative).canonicalFile
        } catch (e: IOException) {
            return null
        }
        if (!(target.path == base.path || target.path.startsWith(base.path + File.separator))) return null
        if (!target.isDirectory) return null
        return "/workspace" + target.path.removePrefix(base.path)
    }

    private fun currentBranch(): String {
        return try {
            val full = repo.getBranchName().orEmpty()
            val isSha = full.length == 40 && full.all { it.isDigit() || it in 'a'..'f' }
            if (isSha) full.take(7) else Repo.getCommitDisplayName(full).orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatElapsed(ms: Long): String {
        val seconds = ms / 1000
        return when {
            seconds < 1 -> "<1s"
            seconds < 60 -> "${seconds}s"
            else -> "${seconds / 60}m ${String.format(Locale.ROOT, "%02d", seconds % 60)}s"
        }
    }
}

object WorkflowEngine {

    private val mutableState = MutableStateFlow(EngineSnapshot())
    val state: StateFlow<EngineSnapshot> = mutableState.asStateFlow()

    private val handles = ConcurrentHashMap<Int, RunHandle>()

    fun isRunning(repoId: Int): Boolean = handles.containsKey(repoId)

    fun cancel(repoId: Int) {
        handles[repoId]?.cancelled?.set(true)
    }

    fun cancelAll() {
        handles.values.forEach { it.cancelled.set(true) }
    }

    fun start(context: Context, repo: Repo, definition: WorkflowDefinition, inputs: Map<String, String>): StartResult {
        val appContext = context.applicationContext
        if (!BoxManager.isReady(appContext)) {
            return StartResult.Failed("The Gitara Box is not installed yet. Install it from the Actions tab.")
        }
        for ((name, def) in definition.inputs) {
            if (def.required && def.default == null && inputs[name].isNullOrBlank()) {
                return StartResult.Failed("Input '$name' is required.")
            }
        }
        val repoId = repo.getID()
        val repoDir = try {
            repo.getDir().canonicalFile
        } catch (e: IOException) {
            return StartResult.Failed("Cannot resolve the repository folder: ${e.message}")
        }
        if (!repoDir.isDirectory) {
            return StartResult.Failed("Repository folder does not exist: ${repoDir.absolutePath}")
        }

        val store = WorkflowStore(appContext)
        val runId = store.newRunId()
        val handle = RunHandle()
        if (handles.putIfAbsent(repoId, handle) != null) return StartResult.AlreadyRunning

        val startedAt = System.currentTimeMillis()
        val runner = Runner(appContext, repo, repoDir, definition, runId, inputs, handle, startedAt, store) { steps ->
            publish(repoId, steps)
        }
        mutableState.update {
            it.copy(
                live = it.live + (repoId to LiveRun(runId, repoId, definition.fileName, definition.name, startedAt, runner.initialRecords()))
            )
        }

        try {
            WorkflowService.start(appContext)
        } catch (e: Exception) {
            Timber.w(e, "Could not start the workflow foreground service")
        }

        Thread({
            try {
                runner.execute()
            } catch (t: Throwable) {
                Timber.e(t, "Workflow run crashed")
            } finally {
                handles.remove(repoId)
                mutableState.update { it.copy(live = it.live - repoId, finishedTick = it.finishedTick + 1) }
            }
        }, "gitara-workflow-$repoId").start()

        return StartResult.Started(runId)
    }

    private fun publish(repoId: Int, records: List<StepRecord>) {
        mutableState.update { state ->
            val current = state.live[repoId] ?: return@update state
            state.copy(live = state.live + (repoId to current.copy(steps = records)))
        }
    }
}