package io.gitara.workflow

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.exceptions.YamlEngineException

class WorkflowException(message: String) : Exception(message)

data class InputDef(val description: String, val required: Boolean, val default: String?)

data class StepDef(
    val id: String?,
    val name: String?,
    val condition: String?,
    val uses: String?,
    val withArgs: Map<String, String>,
    val script: String?,
    val shell: String?,
    val workdir: String?,
    val env: Map<String, String>,
    val continueOnError: String,
    val timeoutMinutes: String?
) {
    val label: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: uses
            ?: script?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()?.take(60)
            ?: "step"
}

data class JobDef(
    val id: String,
    val name: String?,
    val needs: List<String>,
    val condition: String?,
    val env: Map<String, String>,
    val timeoutMinutes: Int?,
    val outputs: Map<String, String>,
    val steps: List<StepDef>
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: id
}

data class WorkflowDefinition(
    val fileName: String,
    val name: String,
    val env: Map<String, String>,
    val inputs: Map<String, InputDef>,
    val jobs: List<JobDef>
) {
    val stepCount: Int get() = jobs.sumOf { it.steps.size }
}

data class ActionDef(
    val name: String,
    val inputs: Map<String, InputDef>,
    val outputs: Map<String, String>,
    val steps: List<StepDef>
)

sealed class WorkflowParseResult {
    abstract val fileName: String

    data class Ok(override val fileName: String, val definition: WorkflowDefinition) : WorkflowParseResult()

    data class Failed(override val fileName: String, val message: String) : WorkflowParseResult()
}

enum class RunStatus { RUNNING, SUCCESS, FAILED, CANCELLED }

enum class StepStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED, CANCELLED }

data class StepRecord(
    val name: String,
    val status: StepStatus,
    val durationMs: Long
)

data class RunRecord(
    val id: String,
    val repoId: Int,
    val workflowFile: String,
    val workflowName: String,
    val status: RunStatus,
    val startedAt: Long,
    val finishedAt: Long,
    val steps: List<StepRecord>,
    val summary: String
)

data class LiveRun(
    val id: String,
    val repoId: Int,
    val workflowFile: String,
    val workflowName: String,
    val startedAt: Long,
    val steps: List<StepRecord>
)

object WorkflowParser {
    private val idPattern = Regex("^[A-Za-z_][A-Za-z0-9_-]*$")
    private val envPattern = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

    fun parse(fileName: String, text: String): WorkflowParseResult = try {
        WorkflowParseResult.Ok(fileName, workflow(fileName, load(text)))
    } catch (e: WorkflowException) {
        WorkflowParseResult.Failed(fileName, e.message ?: "Invalid workflow")
    } catch (e: YamlEngineException) {
        WorkflowParseResult.Failed(fileName, "Invalid YAML: " + (e.message ?: "").take(300))
    }

    fun parseAction(text: String): ActionDef = try {
        action(load(text))
    } catch (e: YamlEngineException) {
        throw WorkflowException("Invalid YAML: " + (e.message ?: "").take(300))
    }

    private fun load(text: String): Any? = Load(LoadSettings.builder().build()).loadFromString(text)

