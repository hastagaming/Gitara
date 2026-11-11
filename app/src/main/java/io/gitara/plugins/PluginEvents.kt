package io.gitara.plugins

import android.content.Context
import io.gitara.box.BoxManager
import io.gitara.workflow.RunStatus
import io.gitara.workflow.StartResult
import io.gitara.workflow.WorkflowEngine
import me.sheimi.sgit.database.models.Repo
import timber.log.Timber
import java.util.Locale

object PluginEvents {
    private const val MAX_WAIT_MS = 10L * 60L * 1000L

    @JvmStatic
    fun repoOpened(context: Context, repo: Repo) {
        fire(context, repo, "repo-opened", "", emptyMap())
    }

    @JvmStatic
    fun operationFinished(context: Context, repo: Repo, messageRes: Int, success: Boolean) {
        val operation = try {
            context.resources.getResourceEntryName(messageRes).removeSuffix("_msg_init")
        } catch (e: Exception) {
            return
        }
        fire(context, repo, "after-operation", operation, mapOf("GITARA_RESULT" to if (success) "success" else "failure"))
    }

    fun workflowFinished(context: Context, repo: Repo, workflow: String, status: RunStatus) {
        fire(
            context,
            repo,
            "workflow-finished",
            "",
            mapOf(
                "GITARA_RESULT" to status.name.lowercase(Locale.ROOT),
                "GITARA_FINISHED_WORKFLOW" to workflow
            )
        )
    }

    private fun fire(context: Context, repo: Repo, event: String, operation: String, extra: Map<String, String>) {
        val app = context.applicationContext
        Thread({
            try {
                val store = PluginStore(app)
                val hooks = store.hooks(event).filter { (_, hook) ->
                    hook.operations.isEmpty() || operation in hook.operations
                }
                if (hooks.isEmpty() || !BoxManager.isReady(app)) return@Thread
                for ((info, hook) in hooks) {
                    waitForIdle(repo.getID())
                    val env = LinkedHashMap<String, String>(extra)
                    env["GITARA_EVENT"] = event
                    if (operation.isNotEmpty()) env["GITARA_OPERATION"] = operation
                    val result = WorkflowEngine.start(app, repo, store.hookDefinition(info, hook, env), emptyMap())
                    if (result is StartResult.Failed) {
                        Timber.w("Plugin hook ${info.id}/${hook.id} did not start: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Plugin hooks failed")
            }
        }, "gitara-plugin-hooks").start()
    }

    private fun waitForIdle(repoId: Int) {
        val end = System.currentTimeMillis() + MAX_WAIT_MS
        while (WorkflowEngine.isRunning(repoId) && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
        }
    }
}