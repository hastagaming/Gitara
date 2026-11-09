package io.gitara.workflow

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.sheimi.sgit.R

class WorkflowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(this, WorkflowEngine.state.value), type)
        observer = scope.launch {
            WorkflowEngine.state.collect { snapshot ->
                if (snapshot.live.isEmpty()) {
                    ServiceCompat.stopForeground(this@WorkflowService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    val manager = ContextCompat.getSystemService(this@WorkflowService, android.app.NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(this@WorkflowService, snapshot))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_ALL) WorkflowEngine.cancelAll()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        WorkflowEngine.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "gitara_workflows"
        private const val NOTIFICATION_ID = 4107
        private const val ACTION_CANCEL_ALL = "io.gitara.workflow.CANCEL_ALL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, WorkflowService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Workflow runs")
                .setDescription("Shows progress while a workflow is running")
                .build()
            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }

        private fun buildNotification(context: Context, snapshot: EngineSnapshot): Notification {
            val runs = snapshot.live.values.sortedBy { it.startedAt }
            val title = when {
                runs.isEmpty() -> "Workflows"
                runs.size == 1 -> runs[0].workflowName
                else -> "${runs.size} workflows running"
            }
            val text = runs.firstOrNull()?.let { run ->
                val index = run.steps.indexOfFirst { it.status == StepStatus.RUNNING }
                if (index >= 0) "Step ${index + 1}/${run.steps.size}: ${run.steps[index].name}" else "Starting…"
            } ?: "Starting…"

            val immutable = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
            val cancel = PendingIntent.getService(
                context,
                0,
                Intent(context, WorkflowService::class.java).setAction(ACTION_CANCEL_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or immutable
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_UPDATE_CURRENT or immutable)
            }

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_workflow)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(launch)
                .addAction(0, "Cancel", cancel)
                .build()
        }
    }
}