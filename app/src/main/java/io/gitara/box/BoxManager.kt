package io.gitara.box

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

sealed class BoxState {
    object Checking : BoxState()
    object NotInstalled : BoxState()
    data class Installing(val stage: String, val progress: Float?) : BoxState()
    data class Ready(val description: String) : BoxState()
    data class Failed(val message: String) : BoxState()
}

sealed class ProcessOutcome {
    class Exit(val code: Int) : ProcessOutcome()
    object TimedOut : ProcessOutcome()
    object Cancelled : ProcessOutcome()
    class Failure(val message: String) : ProcessOutcome()
}

class BoxLayout(context: Context) {
    val root = File(context.filesDir, "box")
    val rootfs = File(root, "rootfs")
    val partial = File(root, "rootfs.partial")
    val download = File(root, "download")
    val marker = File(root, "ready")
    val runDir = File(root, "run")
    val prootTmp = File(context.cacheDir, "proot-tmp")
    private val nativeDir: String = context.applicationInfo.nativeLibraryDir
    val proot = File(nativeDir, "libproot.so")
    val loader = File(nativeDir, "libproot_loader.so")
}

internal fun deleteTree(file: File) {
    val path = file.path
    val stat = try {
        Os.lstat(path)
    } catch (e: ErrnoException) {
        return
    }
    if (OsConstants.S_ISLNK(stat.st_mode)) {
        try {
            Os.remove(path)
        } catch (e: ErrnoException) {
            // already gone
        }
        return
    }
    if (OsConstants.S_ISDIR(stat.st_mode)) {
        try {
            Os.chmod(path, 0x1C0)
        } catch (e: ErrnoException) {
            // best effort
        }
        file.list()?.forEach { deleteTree(File(file, it)) }
    }
    try {
        Os.remove(path)
    } catch (e: ErrnoException) {
        // already gone
    }
}

object BoxRuntime {
    const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    fun hostEnvironment(layout: BoxLayout): Map<String, String> {
        layout.prootTmp.mkdirs()
        return mapOf(
            "PROOT_LOADER" to layout.loader.path,
            "PROOT_TMP_DIR" to layout.prootTmp.path,
            "PROOT_NO_SECCOMP" to "1",
            "PROOT_IGNORE_MISSING_BINDINGS" to "1"
        )
    }

    fun command(
        layout: BoxLayout,
        binds: List<Pair<String, String>>,
        workdir: String,
        env: Map<String, String>,
        argv: List<String>
    ): List<String> {
        val command = ArrayList<String>()
        command.add(layout.proot.path)
        command.addAll(listOf("-0", "-r", layout.rootfs.path, "--link2symlink", "--kill-on-exit", "-k", "6.2.1-gitara-box"))
        command.addAll(listOf("-b", "/dev", "-b", "/proc", "-b", "/sys"))
        for ((host, guest) in binds) {
            command.add("-b")
            command.add("$host:$guest")
        }
        command.add("-w")
        command.add(workdir)
        command.add("/usr/bin/env")
        command.add("-i")
        val guestEnv = linkedMapOf(
            "HOME" to "/root",
            "PATH" to GUEST_PATH,
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            "USER" to "root",
            "SHELL" to "/bin/sh"
        )
        guestEnv.putAll(env)
        for ((key, value) in guestEnv) command.add("$key=$value")
        command.addAll(argv)
        return command
    }
}

object BoxProcess {

    fun run(
        command: List<String>,
        environment: Map<String, String>,
        workDir: File,
        timeoutMs: Long,
        isCancelled: () -> Boolean,
        onLine: (String) -> Unit
    ): ProcessOutcome {
        val builder = ProcessBuilder(command)
        builder.directory(workDir)
        builder.redirectErrorStream(true)
        builder.environment().putAll(environment)

        val process = try {
            builder.start()
        } catch (e: IOException) {
            return ProcessOutcome.Failure("Cannot start the Gitara Box runtime: ${e.message}")
        }

        val reader = Thread({
            try {
                process.inputStream.bufferedReader().forEachLine(onLine)
            } catch (e: IOException) {
                // stream closed when the process ends
            }
        }, "gitara-box-output")
        reader.start()

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var outcome: ProcessOutcome? = null
        while (outcome == null) {
            val exit = exitCodeOrNull(process)
            if (exit != null) {
                outcome = ProcessOutcome.Exit(exit)
            } else if (isCancelled()) {
                terminate(process)
                outcome = ProcessOutcome.Cancelled
            } else if (SystemClock.elapsedRealtime() >= deadline) {
                terminate(process)
                outcome = ProcessOutcome.TimedOut
            } else {
                try {
                    Thread.sleep(100)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    terminate(process)
                    outcome = ProcessOutcome.Cancelled
                }
            }
        }

        try {
            reader.join(2000)
            if (reader.isAlive) process.inputStream.close()
        } catch (e: Exception) {
            // best effort
        }
        return outcome ?: ProcessOutcome.Cancelled
    }

    private fun exitCodeOrNull(process: Process): Int? = try {
        process.exitValue()
    } catch (e: IllegalThreadStateException) {
        null
    }

    private fun terminate(process: Process) {
        process.destroy()
        val end = SystemClock.elapsedRealtime() + 2000
        while (exitCodeOrNull(process) == null && SystemClock.elapsedRealtime() < end) {
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                break
            }
        }
        if (exitCodeOrNull(process) == null) process.destroyForcibly()
    }
}

object BoxManager {
    private const val ALPINE_BASE = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64"

    private val mutableState = MutableStateFlow<BoxState>(BoxState.Checking)
    val state: StateFlow<BoxState> = mutableState.asStateFlow()
    private val busy = AtomicBoolean(false)

