package dev.c4editor.ui

import dev.c4editor.model.Project
import dev.c4editor.storage.ProjectRepository
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.TextInputDialog
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.stage.FileChooser
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Последняя папка экспорта/импорта — чтобы диалог открывался там же. */
private var lastDirectory: File? = null

/** Стартовый экран: список проектов, создание, экспорт и импорт. */
class ProjectListView(
    private val repository: ProjectRepository,
    private val onOpen: (Project) -> Unit,
) : BorderPane() {
    private val list = ListView<Project>()
    private val jsonFilter = FileChooser.ExtensionFilter("Проект C4 Editor (*.json)", "*.json")
    private val dateFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault())

    init {
        padding = Insets(24.0)

        val title = Label("C4 Editor").apply { font = Font.font(null, FontWeight.BOLD, 26.0) }
        val subtitle = Label("Проекты C4-диаграмм").apply { style = "-fx-text-fill: #666666;" }
        top = VBox(4.0, title, subtitle).apply { padding = Insets(0.0, 0.0, 16.0, 0.0) }

        list.placeholder = Label("Проектов пока нет — создайте новый")
        list.setCellFactory {
            object : ListCell<Project>() {
                override fun updateItem(item: Project?, empty: Boolean) {
                    super.updateItem(item, empty)
                    graphic = if (empty || item == null) {
                        null
                    } else {
                        VBox(
                            2.0,
                            Label(item.name).apply { font = Font.font(null, FontWeight.BOLD, 14.0) },
                            Label("Изменён ${dateFormat.format(Instant.ofEpochMilli(item.updatedAt))}").apply {
                                style = "-fx-text-fill: #777777;"
                            },
                        )
                    }
                    text = null
                }
            }
        }
        list.setOnMouseClicked { e ->
            if (e.button == MouseButton.PRIMARY && e.clickCount == 2) list.selectionModel.selectedItem?.let(onOpen)
        }
        center = list

        val create = Button("Новый проект").apply {
            isDefaultButton = true
            setOnAction { createProject() }
        }
        val open = Button("Открыть").apply {
            disableProperty().bind(list.selectionModel.selectedItemProperty().isNull)
            setOnAction { list.selectionModel.selectedItem?.let(onOpen) }
        }
        val delete = Button("Удалить").apply {
            disableProperty().bind(list.selectionModel.selectedItemProperty().isNull)
            setOnAction { list.selectionModel.selectedItem?.let(::deleteProject) }
        }
        val export = Button("Экспорт…").apply {
            disableProperty().bind(list.selectionModel.selectedItemProperty().isNull)
            setOnAction { list.selectionModel.selectedItem?.let(::exportProject) }
        }
        val import = Button("Импорт…").apply { setOnAction { importProjects() } }
        bottom = HBox(8.0, create, open, delete, export, import).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(16.0, 0.0, 0.0, 0.0)
        }

        reload()
    }

    private fun reload() {
        list.items.setAll(repository.list())
    }

    private fun createProject() {
        val dialog = TextInputDialog("Новый проект").apply {
            title = "Новый проект"
            headerText = "Название проекта"
        }
        dialog.showAndWait().map { it.trim() }.filter { it.isNotEmpty() }.ifPresent { name ->
            onOpen(repository.create(name))
        }
    }

    private fun exportProject(project: Project) {
        val chooser = FileChooser().apply {
            title = "Экспорт проекта"
            initialFileName = ProjectRepository.exportFileName(project)
            extensionFilters += jsonFilter
            lastDirectory?.let { initialDirectory = it }
        }
        val file = chooser.showSaveDialog(scene.window) ?: return
        lastDirectory = file.parentFile
        runCatching { repository.export(project, file.toPath()) }
            .onFailure { showError("Не удалось экспортировать проект", it) }
    }

    private fun importProjects() {
        val chooser = FileChooser().apply {
            title = "Импорт проектов"
            extensionFilters += jsonFilter
            lastDirectory?.let { initialDirectory = it }
        }
        val files = chooser.showOpenMultipleDialog(scene.window) ?: return
        lastDirectory = files.first().parentFile

        val imported = mutableListOf<Project>()
        val failed = mutableListOf<String>()
        for (file in files) {
            runCatching { repository.import(file.toPath()) }
                .onSuccess { imported += it }
                .onFailure { failed += file.name }
        }
        reload()
        imported.lastOrNull()?.let { last -> list.items.firstOrNull { it.id == last.id }?.let(list.selectionModel::select) }
        if (failed.isNotEmpty()) {
            Alert(Alert.AlertType.ERROR, "Файлы не являются проектами C4 Editor:\n${failed.joinToString("\n")}").apply {
                headerText = "Часть файлов не импортирована"
            }.showAndWait()
        }
    }

    private fun showError(header: String, error: Throwable) {
        Alert(Alert.AlertType.ERROR, error.message ?: error.toString()).apply { headerText = header }.showAndWait()
    }

    private fun deleteProject(project: Project) {
        val confirm = Alert(Alert.AlertType.CONFIRMATION, "Удалить проект «${project.name}»?", ButtonType.OK, ButtonType.CANCEL)
        confirm.headerText = null
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            repository.delete(project)
            reload()
        }
    }
}
