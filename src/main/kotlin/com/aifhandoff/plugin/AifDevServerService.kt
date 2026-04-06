package com.aifhandoff.plugin

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.io.File
import java.net.HttpURLConnection
import java.net.URI


@Service(Service.Level.PROJECT)
class AifDevServerService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AifDevServerService::class.java)

    var processHandler: ProcessHandler? = null
        private set

    var isRunning: Boolean = false
        private set

    private var stoppingManually = false

    var isSetup: Boolean = false
        private set

    enum class State { STOPPED, CLONING, INSTALLING, SETTING_UP_DB, STARTING, RUNNING }

    var state: State = State.STOPPED
        private set

    private val listeners = mutableListOf<(State) -> Unit>()

    data class ApiLogEntry(val timestamp: Long, val method: String, val url: String, val requestBody: String?, val responseCode: Int, val responseBody: String?)
    private val apiLogListeners = mutableListOf<(ApiLogEntry) -> Unit>()
    private val outputLogListeners = mutableListOf<(String) -> Unit>()

    fun addApiLogListener(listener: (ApiLogEntry) -> Unit) { apiLogListeners.add(listener) }
    fun addOutputLogListener(listener: (String) -> Unit) { outputLogListeners.add(listener) }

    private fun logOutput(text: String) { outputLogListeners.forEach { it(text) } }

    private fun logApi(method: String, url: String, requestBody: String?, responseCode: Int, responseBody: String?) {
        val entry = ApiLogEntry(System.currentTimeMillis(), method, url, requestBody, responseCode, responseBody)
        log.info("API $method $url -> $responseCode")
        apiLogListeners.forEach { it(entry) }
    }

    companion object {
        const val REPO_URL = "https://github.com/lee-to/aif-handoff.git"
        val INSTALL_DIR: File = File(System.getProperty("user.home"), ".aif-handoff")

        val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().startsWith("windows")
        val HOME: String = System.getProperty("user.home")
        val PROGRAM_FILES: String = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val PROGRAM_FILES_X86: String = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val APP_DATA: String = System.getenv("APPDATA") ?: "$HOME\\AppData\\Roaming"
    }

    fun addStateListener(listener: (State) -> Unit) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun setState(newState: State) {
        state = newState
        isRunning = newState == State.RUNNING
        isSetup = INSTALL_DIR.resolve("node_modules").exists()
        listeners.forEach { it(newState) }
    }

    /**
     * First launch: clone → npm install → db:setup → npm run dev.
     * Subsequent launches: just npm run dev.
     */
    private val settings get() = project.getService(AifSettingsService::class.java)

    fun isRemoteMode(): Boolean = settings.isRemoteMode()

    fun getEffectiveBaseUrl(): String = settings.getBaseUrl(getWebPort())

    private fun getEffectiveApiBaseUrl(): String = settings.getApiBaseUrl(getApiPort())

    fun start(onReady: (() -> Unit)? = null) {
        if (state != State.STOPPED) return

        if (settings.isRemoteMode()) {
            setState(State.RUNNING)
            notify("Connected to remote host: ${settings.remoteHost}", NotificationType.INFORMATION)
            onReady?.invoke()
            return
        }

        Thread {
            try {
                val freshInstall = !INSTALL_DIR.resolve(".git").exists()
                if (freshInstall) {
                    ensureRepo()
                    ensureDeps()
                    ensureDb()
                }
                startDevServer(onReady)
            } catch (e: Exception) {
                log.error("AIF Handoff setup failed", e)
                notify("Setup failed: ${e.message}", NotificationType.ERROR)
                setState(State.STOPPED)
            }
        }.apply {
            isDaemon = true
            name = "AIF-Handoff-Setup"
            start()
        }
    }

    fun stop() {
        if (settings.isRemoteMode()) {
            setState(State.STOPPED)
            notify("Disconnected from remote host", NotificationType.INFORMATION)
            return
        }
        stoppingManually = true
        processHandler?.destroyProcess()
        processHandler = null
        setState(State.STOPPED)
        notify("Dev server stopped", NotificationType.INFORMATION)
    }

    fun update(onDone: (() -> Unit)? = null) {
        if (state != State.STOPPED) return
        Thread {
            try {
                ensureRepo()
                ensureDeps()
                ensureDb()
                notify("Updated to latest version", NotificationType.INFORMATION)
                onDone?.invoke()
            } catch (e: Exception) {
                log.error("Update failed", e)
                notify("Update failed: ${e.message}", NotificationType.ERROR)
            } finally {
                setState(State.STOPPED)
            }
        }.apply {
            isDaemon = true
            name = "AIF-Handoff-Update"
            start()
        }
    }

    // --- Setup steps ---

    private fun ensureRepo() {
        setState(State.CLONING)
        if (INSTALL_DIR.resolve(".git").exists()) {
            notify("Pulling latest changes...", NotificationType.INFORMATION)
            runCmd(findGit(), "checkout", ".", workDir = INSTALL_DIR, label = "git checkout .")
            runCmd(findGit(), "pull", "--ff-only", workDir = INSTALL_DIR, label = "git pull")
        } else {
            notify("Cloning repository...", NotificationType.INFORMATION)
            runCmd(findGit(), "clone", REPO_URL, INSTALL_DIR.absolutePath, label = "git clone")
            ensureEnvFile()
        }
    }

    private fun ensureEnvFile() {
        val envFile = INSTALL_DIR.resolve(".env")
        val exampleFile = INSTALL_DIR.resolve(".env.example")
        if (!envFile.exists() && exampleFile.exists()) {
            exampleFile.copyTo(envFile)
            log.info("Copied .env.example → .env")
        }
    }

    private fun ensureDeps() {
        setState(State.INSTALLING)
        notify("Installing dependencies...", NotificationType.INFORMATION)
        runCmd(findNpm(), "install", workDir = INSTALL_DIR, label = "npm install", timeoutMs = 300_000)
    }

    private fun ensureDb() {
        val dbFile = INSTALL_DIR.resolve("data/aif.sqlite")
        if (dbFile.exists()) return
        setState(State.SETTING_UP_DB)
        notify("Setting up database...", NotificationType.INFORMATION)
        runCmd(findNpm(), "run", "db:setup", workDir = INSTALL_DIR, label = "db:setup")
    }

    private fun startDevServer(onReady: (() -> Unit)?) {
        setState(State.STARTING)
        notify("Starting dev server...", NotificationType.INFORMATION)

        val envPath = buildEnvPath()
        val cmd = GeneralCommandLine(findNpm(), "run", "dev")
            .withWorkDirectory(INSTALL_DIR)
            .withEnvironment("FORCE_COLOR", "0")
            .withEnvironment("PATH", envPath)
            .withCharset(Charsets.UTF_8)

        val handler = KillableColoredProcessHandler(cmd)
        handler.addProcessListener(object : ProcessAdapter() {
            private var readyFired = false

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                val text = event.text
                log.info("AIF dev: $text")
                logOutput(text)
                if (!readyFired && outputType == ProcessOutputTypes.STDOUT &&
                    text.contains("API server started")
                ) {
                    readyFired = true
                    setState(State.RUNNING)
                    notify("Kanban board ready on port ${getWebPort()}", NotificationType.INFORMATION)
                    onReady?.invoke()
                }
            }

            override fun processTerminated(event: ProcessEvent) {
                processHandler = null
                val wasStarting = state != State.RUNNING
                val manual = stoppingManually
                stoppingManually = false
                setState(State.STOPPED)
                log.info("AIF dev server stopped (exit ${event.exitCode})")
                if (!manual && (event.exitCode != 0 || wasStarting)) {
                    notify("Dev server failed (exit ${event.exitCode}). Check logs for details.", NotificationType.ERROR)
                }
            }
        })

        handler.startNotify()
        processHandler = handler

        // Fallback: load browser after 20s even if ready signal missed
        Thread {
            Thread.sleep(20_000)
            if (state == State.STARTING) {
                setState(State.RUNNING)
                onReady?.invoke()
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    // --- Env file management ---

    data class EnvEntry(val key: String, var value: String, var enabled: Boolean)

    fun loadEnvEntries(): List<EnvEntry> {
        ensureEnvFile()
        val envFile = INSTALL_DIR.resolve(".env")
        if (!envFile.exists()) return emptyList()
        val entries = mutableListOf<EnvEntry>()
        envFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            // commented-out variable: "# KEY=value"
            if (trimmed.startsWith("#")) {
                val uncommented = trimmed.removePrefix("#").trim()
                val eqIdx = uncommented.indexOf('=')
                if (eqIdx > 0) {
                    val key = uncommented.substring(0, eqIdx).trim()
                    // skip pure section comments like "# --- Ports ---"
                    if (key.all { c -> c.isLetterOrDigit() || c == '_' }) {
                        val value = uncommented.substring(eqIdx + 1).trim()
                        entries.add(EnvEntry(key, value, enabled = false))
                    }
                }
                return@forEach
            }
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val value = trimmed.substring(eqIdx + 1).trim()
                entries.add(EnvEntry(key, value, enabled = true))
            }
        }
        return entries
    }

    fun saveEnvEntries(entries: List<EnvEntry>) {
        val envFile = INSTALL_DIR.resolve(".env")
        val existingLines = if (envFile.exists()) envFile.readLines() else emptyList()

        val entryMap = LinkedHashMap<String, EnvEntry>()
        for (e in entries) entryMap[e.key] = e

        val newLines = mutableListOf<String>()

        for (line in existingLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) { newLines.add(line); continue }

            // try to match as commented var
            if (trimmed.startsWith("#")) {
                val uncommented = trimmed.removePrefix("#").trim()
                val eqIdx = uncommented.indexOf('=')
                if (eqIdx > 0) {
                    val key = uncommented.substring(0, eqIdx).trim()
                    if (key.all { c -> c.isLetterOrDigit() || c == '_' }) {
                        val entry = entryMap.remove(key)
                        if (entry != null) {
                            newLines.add(if (entry.enabled) "${entry.key}=${entry.value}" else "# ${entry.key}=${entry.value}")
                        } else {
                            newLines.add(line)
                        }
                        continue
                    }
                }
                newLines.add(line)
                continue
            }

            // active var
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx > 0) {
                val key = trimmed.substring(0, eqIdx).trim()
                val entry = entryMap.remove(key)
                if (entry != null) {
                    newLines.add(if (entry.enabled) "${entry.key}=${entry.value}" else "# ${entry.key}=${entry.value}")
                } else {
                    newLines.add(line)
                }
            } else {
                newLines.add(line)
            }
        }

        envFile.writeText(newLines.joinToString("\n") + "\n")
        log.info("Saved .env (${entries.size} entries)")
    }

    fun isInstalled(): Boolean = INSTALL_DIR.resolve(".git").exists()

    fun getWebPort(): Int = getEnvPort("WEB_PORT", 5180)

    fun getApiPort(): Int = getEnvPort("API_PORT", 3090)

    private fun getEnvPort(name: String, default: Int): Int {
        val envFile = INSTALL_DIR.resolve(".env")
        if (envFile.exists()) {
            for (line in envFile.readLines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("#") && trimmed.startsWith("$name=")) {
                    return trimmed.substringAfter("=").trim().toIntOrNull() ?: default
                }
            }
        }
        return default
    }

    /**
     * Find project by rootPath or create a new one. Returns project ID or null.
     */
    fun ensureProject(): String? {
        val rootPath = project.basePath ?: return null
        // API server may still be starting — retry a few times
        for (attempt in 1..5) {
            try {
                val result = findOrCreateProject(rootPath)
                if (result != null) return result
            } catch (e: Exception) {
                log.info("ensureProject attempt $attempt failed: ${e.message}")
            }
            Thread.sleep(2000)
        }
        log.warn("Could not ensure project for $rootPath after 5 attempts")
        return null
    }

    private fun findOrCreateProject(rootPath: String): String? {
        val baseUrl = getEffectiveApiBaseUrl()
        log.info("ensureProject: looking for rootPath=$rootPath at $baseUrl")

        // GET /api/projects — find existing
        val listConn = URI("$baseUrl/projects").toURL().openConnection() as HttpURLConnection
        listConn.requestMethod = "GET"
        listConn.connectTimeout = 5000
        listConn.readTimeout = 5000
        val listCode = listConn.responseCode
        val listBody = if (listCode == 200) listConn.inputStream.bufferedReader().readText() else listConn.errorStream?.bufferedReader()?.readText() ?: ""
        logApi("GET", "$baseUrl/projects", null, listCode, listBody)
        if (listCode == 200) {
            val body = listBody
            log.info("ensureProject: response body (${body.length} chars)")
            // Find "rootPath":"<path>" then grab "id" from the same object
            val escapedPath = rootPath.replace("\\", "\\\\").replace("/", "\\/")
            val objects = body.split(Regex("\\},\\s*\\{"))
            for (obj in objects) {
                if (obj.contains("\"rootPath\":\"$rootPath\"") || obj.contains("\"rootPath\":\"$escapedPath\"")) {
                    val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").find(obj)
                    if (idMatch != null) {
                        log.info("Found existing project ${idMatch.groupValues[1]} for $rootPath")
                        return idMatch.groupValues[1]
                    }
                }
            }
            log.info("ensureProject: no project found with rootPath=$rootPath")
        }

        // POST /projects — create new
        val name = File(rootPath).name
        val json = """{"name":"$name","rootPath":"$rootPath"}"""
        log.info("ensureProject: POST /projects with $json")
        val createConn = URI("$baseUrl/projects").toURL().openConnection() as HttpURLConnection
        createConn.requestMethod = "POST"
        createConn.doOutput = true
        createConn.connectTimeout = 5000
        createConn.readTimeout = 5000
        createConn.setRequestProperty("Content-Type", "application/json")
        createConn.outputStream.use { it.write(json.toByteArray()) }
        val createCode = createConn.responseCode
        val createBody = if (createCode in 200..201) {
            createConn.inputStream.bufferedReader().readText()
        } else {
            createConn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        logApi("POST", "$baseUrl/projects", json, createCode, createBody)
        if (createCode in 200..201) {
            val idMatch = Regex(""""id"\s*:\s*"([^"]+)"""").find(createBody)
            if (idMatch != null) {
                log.info("Created project ${idMatch.groupValues[1]} for $rootPath")
                return idMatch.groupValues[1]
            }
        }
        return null
    }

    // --- Task API ---

    data class PlanFileStatus(val exists: Boolean, val path: String?)

    fun getTaskPlanFileStatus(taskId: String): PlanFileStatus? {
        val baseUrl = getEffectiveApiBaseUrl()
        return try {
            val conn = URI("$baseUrl/tasks/$taskId/plan-file-status").toURL().openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val code = conn.responseCode
            val body = if (code == 200) conn.inputStream.bufferedReader().readText() else null
            logApi("GET", "$baseUrl/tasks/$taskId/plan-file-status", null, code, body)
            if (code == 200 && body != null) {
                val exists = Regex(""""exists"\s*:\s*(true|false)""").find(body)?.groupValues?.get(1) == "true"
                val path = Regex(""""path"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                PlanFileStatus(exists, path)
            } else null
        } catch (e: Exception) {
            log.warn("Failed to get plan file status for task $taskId", e)
            null
        }
    }

    // --- Helpers ---

    private fun runCmd(vararg args: String, workDir: File? = null, label: String, timeoutMs: Long = 120_000, ignoreError: Boolean = false) {
        log.info("AIF running: ${args.joinToString(" ")}")
        val envPath = buildEnvPath()
        val pb = ProcessBuilder(*args)
            .redirectErrorStream(true)
        pb.environment()["PATH"] = envPath
        if (workDir != null) pb.directory(workDir)
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        if (output.isNotBlank()) logOutput("[$label] $output")
        val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            throw RuntimeException("$label timed out after ${timeoutMs / 1000}s")
        }
        if (proc.exitValue() != 0) {
            log.warn("$label output: $output")
            if (!ignoreError) {
                throw RuntimeException("$label failed (exit ${proc.exitValue()}): ${output.takeLast(500)}")
            }
        }
        log.info("$label completed successfully")
    }

    private fun buildEnvPath(): String {
        val extra = if (IS_WINDOWS) {
            listOf(
                "$PROGRAM_FILES\\Git\\bin",
                "$PROGRAM_FILES_X86\\Git\\bin",
                "$PROGRAM_FILES\\nodejs",
                "$APP_DATA\\npm",
                "$HOME\\AppData\\Roaming\\npm",
                "$HOME\\.volta\\bin",
                "$HOME\\.fnm\\current",
            )
        } else {
            listOf(
                "/usr/local/bin",
                "/opt/homebrew/bin",
                "$HOME/.nvm/current/bin",
                "$HOME/.volta/bin",
                "$HOME/.fnm/current/bin",
            )
        }

        val current = System.getenv("PATH") ?: if (IS_WINDOWS) "" else "/usr/bin:/bin"
        return (extra + current.split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
    }

    private fun findNpm(): String = findBinary("npm")
    private fun findGit(): String = findBinary("git")

    private fun findBinary(name: String): String {
        if (IS_WINDOWS) {
            val winName = when (name) {
                "npm" -> "npm.cmd"
                else -> "$name.exe"
            }
            val candidates = listOf(
                "$PROGRAM_FILES\\Git\\bin\\$winName",
                "$PROGRAM_FILES_X86\\Git\\bin\\$winName",
                "$PROGRAM_FILES\\nodejs\\$winName",
                "$APP_DATA\\npm\\$winName",
                "$HOME\\AppData\\Roaming\\npm\\$winName",
                "$HOME\\.volta\\bin\\$winName",
            )
            candidates.firstOrNull { File(it).exists() }?.let { return it }

            // Fallback: `where` is the Windows equivalent of `which`
            try {
                val where = ProcessBuilder("where", name).redirectErrorStream(true).start()
                val path = where.inputStream.bufferedReader().readLine()?.trim() ?: ""
                where.waitFor()
                if (path.isNotEmpty() && File(path).exists()) return path
            } catch (_: Exception) {}
        } else {
            try {
                val which = ProcessBuilder("which", name).redirectErrorStream(true).start()
                val path = which.inputStream.bufferedReader().readText().trim()
                which.waitFor()
                if (path.isNotEmpty() && File(path).exists()) return path
            } catch (_: Exception) {}

            val candidates = listOf(
                "/usr/local/bin/$name",
                "/opt/homebrew/bin/$name",
                "$HOME/.nvm/current/bin/$name",
                "$HOME/.volta/bin/$name",
                "/usr/bin/$name",
            )
            candidates.firstOrNull { File(it).exists() }?.let { return it }
        }

        throw IllegalStateException("$name not found. Please install it and ensure it's in PATH.")
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AIF Handoff")
            .createNotification("AIF Handoff", content, type)
            .notify(project)
    }

    override fun dispose() {
        stop()
    }
}