    private fun asMap(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *>) return null
        val out = LinkedHashMap<String, Any?>()
        for ((key, item) in value) out[key.toString()] = item
        return out
    }

    private fun asList(value: Any?): List<Any?>? = (value as? List<*>)?.toList()

    private fun scalar(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Boolean -> value.toString()
        is Double -> if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()
        is Number -> value.toString()
        else -> throw WorkflowException("Expected a plain value but found a collection")
    }

    private fun stringMap(value: Any?, where: String, envNames: Boolean): Map<String, String> {
        if (value == null) return emptyMap()
        val map = asMap(value) ?: throw WorkflowException("$where must be a mapping")
        val out = LinkedHashMap<String, String>()
        for ((key, item) in map) {
            if (envNames && !envPattern.matches(key)) throw WorkflowException("$where has an invalid variable name '$key'")
            if (item is Map<*, *> || item is List<*>) throw WorkflowException("$where.$key must be a plain value")
            out[key] = scalar(item)
        }
        return out
    }

    private fun parseInputs(value: Any?, where: String): Map<String, InputDef> {
        val map = asMap(value) ?: return emptyMap()
        val out = LinkedHashMap<String, InputDef>()
        for ((name, raw) in map) {
            val d = asMap(raw) ?: emptyMap()
            out[name] = InputDef(
                description = d["description"]?.let { scalar(it) }.orEmpty(),
                required = d["required"] == true,
                default = d["default"]?.let { scalar(it) }
            )
        }
        return out
    }

    private fun parseStep(raw: Any?, where: String): StepDef {
        val m = asMap(raw) ?: throw WorkflowException("$where must be a mapping")
        val script = m["run"]?.let { scalar(it) }
        val uses = m["uses"]?.let { scalar(it) }
        if ((script == null) == (uses == null)) throw WorkflowException("$where needs exactly one of 'run' or 'uses'")
        val id = m["id"]?.let { scalar(it) }
        if (id != null && !idPattern.matches(id)) throw WorkflowException("$where has an invalid id '$id'")
        return StepDef(
            id = id,
            name = m["name"]?.let { scalar(it) },
            condition = m["if"]?.let { scalar(it) },
            uses = uses,
            withArgs = stringMap(m["with"], "$where.with", false),
            script = script,
            shell = m["shell"]?.let { scalar(it) },
            workdir = m["working-directory"]?.let { scalar(it) },
            env = stringMap(m["env"], "$where.env", true),
            continueOnError = m["continue-on-error"]?.let { scalar(it) } ?: "false",
            timeoutMinutes = m["timeout-minutes"]?.let { scalar(it) }
        )
    }

    private fun parseSteps(value: Any?, where: String): List<StepDef> {
        val list = asList(value) ?: throw WorkflowException("$where needs a list of steps")
        if (list.isEmpty()) throw WorkflowException("$where needs at least one step")
        return list.mapIndexed { index, raw -> parseStep(raw, "$where[${index + 1}]") }
    }

    private fun parseJob(id: String, raw: Any?): JobDef {
        if (!idPattern.matches(id)) throw WorkflowException("Invalid job id '$id'")
        val m = asMap(raw) ?: throw WorkflowException("Job '$id' must be a mapping")
        val needs = when (val n = m["needs"]) {
            null -> emptyList()
            is List<*> -> n.map { scalar(it) }
            else -> listOf(scalar(n))
        }
        val timeout = m["timeout-minutes"]?.let { scalar(it).toDoubleOrNull()?.toInt() }
        if (timeout != null && timeout < 1) throw WorkflowException("Job '$id': timeout-minutes must be at least 1")
        return JobDef(
            id = id,
            name = m["name"]?.let { scalar(it) },
            needs = needs,
            condition = m["if"]?.let { scalar(it) },
            env = stringMap(m["env"], "jobs.$id.env", true),
            timeoutMinutes = timeout,
            outputs = stringMap(m["outputs"], "jobs.$id.outputs", false),
            steps = parseSteps(m["steps"], "jobs.$id.steps")
        )
    }

    private fun order(jobs: List<JobDef>): List<JobDef> {
        val byId = jobs.associateBy { it.id }
        val result = ArrayList<JobDef>()
        val state = HashMap<String, Int>()
        fun visit(job: JobDef) {
            val current = state[job.id]
            if (current == 2) return
            if (current == 1) throw WorkflowException("Circular 'needs' involving job '${job.id}'")
            state[job.id] = 1
            for (need in job.needs) {
                visit(byId[need] ?: throw WorkflowException("Job '${job.id}' needs unknown job '$need'"))
            }
            state[job.id] = 2
            result.add(job)
        }
        jobs.forEach { visit(it) }
        return result
    }

    private fun workflow(fileName: String, doc: Any?): WorkflowDefinition {
        val root = asMap(doc) ?: throw WorkflowException("The workflow must be a YAML mapping")
        val jobsMap = asMap(root["jobs"]) ?: throw WorkflowException("'jobs' is required")
        if (jobsMap.isEmpty()) throw WorkflowException("'jobs' needs at least one job")
        val trigger = asMap(root["on"])
        val manual = asMap(trigger?.get("manual")) ?: asMap(trigger?.get("workflow_dispatch"))
        return WorkflowDefinition(
            fileName = fileName,
            name = root["name"]?.let { scalar(it) }?.ifBlank { null } ?: fileName.substringBeforeLast('.'),
            env = stringMap(root["env"], "env", true),
            inputs = parseInputs(manual?.get("inputs"), "on.manual.inputs"),
            jobs = order(jobsMap.map { (id, raw) -> parseJob(id, raw) })
        )
    }

    private fun action(doc: Any?): ActionDef {
        val root = asMap(doc) ?: throw WorkflowException("The action must be a YAML mapping")
        val runs = asMap(root["runs"]) ?: throw WorkflowException("'runs' is required")
        if (runs["using"]?.let { scalar(it) } != "composite") throw WorkflowException("Only 'using: composite' actions are supported")
        val outputs = LinkedHashMap<String, String>()
        asMap(root["outputs"])?.forEach { (name, raw) ->
            val value = asMap(raw)?.get("value")?.let { scalar(it) } ?: ""
            outputs[name] = value
        }
        return ActionDef(
            name = root["name"]?.let { scalar(it) }.orEmpty(),
            inputs = parseInputs(root["inputs"], "inputs"),
            outputs = outputs,
            steps = parseSteps(runs["steps"], "runs.steps")
        )
    }
}