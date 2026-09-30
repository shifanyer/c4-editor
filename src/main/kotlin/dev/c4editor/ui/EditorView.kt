package dev.c4editor.ui

import dev.c4editor.editor.EditorController
import dev.c4editor.editor.Tool
import dev.c4editor.model.CodeLevel
import dev.c4editor.model.ComponentLevel
import dev.c4editor.model.ContainerLevel
import dev.c4editor.model.ContextLevel
import dev.c4editor.model.DiagramLevel
import dev.c4editor.model.PaletteItem
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Point2D
import javafx.geometry.Pos
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Hyperlink
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.Separator
import javafx.scene.control.TextInputControl
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToggleGroup
import javafx.scene.control.ToolBar
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.input.MouseButton
import kotlin.math.min

/** Экран редактирования проекта: «хлебные крошки», палитра, канвас и панель свойств. */
class EditorView(
    private val controller: EditorController,
    private val onBack: () -> Unit,
) : BorderPane() {
    private val canvas = DiagramCanvas(controller)

    /**
     * Group нужен, чтобы ScrollPane учитывал масштаб канваса в размерах прокрутки.
     * Поля в полэкрана вокруг канваса позволяют поставить в центр окна любую его точку, даже у края.
     */
    private val content = StackPane(Group(canvas)).apply {
        alignment = Pos.TOP_LEFT
        style = "-fx-background-color: ${Theme.CANVAS_BACKGROUND};"
        setOnMouseClicked { e ->
            if (e.target === this && e.button == MouseButton.PRIMARY && e.isStillSincePress) controller.clearSelection()
        }
    }
    private val scroll = ScrollPane(content).apply {
        isPannable = true
        // Постоянные полосы прокрутки: иначе их появление меняет размер окна просмотра и поля канваса.
        hbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
        vbarPolicy = ScrollPane.ScrollBarPolicy.ALWAYS
    }

    /** Вписать диаграмму в экран, как только станет известен размер окна просмотра. */
    private var fitPending = true

    /** Место для редактора диаграммы кода: на этом уровне вместо канваса текст PlantUML. */
    private val codeHost = BorderPane()
    private val propertiesPanel = PropertiesPanel(controller)

    private val breadcrumbs = HBox(6.0).apply {
        alignment = Pos.CENTER_LEFT
        padding = Insets(8.0, 12.0, 8.0, 12.0)
    }
    private val paletteBox = HBox(4.0).apply { alignment = Pos.CENTER_LEFT }
    private val toolGroup = ToggleGroup()
    private val toolButtons = mapOf(
        Tool.RELATION_SOLID to ToggleButton("⟶  Связь"),
        Tool.RELATION_DASHED to ToggleButton("⇢  Пунктирная связь"),
    )
    private val showAll = CheckBox("Показать все элементы")
    private val fitButton = Button("Вписать в экран").apply { setOnAction { fitToContent() } }
    private val status = Label().apply { padding = Insets(4.0, 12.0, 4.0, 12.0) }

    private var paletteLevel: DiagramLevel? = null
    private val toolbar: ToolBar

    init {
        toolButtons.forEach { (tool, button) ->
            button.toggleGroup = toolGroup
            button.setOnAction { controller.selectTool(if (button.isSelected) tool else Tool.SELECT) }
        }
        showAll.setOnAction { controller.setShowAll(showAll.isSelected) }

        toolbar = ToolBar(
            paletteBox,
            Separator(Orientation.VERTICAL),
            *toolButtons.values.toTypedArray(),
            Separator(Orientation.VERTICAL),
            fitButton,
            showAll,
        )
        top = VBox(breadcrumbs, toolbar)
        center = StackPane(scroll, codeHost)

        scroll.viewportBoundsProperty().addListener { _, _, _ ->
            updateMargins()
            if (fitPending) Platform.runLater(::fitToContent)
        }

        addEventFilter(KeyEvent.KEY_PRESSED, ::onKey)
        controller.addListener(::refresh)
        refresh()
    }

    private fun onKey(e: KeyEvent) {
        // Cmd на macOS, Ctrl на Windows/Linux.
        if (e.isShortcutDown && controller.level.hasCanvas) {
            if (e.isShiftDown && (e.code == KeyCode.DIGIT0 || e.code == KeyCode.NUMPAD0)) {
                fitToContent()
                e.consume()
                return
            }
            val factor = when (e.code) {
                KeyCode.EQUALS, KeyCode.PLUS, KeyCode.ADD -> ZOOM_STEP
                KeyCode.MINUS, KeyCode.SUBTRACT -> 1 / ZOOM_STEP
                KeyCode.DIGIT0, KeyCode.NUMPAD0 -> 1 / canvas.zoom
                else -> null
            }
            if (factor != null) {
                zoomBy(factor)
                e.consume()
                return
            }
        }
        if (e.target is TextInputControl) return
        when (e.code) {
            KeyCode.DELETE, KeyCode.BACK_SPACE -> controller.deleteSelection()
            KeyCode.ESCAPE -> if (controller.tool != Tool.SELECT) controller.selectTool(Tool.SELECT) else controller.clearSelection()
            else -> return
        }
        e.consume()
    }

    /** Меняет масштаб, сохраняя под центром окна ту же точку диаграммы. */
    private fun zoomBy(factor: Double) {
        val center = viewportCenter()
        canvas.zoom *= factor
        centerOn(center)
    }

    /**
     * Масштабирует и прокручивает канвас так, чтобы все видимые элементы поместились на экране
     * и оказались по центру. Крупнее 100% не увеличивает.
     */
    private fun fitToContent() {
        val viewport = scroll.viewportBounds
        if (!controller.level.hasCanvas || viewport.width <= 0 || viewport.height <= 0) return
        fitPending = false
        updateMargins()
        val bounds = canvas.contentBounds()
        if (bounds == null) {
            // Пустая диаграмма: масштаб 100%, начало координат в левом верхнем углу.
            canvas.zoom = 1.0
            centerOn(Point2D(viewport.width / 2, viewport.height / 2))
            return
        }
        canvas.zoom = min(
            1.0,
            min(viewport.width / (bounds.width + 2 * FIT_PADDING), viewport.height / (bounds.height + 2 * FIT_PADDING)),
        )
        centerOn(Point2D(bounds.centerX, bounds.centerY))
    }

    /** Прокручивает так, чтобы точка диаграммы оказалась в центре окна просмотра. */
    private fun centerOn(point: Point2D) {
        scroll.applyCss()
        scroll.layout()
        val viewport = scroll.viewportBounds
        val target = content.sceneToLocal(canvas.localToScene(point))
        val extraW = content.width - viewport.width
        val extraH = content.height - viewport.height
        scroll.hvalue = if (extraW > 0) ((target.x - viewport.width / 2) / extraW).coerceIn(0.0, 1.0) else 0.0
        scroll.vvalue = if (extraH > 0) ((target.y - viewport.height / 2) / extraH).coerceIn(0.0, 1.0) else 0.0
    }

    /** Центр видимой области в координатах диаграммы. */
    private fun viewportCenter(): Point2D {
        val viewport = scroll.viewportBounds
        val x = scroll.hvalue * (content.width - viewport.width).coerceAtLeast(0.0) + viewport.width / 2
        val y = scroll.vvalue * (content.height - viewport.height).coerceAtLeast(0.0) + viewport.height / 2
        return canvas.sceneToLocal(content.localToScene(x, y))
    }

    private fun updateMargins() {
        val viewport = scroll.viewportBounds
        val margins = Insets(viewport.height / 2, viewport.width / 2, viewport.height / 2, viewport.width / 2)
        if (content.padding != margins) content.padding = margins
    }

    private fun refresh() {
        val level = controller.level
        renderBreadcrumbs(level)
        if (paletteLevel != level) {
            paletteLevel = level
            paletteBox.children.setAll(level.palette.map(::paletteButton))
            showLevelContent(level)
            fitPending = true
            // После перерисовки канваса, когда известны новые элементы.
            Platform.runLater(::fitToContent)
        }

        toolButtons.forEach { (tool, button) -> button.isSelected = controller.tool == tool }
        showAll.isSelected = controller.showAll
        status.text = statusText()
    }

    /** Канвас с палитрой и панелью свойств или, на уровне кода, редактор PlantUML. */
    private fun showLevelContent(level: DiagramLevel) {
        val canvasLevel = level.hasCanvas
        toolbar.isVisible = canvasLevel
        toolbar.isManaged = canvasLevel
        scroll.isVisible = canvasLevel
        codeHost.center = (level as? CodeLevel)?.let { CodeDiagramView(controller, it.componentId) }
        codeHost.isVisible = !canvasLevel
        right = if (canvasLevel) propertiesPanel else null
        bottom = if (canvasLevel) status else null
    }

    private fun statusText(): String = when {
        controller.tool != Tool.SELECT && controller.relationSource == null -> "Выберите элемент, от которого идёт связь (Esc — отмена)"
        controller.tool != Tool.SELECT -> "Выберите элемент, к которому идёт связь (Esc — отмена)"
        controller.level == ContextLevel && controller.project.documentedSystemId == null ->
            "Щёлкните по системе, чтобы сделать её документируемой"
        controller.level == ContextLevel ->
            "Двойной щелчок по документируемой системе — диаграмма контейнеров"
        controller.level is ContainerLevel && controller.level.focusId(controller.project) == null ->
            "Щёлкните по контейнеру, чтобы выбрать его. Двойной щелчок — диаграмма компонентов"
        controller.level is ContainerLevel -> "Двойной щелчок по контейнеру — диаграмма компонентов"
        controller.level is ComponentLevel && controller.level.focusId(controller.project) == null ->
            "Щёлкните по компоненту, чтобы выбрать его. Двойной щелчок — диаграмма кода"
        controller.level is ComponentLevel -> "Двойной щелчок по компоненту — диаграмма кода"
        else -> ""
    }

    private fun renderBreadcrumbs(level: DiagramLevel) {
        val items = mutableListOf<Node>(
            Button("← Проекты").apply { setOnAction { onBack() } },
            Label(controller.project.name).apply {
                font = Font.font(null, FontWeight.BOLD, 14.0)
                padding = Insets(0.0, 8.0, 0.0, 8.0)
            },
        )
        val path = level.path(controller.project)
        path.forEachIndexed { index, item ->
            if (index > 0) items += Label("›")
            val title = if (item == ContextLevel) "Контекст" else "${levelKind(item)}: ${item.title(controller.project)}"
            items += if (item == level) {
                Label(title).apply { font = Font.font(null, FontWeight.BOLD, 13.0) }
            } else {
                Hyperlink(title).apply { setOnAction { controller.navigate(item) } }
            }
        }
        breadcrumbs.children.setAll(items)
    }

    private fun levelKind(level: DiagramLevel): String = when (level) {
        ContextLevel -> "Контекст"
        is ContainerLevel -> "Контейнеры"
        is ComponentLevel -> "Компоненты"
        is CodeLevel -> "Код"
    }

    private fun paletteButton(item: PaletteItem) = Button(item.label, Theme.paletteIcon(item)).apply {
        setOnAction {
            val center = viewportCenter()
            controller.addElement(item, center.x, center.y)
        }
    }

    private companion object {
        const val ZOOM_STEP = 1.2

        /** Отступ вокруг диаграммы при вписывании в экран, в пикселях диаграммы. */
        const val FIT_PADDING = 40.0
    }
}
