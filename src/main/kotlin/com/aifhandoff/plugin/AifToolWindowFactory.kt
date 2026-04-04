package com.aifhandoff.plugin

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ide.BrowserUtil
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefDisplayHandlerAdapter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.AbstractCellEditor
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

class AifToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AifToolWindowPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Set default width to ~70% of the IDE frame
        val frame = com.intellij.openapi.wm.WindowManager.getInstance().getIdeFrame(project)
        val frameWidth = frame?.component?.width ?: 1400
        toolWindow.component.preferredSize = java.awt.Dimension((frameWidth * 0.7).toInt(), 0)
    }
}

private class AifToolWindowPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val service = project.getService(AifDevServerService::class.java)
    private var browser: JBCefBrowser? = null
    private val cardPanel = JPanel(CardLayout())

    private lateinit var startAction: AnAction
    private lateinit var stopAction: AnAction
    private lateinit var refreshAction: AnAction
    private lateinit var updateAction: AnAction
    private lateinit var settingsAction: AnAction
    private lateinit var openInBrowserAction: AnAction
    private lateinit var viewPlanAction: AnAction
    private var viewPlanEnabled = false
    private var currentTaskPlanPath: String? = null
    private var startEnabled = true
    private var stopEnabled = false
    private var refreshEnabled = false
    private var updateEnabled = true
    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
    }
    private val logArea = JTextArea().apply {
        isEditable = false
        font = java.awt.Font("JetBrains Mono", java.awt.Font.PLAIN, 11).let { f ->
            if (f.family == "JetBrains Mono") f else java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11)
        }
    }
    private val logPanel = JPanel(BorderLayout()).apply { isVisible = false }

    companion object {
        private const val CARD_WELCOME = "welcome"
        private const val CARD_PROGRESS = "progress"
        private const val CARD_BROWSER = "browser"
        private const val CARD_SETTINGS = "settings"
    }

    private val progressLabel = JLabel("Initializing...")
    private var currentProjectId: String? = null
    private fun getUrl(): String {
        val base = "http://localhost:${service.getWebPort()}"
        return if (currentProjectId != null) "$base/project/$currentProjectId" else base
    }

    init {
        buildUI()
        syncState(service.state)
        service.addStateListener { state ->
            ApplicationManager.getApplication().invokeLater { syncState(state) }
        }
    }

    private fun buildUI() {
        // --- Toolbar (IDE-style action toolbar) ---
        startAction = object : AnAction("Start", "Start AIF Handoff server", AllIcons.Actions.Execute) {
            override fun actionPerformed(e: AnActionEvent) = onStart()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = startEnabled }
        }
        stopAction = object : AnAction("Stop", "Stop AIF Handoff server", AllIcons.Actions.Suspend) {
            override fun actionPerformed(e: AnActionEvent) = onStop()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = stopEnabled }
        }
        refreshAction = object : AnAction("Refresh", "Refresh browser", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                browser?.cefBrowser?.reloadIgnoreCache()
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = refreshEnabled }
        }
        updateAction = object : AnAction("Update", "Update from upstream repository", AllIcons.Actions.CheckOut) {
            override fun actionPerformed(e: AnActionEvent) = onUpdate()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = updateEnabled }
        }
        settingsAction = object : AnAction("Settings", "Edit environment variables", AllIcons.General.Settings) {
            override fun actionPerformed(e: AnActionEvent) = onSettings()
        }

        viewPlanAction = object : AnAction("View Plan", "Open task plan in editor", AllIcons.Actions.PreviewDetails) {
            override fun actionPerformed(e: AnActionEvent) = onViewPlan()
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = viewPlanEnabled }
        }

        openInBrowserAction = object : AnAction("Open in Browser", "Open board in external browser", AllIcons.General.Web) {
            override fun actionPerformed(e: AnActionEvent) {
                BrowserUtil.browse(getUrl())
            }
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = refreshEnabled }
        }

        val logAction = object : AnAction("Toggle Log", "Show/hide API log", AllIcons.Debugger.Console) {
            override fun actionPerformed(e: AnActionEvent) {
                logPanel.isVisible = !logPanel.isVisible
                this@AifToolWindowPanel.revalidate()
            }
        }

        val actionGroup = DefaultActionGroup().apply {
            add(startAction)
            add(stopAction)
            add(refreshAction)
            add(openInBrowserAction)
            addSeparator()
            add(viewPlanAction)
            addSeparator()
            add(updateAction)
            add(settingsAction)
            addSeparator()
            add(logAction)
        }
        val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLBAR, actionGroup, true)
        actionToolbar.targetComponent = this

        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 8)
            add(actionToolbar.component)
            add(progressBar)
        }

        // --- Welcome card ---
        val welcomePanel = JPanel(java.awt.GridBagLayout()).apply {
            val gbc = java.awt.GridBagConstraints().apply {
                gridx = 0
                anchor = java.awt.GridBagConstraints.CENTER
                fill = java.awt.GridBagConstraints.NONE
            }

            val installed = AifDevServerService.INSTALL_DIR.resolve(".git").exists()
            val text = if (installed) {
                "<html><div style='text-align:center;'><h2>AIF Handoff</h2><p>Click <b>Start</b> to launch the Kanban board.</p></div></html>"
            } else {
                "<html><div style='text-align:center;'><h2>AIF Handoff</h2><p>Click <b>Start</b> to download and launch the Kanban board.<br><small>First launch will clone the repo and install dependencies.</small></p></div></html>"
            }
            val label = JLabel(text).apply { horizontalAlignment = SwingConstants.CENTER }
            gbc.gridy = 0
            gbc.insets = java.awt.Insets(0, 0, 16, 0)
            add(label, gbc)

            val bigStart = JButton("Start AIF Handoff")
            bigStart.addActionListener { onStart() }
            gbc.gridy = 1
            gbc.insets = java.awt.Insets(0, 0, 0, 0)
            add(bigStart, gbc)
        }

        // --- Progress card ---
        val progressPanel = JPanel(java.awt.GridBagLayout()).apply {
            val gbc = java.awt.GridBagConstraints().apply {
                gridx = 0
                anchor = java.awt.GridBagConstraints.CENTER
                fill = java.awt.GridBagConstraints.NONE
            }

            progressLabel.horizontalAlignment = SwingConstants.CENTER
            gbc.gridy = 0
            gbc.insets = java.awt.Insets(0, 0, 12, 0)
            add(progressLabel, gbc)

            val bar = JProgressBar().apply {
                isIndeterminate = true
                preferredSize = java.awt.Dimension(300, 20)
            }
            gbc.gridy = 1
            gbc.insets = java.awt.Insets(0, 0, 8, 0)
            add(bar, gbc)

            val hint = JLabel("<html><small>This may take a minute on first launch...</small></html>").apply {
                horizontalAlignment = SwingConstants.CENTER
            }
            gbc.gridy = 2
            gbc.insets = java.awt.Insets(0, 0, 0, 0)
            add(hint, gbc)

            add(Box.createVerticalGlue())
        }

        // --- Browser card ---
        val browserPanel = JPanel(BorderLayout())
        try {
            browser = JBCefBrowser()
            browserPanel.add(browser!!.component, BorderLayout.CENTER)

            // Monitor URL changes to detect task navigation
            browser!!.jbCefClient.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                override fun onAddressChange(b: CefBrowser?, frame: CefFrame?, url: String?) {
                    if (url == null || frame == null || !frame.isMain) return
                    val taskMatch = Regex("/project/[^/]+/task/([^/?#]+)").find(url)
                    val taskId = taskMatch?.groupValues?.get(1)
                    onBrowserTaskChanged(taskId)
                }
            }, browser!!.cefBrowser)
        } catch (e: Exception) {
            browserPanel.add(JLabel("JCEF browser not available: ${e.message}"), BorderLayout.CENTER)
        }

        // --- Settings card ---
        val settingsPanel = buildSettingsPanel()

        cardPanel.add(welcomePanel, CARD_WELCOME)
        cardPanel.add(progressPanel, CARD_PROGRESS)
        cardPanel.add(browserPanel, CARD_BROWSER)
        cardPanel.add(settingsPanel, CARD_SETTINGS)

        // --- Log panel ---
        val logScroll = JScrollPane(logArea).apply {
            preferredSize = java.awt.Dimension(0, 150)
        }
        logPanel.add(logScroll, BorderLayout.CENTER)

        val mainPanel = JPanel(BorderLayout()).apply {
            add(cardPanel, BorderLayout.CENTER)
            add(logPanel, BorderLayout.SOUTH)
        }

        add(toolbar, BorderLayout.NORTH)
        add(mainPanel, BorderLayout.CENTER)

        // Subscribe to log events
        val dateFormat = java.text.SimpleDateFormat("HH:mm:ss.SSS")
        service.addOutputLogListener { text ->
            ApplicationManager.getApplication().invokeLater {
                val time = dateFormat.format(java.util.Date())
                logArea.append("[$time] $text")
                if (!text.endsWith("\n")) logArea.append("\n")
                logArea.caretPosition = logArea.document.length
            }
        }
        service.addApiLogListener { entry ->
            ApplicationManager.getApplication().invokeLater {
                val time = dateFormat.format(java.util.Date(entry.timestamp))
                logArea.append("[$time] API ${entry.method} ${entry.url}\n")
                if (entry.requestBody != null) {
                    logArea.append("  → ${entry.requestBody}\n")
                }
                logArea.append("  ← ${entry.responseCode} ${entry.responseBody?.take(500) ?: ""}\n\n")
                logArea.caretPosition = logArea.document.length
            }
        }

        showCard(CARD_WELCOME)
    }

    private fun onStart() {
        showCard(CARD_PROGRESS)
        service.start {
            currentProjectId = service.ensureProject()
            ApplicationManager.getApplication().invokeLater {
                showCard(CARD_BROWSER)
                browser?.loadURL(getUrl())
            }
        }
    }

    private fun onStop() {
        service.stop()
        showCard(CARD_WELCOME)
        browser?.loadURL("about:blank")
    }

    private var envTableModel: EnvTableModel? = null
    private var previousCard: String = CARD_WELCOME

    private fun onSettings() {
        if (!service.isInstalled()) {
            JOptionPane.showMessageDialog(this, "Start the server first to initialize the project.", "Settings", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        envTableModel?.reload()
        previousCard = currentCard
        showCard(CARD_SETTINGS)
    }

    private var currentCard: String = CARD_WELCOME

    private fun onBrowserTaskChanged(taskId: String?) {
        if (taskId == null) {
            ApplicationManager.getApplication().invokeLater {
                viewPlanEnabled = false
                currentTaskPlanPath = null
            }
            return
        }
        // Check plan status in background
        Thread {
            val status = service.getTaskPlanFileStatus(taskId)
            ApplicationManager.getApplication().invokeLater {
                if (status != null && status.exists && status.path != null) {
                    viewPlanEnabled = true
                    currentTaskPlanPath = status.path
                } else {
                    viewPlanEnabled = false
                    currentTaskPlanPath = null
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun onViewPlan() {
        val planPath = currentTaskPlanPath ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath(planPath)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        } else {
            JOptionPane.showMessageDialog(this, "Plan file not found:\n$planPath", "View Plan", JOptionPane.WARNING_MESSAGE)
        }
    }

    private fun onUpdate() {
        showCard(CARD_PROGRESS)
        progressLabel.text = "Updating..."
        service.update {
            ApplicationManager.getApplication().invokeLater {
                showCard(CARD_WELCOME)
            }
        }
    }

    private fun syncState(state: AifDevServerService.State) {
        when (state) {
            AifDevServerService.State.STOPPED -> {
                startEnabled = true
                stopEnabled = false
                refreshEnabled = false
                updateEnabled = true
                progressBar.isVisible = false
                if (currentCard == CARD_PROGRESS) {
                    showCard(CARD_WELCOME)
                }
            }
            AifDevServerService.State.CLONING -> {
                progressLabel.text = "Cloning repository..."
                setButtonsBusy()
            }
            AifDevServerService.State.INSTALLING -> {
                progressLabel.text = "Installing npm dependencies..."
                setButtonsBusy()
            }
            AifDevServerService.State.SETTING_UP_DB -> {
                progressLabel.text = "Setting up database..."
                setButtonsBusy()
            }
            AifDevServerService.State.STARTING -> {
                progressLabel.text = "Starting dev server..."
                setButtonsBusy()
            }
            AifDevServerService.State.RUNNING -> {
                startEnabled = false
                stopEnabled = true
                refreshEnabled = true
                updateEnabled = false
                progressBar.isVisible = false
            }
        }
    }

    private fun setButtonsBusy() {
        startEnabled = false
        stopEnabled = true // allow stopping during setup
        refreshEnabled = false
        updateEnabled = false
        progressBar.isVisible = true
    }

    private fun showCard(name: String) {
        if (name != CARD_SETTINGS) currentCard = name
        (cardPanel.layout as CardLayout).show(cardPanel, name)
    }

    private fun buildSettingsPanel(): JPanel {
        val model = EnvTableModel()
        envTableModel = model

        val table = JTable(model).apply {
            rowHeight = 28
            tableHeader.reorderingAllowed = false
            columnModel.getColumn(0).preferredWidth = 50
            columnModel.getColumn(0).maxWidth = 60
            columnModel.getColumn(1).preferredWidth = 280
            columnModel.getColumn(2).preferredWidth = 400
            columnModel.getColumn(2).cellRenderer = SecretCellRenderer(model)
            columnModel.getColumn(2).cellEditor = SecretCellEditor(model)
        }
        val scrollPane = JScrollPane(table)

        val saveButton = JButton("Save")
        val backButton = JButton("Back")

        saveButton.addActionListener {
            if (table.isEditing) table.cellEditor.stopCellEditing()
            service.saveEnvEntries(model.toEntries())
            onStop()
            onStart()
        }

        backButton.addActionListener {
            if (table.isEditing) table.cellEditor.stopCellEditing()
            showCard(previousCard)
        }

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            add(saveButton)
            add(Box.createHorizontalStrut(8))
            add(backButton)
        }

        return JPanel(BorderLayout()).apply {
            val header = JLabel("<html><b>Environment Variables</b> — edit .env settings</html>").apply {
                border = BorderFactory.createEmptyBorder(8, 8, 4, 8)
            }
            add(header, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(buttonsPanel, BorderLayout.SOUTH)
        }
    }

    private fun isSecretKey(key: String): Boolean {
        val upper = key.uppercase()
        return upper.contains("TOKEN") || upper.contains("SECRET") || upper.contains("API_KEY") || upper.contains("PASSWORD")
    }

    private inner class SecretCellRenderer(private val model: EnvTableModel) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, col: Int
        ): java.awt.Component {
            val comp = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col)
            val key = model.getValueAt(row, 1) as String
            if (isSecretKey(key) && value is String && value.isNotEmpty()) {
                text = "\u2022".repeat(value.length.coerceAtMost(20))
            }
            return comp
        }
    }

    private inner class SecretCellEditor(private val model: EnvTableModel) : AbstractCellEditor(), TableCellEditor {
        private val textField = JTextField()
        private val passwordField = JPasswordField()
        private var usingPassword = false

        override fun getCellEditorValue(): Any {
            return if (usingPassword) String(passwordField.password) else textField.text
        }

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, col: Int
        ): java.awt.Component {
            val key = model.getValueAt(row, 1) as String
            val str = value?.toString() ?: ""
            usingPassword = isSecretKey(key)
            return if (usingPassword) {
                passwordField.apply { text = str }
            } else {
                textField.apply { text = str }
            }
        }
    }

    private inner class EnvTableModel : AbstractTableModel() {
        private val entries = mutableListOf<AifDevServerService.EnvEntry>()

        fun reload() {
            entries.clear()
            entries.addAll(service.loadEnvEntries())
            fireTableDataChanged()
        }

        fun toEntries(): List<AifDevServerService.EnvEntry> = entries.toList()

        override fun getRowCount() = entries.size
        override fun getColumnCount() = 3
        override fun getColumnName(col: Int) = when (col) {
            0 -> "On"
            1 -> "Variable"
            else -> "Value"
        }

        override fun getColumnClass(col: Int): Class<*> = when (col) {
            0 -> java.lang.Boolean::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, col: Int) = col != 1 // key is read-only

        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> entries[row].enabled
            1 -> entries[row].key
            else -> entries[row].value
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            when (col) {
                0 -> entries[row].enabled = value as? Boolean ?: false
                2 -> entries[row].value = value?.toString() ?: ""
            }
            fireTableCellUpdated(row, col)
        }
    }
}
