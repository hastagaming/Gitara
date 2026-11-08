package io.gitara.ui.workflow

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.gitara.box.BoxManager
import io.gitara.box.BoxState
import io.gitara.secrets.SecretStore
import io.gitara.ui.repodetail.RepoDetailController
import io.gitara.workflow.LiveRun
import io.gitara.workflow.RunRecord
import io.gitara.workflow.RunStatus
import io.gitara.workflow.StartResult
import io.gitara.workflow.StepRecord
import io.gitara.workflow.StepStatus
import io.gitara.workflow.WorkflowDefinition
import io.gitara.workflow.WorkflowEngine
import io.gitara.workflow.WorkflowParseResult
import io.gitara.workflow.WorkflowStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.sheimi.sgit.database.models.Repo
import java.util.Locale

private val SuccessGreen = Color(0xFF1A7F37)
private const val MAX_LOG_LINES = 5000

private data class RunTarget(val runId: String, val title: String)

private data class RunRequest(val definition: WorkflowDefinition, val inputs: Map<String, String>)

private class WorkflowTemplate(
    val title: String,
    val description: String,
    val baseName: String,
    val content: String,
    val extras: List<Pair<String, String>>,
    val extension: String = "yml"
)

private fun yaml(text: String): String = text.trimIndent().replace('§', '$') + "\n"

private fun workflowTemplates(): List<WorkflowTemplate> = listOf(
    WorkflowTemplate(
        title = "Hello Gitara",
        description = "Inputs, step outputs and expressions. Runs in a few seconds.",
        baseName = "hello",
        content = yaml(
            """
            name: Hello Gitara

            on:
              manual:
                inputs:
                  who:
                    description: Who to greet
                    default: world

            jobs:
              hello:
                name: Say hello
                runs-on: gitara
                steps:
                  - name: Greet
                    run: |
                      echo "Hello, §{{ inputs.who }}!"
                      echo "Repository: §{{ gitara.repository }}"
                      echo "Branch: §{{ gitara.ref_name }}"
                      uname -a
                      ls -la
                  - name: Produce an output
                    id: stamp
                    run: echo "time=§(date +%s)" >> "§GITARA_OUTPUT"
                  - name: Use the output
                    run: |
                      echo "Stamp was §{{ steps.stamp.outputs.time }}"
            """
        ),
        extras = emptyList()
    ),
    WorkflowTemplate(
        title = "Gradle build (JDK 17)",
        description = "Installs JDK 17 with apk and runs ./gradlew build. Android apps also need the Android SDK inside the Box.",
        baseName = "gradle-build",
        content = yaml(
            """
            name: Gradle build

            jobs:
              build:
                name: Build with Gradle
                runs-on: gitara
                timeout-minutes: 120
                env:
                  GRADLE_OPTS: -Dorg.gradle.daemon=false -Xmx2g
                steps:
                  - name: Install toolchain
                    run: apk add --no-cache bash git openjdk17 curl unzip
                  - name: Build
                    run: |
                      chmod +x ./gradlew
                      ./gradlew build --no-daemon
                  - name: List build outputs
                    if: §{{ always() }}
                    run: |
                      find . -path '*/build/libs/*' -o -path '*/build/outputs/*' | head -50
            """
        ),
        extras = emptyList()
    ),
    WorkflowTemplate(
        title = "Custom action",
        description = "A workflow that calls your own composite action from .gitara/actions/greet.",
        baseName = "use-action",
        content = yaml(
            """
            name: Use a custom action

            jobs:
              demo:
                runs-on: gitara
                steps:
                  - id: greet
                    uses: ./.gitara/actions/greet
                    with:
                      name: Gitara
                  - name: Show the action output
                    run: |
                      echo "Action said: §{{ steps.greet.outputs.message }}"
            """
        ),
        extras = listOf(
            ".gitara/actions/greet/action.yml" to yaml(
                """
                name: Greet
                description: Print a greeting and expose it as an output
                inputs:
                  name:
                    description: Who to greet
                    default: Gitara
                outputs:
                  message:
                    description: The greeting
                    value: §{{ steps.build.outputs.message }}
                runs:
                  using: composite
                  steps:
                    - id: build
                      shell: sh
                      run: echo "message=Hello, §{{ inputs.name }}!" >> "§GITARA_OUTPUT"
                    - shell: sh
                      run: echo "§{{ steps.build.outputs.message }}"
                """
            )
        )
    )
)

