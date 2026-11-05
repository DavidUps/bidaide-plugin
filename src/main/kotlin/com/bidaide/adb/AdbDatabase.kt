package com.bidaide.adb

import com.bidaide.BidaideBundle
import com.bidaide.Notifier
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import java.sql.Connection
import java.sql.Driver
import java.util.Properties
import javax.swing.Action
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/** Reads SQLite files with the JDBC driver that ships with the Android plugin. Blocking. */
object Sqlite {
    class Result(val columns: List<String>, val rows: List<List<String>>, val truncated: Boolean, val message: String)

    const val MAX_ROWS = 1000

    private fun open(file: File): Connection {
        // The driver comes with the Android plugin this plugin depends on; looked up by name to avoid a compile dependency.
        val driver = try {
            Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance() as Driver
        } catch (e: ReflectiveOperationException) {
            throw AdbException(BidaideBundle.message("adb.db.noDriver"))
        }
        return driver.connect("jdbc:sqlite:${file.path}", Properties()) ?: throw AdbException(BidaideBundle.message("adb.db.cannotOpen", file.name))
    }

    fun tables(file: File): List<String> =
        run(file, "SELECT name FROM sqlite_master WHERE type IN ('table', 'view') ORDER BY name").rows.map { it.first() }

    fun selectAll(table: String) = "SELECT * FROM \"${table.replace("\"", "\"\"")}\" LIMIT 500"

    /** Runs any statement; at most [MAX_ROWS] rows are read. */
    fun run(file: File, sql: String): Result = open(file).use { connection ->
        connection.createStatement().use { statement ->
            if (!statement.execute(sql)) {
                return Result(emptyList(), emptyList(), false, BidaideBundle.message("adb.db.updated", statement.updateCount))
            }
            statement.resultSet.use { rows ->
                val meta = rows.metaData
                val columns = (1..meta.columnCount).map(meta::getColumnLabel)
                val data = ArrayList<List<String>>()
                var truncated = false
                while (rows.next()) {
                    if (data.size == MAX_ROWS) {
                        truncated = true
                        break
                    }
                    data += columns.indices.map { index ->
                        when (val value = rows.getObject(index + 1)) {
                            null -> "NULL"
                            is ByteArray -> "<blob ${value.size} bytes>"
                            else -> value.toString()
                        }
                    }
                }
                Result(columns, data, truncated, if (truncated) BidaideBundle.message("adb.db.rowsTruncated", data.size) else BidaideBundle.message("adb.db.rows", data.size))
            }
        }
    }
}

/** Pulls the app's databases to this machine and opens a small browser on them. */
class AdbDatabaseAction : AdbBaseAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        AdbTargetResolver.resolve(project, needsPackage = true) { devices, pkg ->
            val packageName = pkg ?: return@resolve
            val device = devices.first()
            AdbTasks.fetch(
                project, BidaideBundle.message("adb.db.title", packageName),
                work = {
                    AdbFiles.pullDatabases(device, packageName).walkTopDown()
                        .filter { it.isFile && !COMPANIONS.any(it.name::endsWith) && it.extension != "tar" }.sortedBy { it.name }.toList()
                },
            ) { files ->
                when (files.size) {
                    0 -> Notifier.info(project, AndroidCli.TITLE, BidaideBundle.message("adb.db.none", packageName, AdbTargetResolver.deviceLabel(device)))
                    1 -> DatabaseBrowser(project, packageName, files.single()).show()
                    else -> JBPopupFactory.getInstance().createPopupChooserBuilder(files)
                        .setTitle(BidaideBundle.message("adb.db.title", packageName))
                        .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { it.name })
                        .setItemChosenCallback { DatabaseBrowser(project, packageName, it).show() }
                        .createPopup()
                        .showCenteredInCurrentWindow(project)
                }
            }
        }
    }

    private companion object {
        /** Write-ahead log and journal files travel with the database but are not databases themselves. */
        val COMPANIONS = listOf("-wal", "-shm", "-journal")
    }
}

/** Tables on the left, rows on the right, free SQL on top. Works on a local copy: the device is never touched. */
private class DatabaseBrowser(project: Project, packageName: String, private val file: File) : DialogWrapper(project, true) {
    private val tables = JBList<String>()
    private val grid = JBTable().apply {
        autoResizeMode = JTable.AUTO_RESIZE_OFF
        setShowGrid(true)
    }
    private val sqlField = JBTextField()
    private val status = JBLabel(" ")

    init {
        title = BidaideBundle.message("adb.db.browserTitle", file.name, packageName)
        isModal = false
        setOKButtonText(BidaideBundle.message("adb.db.close"))
        init()
        background(work = { Sqlite.tables(file) }) { names ->
            tables.setListData(names.toTypedArray())
            if (names.isNotEmpty()) tables.selectedIndex = 0
        }
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        tables.selectionMode = ListSelectionModel.SINGLE_SELECTION
        tables.emptyText.text = BidaideBundle.message("adb.db.noTables")
        tables.addListSelectionListener {
            if (!it.valueIsAdjusting) tables.selectedValue?.let { table ->
                sqlField.text = Sqlite.selectAll(table)
                execute()
            }
        }
        sqlField.addActionListener { execute() }
        val top = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            border = JBUI.Borders.emptyBottom(6)
            add(JBLabel("SQL:"), BorderLayout.WEST)
            add(sqlField, BorderLayout.CENTER)
            add(JButton(BidaideBundle.message("adb.db.execute")).apply { addActionListener { execute() } }, BorderLayout.EAST)
        }
        val splitter = OnePixelSplitter(false, 0.2f).apply {
            firstComponent = JBScrollPane(tables)
            secondComponent = JBScrollPane(grid)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(900), JBUI.scale(520))
            add(top, BorderLayout.NORTH)
            add(splitter, BorderLayout.CENTER)
            add(status, BorderLayout.SOUTH)
        }
    }

    override fun getPreferredFocusedComponent() = sqlField

    private fun execute() {
        val sql = sqlField.text.trim()
        if (sql.isEmpty()) return
        status.text = BidaideBundle.message("adb.db.running")
        background(work = { Sqlite.run(file, sql) }) { result ->
            grid.model = object : DefaultTableModel(
                result.rows.map { it.toTypedArray<Any>() }.toTypedArray(), result.columns.toTypedArray<Any>(),
            ) {
                override fun isCellEditable(row: Int, column: Int) = false
            }
            status.text = result.message
        }
    }

    /** Queries run off the EDT; a failure (bad SQL, mostly) lands in the status line. */
    private fun <T> background(work: () -> T, onSuccess: (T) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(work)
            ApplicationManager.getApplication().invokeLater({
                if (isDisposed) return@invokeLater
                result.onSuccess(onSuccess).onFailure { status.text = BidaideBundle.message("adb.db.error", it.message ?: it.javaClass.simpleName) }
            }, ModalityState.any())
        }
    }
}
