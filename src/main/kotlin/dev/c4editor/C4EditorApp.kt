package dev.c4editor

import dev.c4editor.editor.EditorController
import dev.c4editor.model.Project
import dev.c4editor.storage.ProjectRepository
import dev.c4editor.ui.CodeDiagramView
import dev.c4editor.ui.EditorView
import dev.c4editor.ui.ProjectListView
import javafx.animation.PauseTransition
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import javafx.util.Duration

class C4EditorApp : Application() {
    private val repository = ProjectRepository()
    private lateinit var scene: Scene
    private lateinit var stage: Stage

    /** Автосохранение с небольшой задержкой, чтобы не писать файл на каждое нажатие клавиши. */
    private val saveDelay = PauseTransition(Duration.millis(400.0))
    private var dirty: Project? = null

    override fun start(stage: Stage) {
        this.stage = stage
        CodeDiagramView.hostServices = hostServices
        saveDelay.setOnFinished { flush() }
        scene = Scene(StackPane(), 1400.0, 900.0)
        stage.scene = scene
        stage.setOnCloseRequest { flush() }
        showProjects()
        stage.show()
    }

    private fun showProjects() {
        flush()
        stage.title = "C4 Editor"
        scene.root = ProjectListView(repository, onOpen = ::openProject)
    }

    private fun openProject(project: Project) {
        stage.title = "C4 Editor — ${project.name}"
        val controller = EditorController(project) {
            dirty = it
            saveDelay.playFromStart()
        }
        scene.root = EditorView(controller, onBack = ::showProjects)
    }

    private fun flush() {
        saveDelay.stop()
        dirty?.let(repository::save)
        dirty = null
    }
}