private fun tomlTemplates(): List<WorkflowTemplate> = listOf(
    WorkflowTemplate(
        title = "Hello Gitara (TOML)",
        description = "The same hello workflow written in TOML.",
        baseName = "hello",
        extension = "toml",
        content = yaml(
            """
            name = "Hello Gitara"

            [on.manual.inputs.who]
            description = "Who to greet"
            default = "world"

            [jobs.hello]
            name = "Say hello"
            runs-on = "gitara"

            [[jobs.hello.steps]]
            name = "Greet"
            run = ${'"'}${'"'}${'"'}
            echo "Hello, §{{ inputs.who }}!"
            echo "Repository: §{{ gitara.repository }}"
            uname -a
            ${'"'}${'"'}${'"'}

            [[jobs.hello.steps]]
            name = "Produce an output"
            id = "stamp"
            run = 'echo "time=§(date +%s)" >> "§GITARA_OUTPUT"'

            [[jobs.hello.steps]]
            name = "Use the output"
            run = 'echo "Stamp was §{{ steps.stamp.outputs.time }}"'
            """
        ),
        extras = emptyList()
    ),
    WorkflowTemplate(
        title = "Custom action (TOML)",
        description = "A workflow that calls a composite action defined in action.toml.",
        baseName = "use-action",
        extension = "toml",
        content = yaml(
            """
            name = "Use a custom action"

            [jobs.demo]
            runs-on = "gitara"

            [[jobs.demo.steps]]
            id = "greet"
            uses = "./.gitara/actions/greet-toml"

            [jobs.demo.steps.with]
            name = "Gitara"

            [[jobs.demo.steps]]
            name = "Show the action output"
            run = 'echo "Action said: §{{ steps.greet.outputs.message }}"'
            """
        ),
        extras = listOf(
            ".gitara/actions/greet-toml/action.toml" to yaml(
                """
                name = "Greet"
                description = "Print a greeting and expose it as an output"

                [inputs.name]
                description = "Who to greet"
                default = "Gitara"

                [outputs.message]
                description = "The greeting"
                value = "§{{ steps.build.outputs.message }}"

                [runs]
                using = "composite"

                [[runs.steps]]
                id = "build"
                shell = "sh"
                run = 'echo "message=Hello, §{{ inputs.name }}!" >> "§GITARA_OUTPUT"'

                [[runs.steps]]
                shell = "sh"
                run = 'echo "§{{ steps.build.outputs.message }}"'
                """
            )
        )
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowPane(repo: Repo, controller: RepoDetailController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val store = remember { WorkflowStore(appContext) }
    val secretStore = remember { SecretStore(appContext) }
    val repoId = remember(repo) { repo.getID() }
    val repoDir = remember(repo) { repo.getDir() }

    LaunchedEffect(Unit) { BoxManager.refresh(appContext) }
    val box by BoxManager.state.collectAsState()

    var reloadTick by remember { mutableIntStateOf(0) }
    val engine by WorkflowEngine.state.collectAsState()
    val live = engine.live[repoId]

    val workflows by produceState<List<WorkflowParseResult>?>(null, reloadTick) {
        value = withContext(Dispatchers.IO) { store.discover(repoDir) }
    }
    val history by produceState(emptyList<RunRecord>(), engine.finishedTick, reloadTick) {
        value = withContext(Dispatchers.IO) { store.loadRuns(repoId) }
    }
    var secretsTick by remember { mutableIntStateOf(0) }
    val secretCount by produceState(0, secretsTick) {
        value = withContext(Dispatchers.IO) {
            secretStore.names(SecretStore.GLOBAL).size + secretStore.names(SecretStore.repoScope(repoId)).size
        }
    }

    var openRun by remember { mutableStateOf<RunTarget?>(null) }
    var showTemplates by remember { mutableStateOf(false) }
    var showSecrets by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var inputsFor by remember { mutableStateOf<WorkflowDefinition?>(null) }
    var pending by remember { mutableStateOf<RunRequest?>(null) }
    var resume by remember { mutableStateOf<RunRequest?>(null) }
    var notificationAsked by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(controller.logOpen) {
        if (!controller.logOpen) openRun = null
    }

    fun say(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun startRun(request: RunRequest) {
        when (val result = WorkflowEngine.start(appContext, repo, request.definition, request.inputs)) {
            is StartResult.Started -> {
                openRun = RunTarget(result.runId, request.definition.name)
                controller.openLog()
            }
            StartResult.AlreadyRunning -> say("A workflow is already running in this repository")
            is StartResult.Failed -> say(result.message)
        }
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        val request = pending
        pending = null
        if (request != null) resume = request
    }

    fun gate(request: RunRequest, afterNotification: Boolean) {
        if (!BoxManager.isReady(appContext)) {
            say("Install the Gitara Box first")
            return
        }
        if (!afterNotification &&
            Build.VERSION.SDK_INT >= 33 &&
            !notificationAsked &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationAsked = true
            pending = request
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startRun(request)
    }

    LaunchedEffect(resume) {
        val request = resume
        if (request != null) {
            resume = null
            gate(request, true)
        }
    }

    fun createFromTemplate(template: WorkflowTemplate) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { store.createTemplate(repoDir, template.baseName, template.extension, template.content, template.extras) }
            }
            result.onSuccess {
                reloadTick++
                snackbar.showSnackbar("Created .gitara/workflows/${it.name}")
            }.onFailure {
                snackbar.showSnackbar("Could not create workflow: ${it.message}")
            }
        }
    }

    val currentLog = openRun

    Box(modifier) {
        if (currentLog != null) {
            val steps = if (live != null && live.id == currentLog.runId) {
                live.steps
            } else {
                history.firstOrNull { it.id == currentLog.runId }?.steps.orEmpty()
            }
            LogViewer(
                store = store,
                repoId = repoId,
                target = currentLog,
                steps = steps,
                isLive = live != null && live.id == currentLog.runId,
                onBack = {
                    controller.closeLog()
                    openRun = null
                },
                onCopy = { text ->
                    copyToClipboard(context, text)
                    say("Log copied")
                }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "box") {
                    BoxCard(
                        state = box,
                        onInstall = { BoxManager.install(appContext) },
                        onReset = { confirmReset = true }
                    )
                }
                item(key = "secrets") {
                    SecretsCard(count = secretCount, onManage = { showSecrets = true })
                }

                if (live != null) {
                    item(key = "live") {
                        LiveRunCard(
                            run = live,
                            onOpen = {
                                openRun = RunTarget(live.id, live.workflowName)
                                controller.openLog()
                            },
                            onCancel = {
                                WorkflowEngine.cancel(repoId)
                                say("Cancelling…")
                            }
                        )
                    }
                }

                item(key = "workflows-header") {
                    SectionHeader(title = "Workflows", actionLabel = "New", onAction = { showTemplates = true })
                }

                val parsed = workflows
                when {
                    parsed == null -> item(key = "workflows-loading") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                    parsed.isEmpty() -> item(key = "workflows-empty") {
                        EmptyWorkflows(onCreate = { showTemplates = true })
                    }
                    else -> items(parsed, key = { "wf-" + it.fileName }) { entry ->
                        when (entry) {
                            is WorkflowParseResult.Ok -> WorkflowCard(
                                definition = entry.definition,
                                running = live != null,
                                onRun = {
                                    if (entry.definition.inputs.isEmpty()) {
                                        gate(RunRequest(entry.definition, emptyMap()), false)
                                    } else {
                                        inputsFor = entry.definition
                                    }
                                },
                                onDelete = { deleteTarget = entry.fileName }
                            )
                            is WorkflowParseResult.Failed -> ErrorCard(
                                fileName = entry.fileName,
                                message = entry.message,
                                onDelete = { deleteTarget = entry.fileName }
                            )
                        }
                    }
                }

                item(key = "history-header") {
                    Spacer(Modifier.height(6.dp))
                    SectionHeader(title = "Run history", actionLabel = null, onAction = {})
                }
                if (history.isEmpty()) {
                    item(key = "history-empty") {
                        Text(
                            "No runs yet. Run a workflow and it will show up here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(history, key = { "run-" + it.id }) { run ->
                        RunRow(run = run, onClick = {
                            openRun = RunTarget(run.id, run.workflowName)
                            controller.openLog()
                        })
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    if (showTemplates) {
        ModalBottomSheet(onDismissRequest = { showTemplates = false }) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    "New workflow",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                (workflowTemplates() + tomlTemplates()).forEach { template ->
                    ListItem(
                        headlineContent = { Text(template.title) },
                        supportingContent = { Text(template.description) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            showTemplates = false
                            createFromTemplate(template)
                        }
                    )
                }
            }
        }
    }

    if (showSecrets) {
        SecretsSheet(
            store = secretStore,
            repoId = repoId,
            onDismiss = {
                showSecrets = false
                secretsTick++
            }
        )
    }

    inputsFor?.let { definition ->
        InputsDialog(
            definition = definition,
            onRun = { values ->
                inputsFor = null
                gate(RunRequest(definition, values), false)
            },
            onDismiss = { inputsFor = null }
        )
    }

    deleteTarget?.let { fileName ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete workflow?") },
            text = { Text("\"$fileName\" will be removed from .gitara/workflows in this repository.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val deleted = withContext(Dispatchers.IO) { store.deleteWorkflow(repoDir, fileName) }
                        reloadTick++
                        snackbar.showSnackbar(if (deleted) "Deleted $fileName" else "Could not delete $fileName")
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the Gitara Box?") },
            text = { Text("All packages and files installed inside the Box are removed. Your repositories are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    BoxManager.reset(appContext)
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String?, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        if (actionLabel != null) {
            TextButton(onClick = onAction) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun BoxCard(state: BoxState, onInstall: () -> Unit, onReset: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "Gitara Box",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            when (state) {
                BoxState.Checking -> Text(
                    "Checking…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
                BoxState.NotInstalled -> {
                    Text(
                        "A private Linux environment (Alpine) inside Gitara where workflows run. " +
                            "It downloads about 4 MB once. After that you can add tools with apk add.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onInstall) { Text("Install Gitara Box") }
                }
                is BoxState.Installing -> {
                    Text(state.stage, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    val progress = state.progress
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                is BoxState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Ready", style = MaterialTheme.typography.labelLarge, color = SuccessGreen, fontWeight = FontWeight.SemiBold)
                        Text(state.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onReset) { Text("Reset", color = scheme.error) }
                }
                is BoxState.Failed -> {
                    Text(state.message, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onInstall) { Text("Try again") }
                }
            }
        }
    }
}

@Composable
private fun SecretsCard(count: Int, onManage: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onManage,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outline)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Secrets",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (count == 0) "None yet. Use them as \${{ secrets.NAME }}." else "$count stored, encrypted on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onManage) { Text("Manage") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecretsSheet(store: SecretStore, repoId: Int, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val sheetSnackbar = remember { SnackbarHostState() }
    var repoOnly by remember { mutableStateOf(true) }
    val storeScope = if (repoOnly) SecretStore.repoScope(repoId) else SecretStore.GLOBAL
    var tick by remember { mutableIntStateOf(0) }
    val names by produceState(emptyList<String>(), storeScope, tick) {
        value = withContext(Dispatchers.IO) { store.names(storeScope) }
    }
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var deleteName by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Secrets", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = repoOnly, onClick = { repoOnly = true }, label = { Text("This repository") })
                FilterChip(selected = !repoOnly, onClick = { repoOnly = false }, label = { Text("All repositories") })
            }
            Text(
                "Values are never shown again. A repository secret overrides a global one with the same name. " +
                    "Values shorter than 4 characters are not masked in logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (names.isEmpty()) {
                Text("No secrets in this scope yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                names.forEach { secretName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            secretName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { deleteName = secretName }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete $secretName")
                        }
                    }
                }
            }
            HorizontalDivider()
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim() },
                label = { Text("Name") },
                placeholder = { Text("KEYSTORE_PASSWORD") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text("Value") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    val saveName = name
                    val saveValue = secret
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { runCatching { store.put(storeScope, saveName, saveValue) } }
                        result.onSuccess {
                            name = ""
                            secret = ""
                            tick++
                            sheetSnackbar.showSnackbar("Saved $saveName")
                        }.onFailure {
                            sheetSnackbar.showSnackbar(it.message ?: "Could not save the secret")
                        }
                    }
                },
                enabled = name.isNotEmpty() && secret.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save secret") }
            SnackbarHost(hostState = sheetSnackbar)
        }
    }

    deleteName?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteName = null },
            title = { Text("Delete secret?") },
            text = { Text("$target will be removed from this scope. Workflows that use it will get an empty value.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteName = null
                    scope.launch {
                        withContext(Dispatchers.IO) { store.remove(storeScope, target) }
                        tick++
                        sheetSnackbar.showSnackbar("Deleted $target")
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteName = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InputsDialog(
    definition: WorkflowDefinition,
    onRun: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val values = remember {
        mutableStateMapOf<String, String>().also { map ->
            definition.inputs.forEach { (key, input) -> map[key] = input.default.orEmpty() }
        }
    }
    val missing = definition.inputs.any { (key, input) -> input.required && values[key].isNullOrBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run ${definition.name}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                definition.inputs.forEach { (key, input) ->
                    OutlinedTextField(
                        value = values[key].orEmpty(),
                        onValueChange = { values[key] = it },
                        label = { Text(if (input.required) "$key *" else key) },
                        placeholder = { Text(input.description) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRun(values.toMap()) }, enabled = !missing) { Text("Run") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun WorkflowCard(
    definition: WorkflowDefinition,
    running: Boolean,
    onRun: () -> Unit,
    onDelete: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                definition.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                ".gitara/workflows/${definition.fileName}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(if (definition.jobs.size == 1) "1 job" else "${definition.jobs.size} jobs", scheme.primary)
                Pill(if (definition.stepCount == 1) "1 step" else "${definition.stepCount} steps", scheme.onSurfaceVariant)
                if (definition.inputs.isNotEmpty()) Pill("${definition.inputs.size} input(s)", scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onRun, enabled = !running) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Run")
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete workflow", tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(fileName: String, message: String, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.6f))
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete workflow", tint = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyWorkflows(onCreate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        Text(
            "No workflows yet",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "A workflow is a YAML file in .gitara/workflows/ inside the repository. " +
                "Create one from a template and edit it in your editor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCreate) { Text("Create from template") }
    }
}

@Composable
private fun LiveRunCard(run: LiveRun, onOpen: () -> Unit, onCancel: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.primaryContainer,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Running",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.primary
                )
                Text(
                    formatDuration(now - run.startedAt),
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurfaceVariant
                )
            }
            Text(
                run.workflowName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            run.steps.forEachIndexed { index, step -> StepLine(index + 1, step) }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onOpen) { Text("View log") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel", color = scheme.error) }
            }
        }
    }
}

@Composable
private fun StepLine(number: Int, step: StepRecord) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stepGlyph(step.status),
            color = stepColor(step.status),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(22.dp)
        )
        Text(
            "$number. ${step.name}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (step.durationMs > 0) {
            Text(
                formatDuration(step.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RunRow(run: RunRecord, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val color = runColor(run.status)
    val duration = formatDuration((run.finishedAt - run.startedAt).coerceAtLeast(0L))
    val whenText = if (run.startedAt > 0L) {
        DateUtils.getRelativeTimeSpanString(
            run.startedAt,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        ).toString()
    } else {
        ""
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outline)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                runGlyph(run.status),
                color = color,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(26.dp)
            )
            Column(Modifier.weight(1f)) {
                Text(
                    run.workflowName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (run.summary.isNotBlank()) {
                    Text(
                        run.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = color,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    listOf(whenText, duration).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LogViewer(
    store: WorkflowStore,
    repoId: Int,
    target: RunTarget,
    steps: List<StepRecord>,
    isLive: Boolean,
    onBack: () -> Unit,
    onCopy: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val text by produceState("", target.runId, isLive) {
        while (true) {
            value = withContext(Dispatchers.IO) { store.readLog(repoId, target.runId) }
            if (!isLive) break
            delay(600)
        }
    }
    val lines = remember(text) {
        if (text.isEmpty()) emptyList() else text.trimEnd('\n').split('\n').takeLast(MAX_LOG_LINES)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(scheme.surfaceContainerLow)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to workflows")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    target.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (isLive) "Running" else "Finished",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isLive) scheme.primary else scheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { onCopy(text) }, enabled = text.isNotEmpty()) { Text("Copy") }
        }
        if (steps.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(scheme.surfaceContainerLow)
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                steps.forEachIndexed { index, step -> StepLine(index + 1, step) }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(scheme.surfaceContainerLowest)
        ) {
            if (lines.isEmpty()) {
                Text(
                    "Waiting for output…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    itemsIndexed(lines) { _, line -> LogLine(line) }
                }
            }
        }
    }
}

@Composable
private fun LogLine(line: String) {
    val scheme = MaterialTheme.colorScheme
    val (color, weight) = when {
        line.startsWith("▶ ") -> scheme.primary to FontWeight.Bold
        line.startsWith("✕ ") -> scheme.error to FontWeight.SemiBold
        line.startsWith("✓ ") -> SuccessGreen to FontWeight.SemiBold
        else -> scheme.onSurface to FontWeight.Normal
    }
    Text(
        text = line.ifEmpty { " " },
        color = color,
        fontWeight = weight,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun stepColor(status: StepStatus): Color {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        StepStatus.SUCCESS -> SuccessGreen
        StepStatus.FAILED -> scheme.error
        StepStatus.RUNNING -> scheme.primary
        StepStatus.PENDING, StepStatus.SKIPPED, StepStatus.CANCELLED -> scheme.onSurfaceVariant
    }
}

private fun stepGlyph(status: StepStatus): String = when (status) {
    StepStatus.SUCCESS -> "✓"
    StepStatus.FAILED -> "✕"
    StepStatus.RUNNING -> "●"
    StepStatus.PENDING -> "○"
    StepStatus.SKIPPED -> "–"
    StepStatus.CANCELLED -> "■"
}

@Composable
private fun runColor(status: RunStatus): Color {
    val scheme = MaterialTheme.colorScheme
    return when (status) {
        RunStatus.SUCCESS -> SuccessGreen
        RunStatus.FAILED -> scheme.error
        RunStatus.RUNNING -> scheme.primary
        RunStatus.CANCELLED -> scheme.onSurfaceVariant
    }
}

private fun runGlyph(status: RunStatus): String = when (status) {
    RunStatus.SUCCESS -> "✓"
    RunStatus.FAILED -> "✕"
    RunStatus.RUNNING -> "●"
    RunStatus.CANCELLED -> "■"
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return when {
        seconds < 1 -> "<1s"
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${String.format(Locale.ROOT, "%02d", seconds % 60)}s"
        else -> "${seconds / 3600}h ${String.format(Locale.ROOT, "%02d", (seconds % 3600) / 60)}m"
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Workflow log", text))
}