    fun isReady(context: Context): Boolean {
        val layout = BoxLayout(context)
        return layout.marker.isFile && layout.rootfs.isDirectory
    }

    fun refresh(context: Context) {
        if (busy.get()) return
        val layout = BoxLayout(context)
        mutableState.value = if (isReady(context)) {
            BoxState.Ready(layout.marker.readText().trim())
        } else {
            BoxState.NotInstalled
        }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) return
        mutableState.value = BoxState.Installing("Preparing", null)
        Thread({
            try {
                doInstall(app)
            } catch (t: Throwable) {
                Timber.e(t, "Gitara Box installation failed")
                mutableState.value = BoxState.Failed(t.message ?: t.javaClass.simpleName)
            } finally {
                busy.set(false)
            }
        }, "gitara-box-install").start()
    }

    fun reset(context: Context) {
        val app = context.applicationContext
        if (!busy.compareAndSet(false, true)) return
        mutableState.value = BoxState.Installing("Removing the Gitara Box", null)
        Thread({
            try {
                val layout = BoxLayout(app)
                deleteTree(layout.marker)
                deleteTree(layout.rootfs)
                deleteTree(layout.partial)
                deleteTree(layout.download)
                deleteTree(layout.runDir)
                mutableState.value = BoxState.NotInstalled
            } catch (t: Throwable) {
                Timber.e(t, "Gitara Box reset failed")
                mutableState.value = BoxState.Failed(t.message ?: t.javaClass.simpleName)
            } finally {
                busy.set(false)
            }
        }, "gitara-box-reset").start()
    }

    private fun stage(text: String, fraction: Float?) {
        mutableState.value = BoxState.Installing(text, fraction)
    }

    private fun doInstall(context: Context) {
        val layout = BoxLayout(context)
        if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) {
            throw IOException("The Gitara Box needs a 64-bit ARM (arm64-v8a) device.")
        }
        if (!layout.proot.isFile || !layout.loader.isFile) {
            throw IOException("The proot runtime is missing from this build. Rebuild the app so the prepareProotLibs Gradle task runs.")
        }
        layout.root.mkdirs()
        layout.download.mkdirs()

        stage("Looking up the latest Alpine release", null)
        val index = httpText("$ALPINE_BASE/latest-releases.yaml")
        val fileName = Regex("alpine-minirootfs-[0-9][0-9.]*-aarch64\\.tar\\.gz").find(index)?.value
            ?: throw IOException("Could not find the Alpine minirootfs in the release index.")
        val expected = httpText("$ALPINE_BASE/$fileName.sha256").trim().split(Regex("\\s+")).first().lowercase()
        if (!Regex("[0-9a-f]{64}").matches(expected)) throw IOException("The checksum file from Alpine is not valid.")

        val archive = File(layout.download, fileName)
        stage("Downloading $fileName", 0f)
        download("$ALPINE_BASE/$fileName", archive) { stage("Downloading $fileName", it) }

        stage("Verifying checksum", null)
        if (sha256(archive) != expected) {
            archive.delete()
            throw IOException("Checksum mismatch for $fileName. Try again.")
        }

        stage("Extracting root filesystem", 0f)
        deleteTree(layout.partial)
        val total = archive.length().coerceAtLeast(1L)
        TarExtractor.extractGzip(archive, layout.partial) { read ->
            stage("Extracting root filesystem", (read.toFloat() / total).coerceIn(0f, 1f))
        }
        prepareRootfs(layout.partial)

        deleteTree(layout.rootfs)
        if (!layout.partial.renameTo(layout.rootfs)) throw IOException("Could not move the root filesystem into place.")

        stage("Testing the environment", null)
        val output = selfTest(context)
        if (!output.contains("gitara-box-ok")) {
            throw IOException("The self-test failed:\n" + output.trim().take(1200))
        }

        val version = Regex("alpine-minirootfs-([0-9][0-9.]*)-aarch64").find(fileName)?.groupValues?.get(1) ?: "latest"
        val description = "Alpine Linux $version (arm64)"
        layout.marker.writeText(description)
        archive.delete()
        mutableState.value = BoxState.Ready(description)
    }

    private fun prepareRootfs(rootfs: File) {
        for (dir in listOf("proc", "sys", "dev", "tmp", "root", "workspace", "run/gitara", "etc")) {
            File(rootfs, dir).mkdirs()
        }
        try {
            Os.chmod(File(rootfs, "tmp").path, 0x3FF)
        } catch (e: ErrnoException) {
            // best effort
        }
        File(rootfs, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
    }

    private fun selfTest(context: Context): String {
        val layout = BoxLayout(context)
        val output = StringBuilder()
        val command = BoxRuntime.command(
            layout,
            emptyList(),
            "/",
            emptyMap(),
            listOf("/bin/sh", "-c", "echo gitara-box-ok; uname -m; cat /etc/alpine-release")
        )
        val outcome = BoxProcess.run(command, BoxRuntime.hostEnvironment(layout), context.filesDir, 60_000L, { false }) {
            output.appendLine(it)
        }
        if (outcome is ProcessOutcome.Failure) output.appendLine(outcome.message)
        if (outcome is ProcessOutcome.TimedOut) output.appendLine("The self-test timed out.")
        return output.toString()
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Gitara-Box")
        val code = connection.responseCode
        if (code != 200) {
            connection.disconnect()
            throw IOException("HTTP $code for $url")
        }
        return connection
    }

    private fun httpText(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, onProgress: (Float?) -> Unit) {
        val part = File(target.path + ".part")
        val connection = open(url)
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - last > 200) {
                            last = now
                            onProgress(if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("Could not store the download.")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}