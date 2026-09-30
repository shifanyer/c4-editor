package dev.c4editor.ui

import dev.c4editor.editor.EditorController
import dev.c4editor.render.PlantUmlRenderer
import javafx.animation.PauseTransition
import javafx.application.HostServices
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.SplitPane
import javafx.scene.control.TextArea
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.util.Duration
import java.io.ByteArrayInputStream
import java.util.concurrent.Executors

/**
 * Диаграмма кода компонента: слева текст PlantUML, справа его отрисовка.
 * Текст сохраняется в проект при каждом изменении, картинка перерисовывается с небольшой задержкой.
 */
class CodeDiagramView(
    private val controller: EditorController,
    private val componentId: String,
) : BorderPane() {
    private val editor = TextArea(controller.codeOf(componentId)).apply {
        font = Font.font("Monospaced", 13.0)
        style = "-fx-text-fill: #1F1F1F;"
        isWrapText = false
        VBox.setVgrow(this, Priority.ALWAYS)
    }
    private val image = ImageView().apply { isPreserveRatio = true }
    private val status = Label().apply { padding = Insets(4.0, 12.0, 4.0, 12.0) }
    private val debounce = PauseTransition(Duration.millis(350.0))

    /** Номер последнего запроса отрисовки: результаты устаревших запросов отбрасываются. */
    private var generation = 0

    init {
        val docs = Hyperlink("Синтаксис диаграмм классов").apply {
            setOnAction { hostServices?.showDocument("https://plantuml.com/ru/class-diagram") }
        }
        val editorPane = VBox(
            6.0,
            HBox(8.0, Label("PlantUML").apply { font = Font.font(null, FontWeight.BOLD, 14.0) }, docs).apply {
                alignment = Pos.CENTER_LEFT
            },
            editor,
        ).apply { padding = Insets(8.0) }

        val preview = ScrollPane(StackPane(image).apply { padding = Insets(16.0) }).apply {
            isPannable = true
            isFitToWidth = true
            isFitToHeight = true
            style = "-fx-background: white;"
        }

        center = SplitPane(editorPane, preview).apply { setDividerPositions(0.38) }
        bottom = status

        editor.textProperty().addListener { _, _, text ->
            controller.updateCode(componentId, text)
            status.text = "Изменено…"
            debounce.playFromStart()
        }
        debounce.setOnFinished { render() }
        render()
    }

    private fun render() {
        val source = editor.text
        val requested = ++generation
        status.text = "Отрисовка…"
        renderer.submit {
            val result = runCatching { PlantUmlRenderer.render(source) }
            Platform.runLater {
                if (requested != generation) return@runLater
                result
                    .onSuccess { rendered ->
                        image.image = Image(ByteArrayInputStream(rendered.png()))
                        status.text = if (rendered.error()) "Ошибка в тексте PlantUML — подробности на картинке" else "Готово"
                    }
                    .onFailure { status.text = "Не удалось отрисовать: ${it.message}" }
            }
        }
    }

    companion object {
        /** Открывает ссылки в браузере; задаётся приложением при запуске. */
        var hostServices: HostServices? = null

        /** Один фоновый поток на всё приложение: PlantUML не должен тормозить интерфейс. */
        private val renderer = Executors.newSingleThreadExecutor { task -> Thread(task, "plantuml").apply { isDaemon = true } }
    }
}
