package dev.c4editor.ui

import dev.c4editor.editor.EditorController
import dev.c4editor.editor.Selection
import dev.c4editor.model.ComponentLevel
import dev.c4editor.model.ContainerLevel
import dev.c4editor.model.ContextLevel
import dev.c4editor.model.Element
import dev.c4editor.model.ElementType
import dev.c4editor.model.LineStyle
import dev.c4editor.model.Relationship
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Separator
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.scene.text.FontWeight

/** Панель свойств выбранного элемента или связи. */
class PropertiesPanel(private val controller: EditorController) : VBox(8.0) {
    /** Что сейчас показано; панель перестраивается только при смене выбора, иначе поля ввода теряли бы фокус. */
    private var shownKey: Any? = Unit

    init {
        padding = Insets(12.0)
        prefWidth = 290.0
        minWidth = 290.0
        style = "-fx-background-color: #F2F2F2; -fx-border-color: #D0D0D0; -fx-border-width: 0 0 0 1;"
        controller.addListener(::refresh)
        refresh()
    }

    private fun refresh() {
        val key = Triple(controller.level, controller.selection, controller.level.focusId(controller.project))
        if (key == shownKey) return
        shownKey = key

        children.clear()
        when (val selection = controller.selection) {
            is Selection.OfElement -> controller.project.element(selection.id)?.let(::showElement)
            is Selection.OfRelationship -> controller.project.relationship(selection.id)?.let(::showRelationship)
            null -> showProject()
        }
    }

    private fun showProject() {
        children += header("Проект")
        children += caption("Название")
        children += TextField(controller.project.name).apply {
            textProperty().addListener { _, _, value -> controller.renameProject(value) }
        }
        children += Separator()
        children += hint(levelHint())
    }

    private fun levelHint(): String = when {
        controller.level == ContextLevel ->
            "Добавляйте элементы кнопками на панели инструментов и перетаскивайте их мышью.\n\n" +
                "Щелчок по системе делает её документируемой — на диаграмме остаются только её связи.\n\n" +
                "Двойной щелчок по документируемой системе открывает диаграмму контейнеров."
        controller.level is ContainerLevel ->
            "Щелчок по контейнеру делает его выбранным — если снят флажок «Показать все элементы», " +
                "на диаграмме остаются только его связи.\n\n" +
                "Перетащите контейнер в группу, чтобы вложить его. Размер группы меняется за правый нижний угол.\n\n" +
                "Двойной щелчок по контейнеру открывает диаграмму компонентов."
        controller.level is ComponentLevel ->
            "Щелчок по компоненту делает его выбранным — если снят флажок «Показать все элементы», " +
                "на диаграмме остаются только его связи.\n\n" +
                "Контейнеры, системы и пользователи попадают сюда, если связаны с контейнером; " +
                "с ними можно соединяться.\n\n" +
                "Двойной щелчок по компоненту открывает диаграмму кода."
        else -> ""
    }

    private fun showElement(element: Element) {
        val header = when {
            !controller.isFocused(element) -> element.type.title
            element.type == ElementType.CONTAINER -> "Выбранный контейнер"
            element.type == ElementType.COMPONENT -> "Выбранный компонент"
            else -> "Документируемая система"
        }
        children += header(header)

        children += caption("Название")
        children += TextField(element.name).apply {
            textProperty().addListener { _, _, value -> controller.updateElement(element.id, name = value) }
        }
        if (element.type.hasDescription) {
            children += caption("Описание")
            children += TextArea(element.description).apply {
                isWrapText = true
                prefRowCount = 4
                textProperty().addListener { _, _, value -> controller.updateElement(element.id, description = value) }
            }
        }

        children += Separator()
        controller.level.drillDown(controller.project, element)?.let { target ->
            val text = when (target) {
                is ContainerLevel -> "Открыть диаграмму контейнеров"
                is ComponentLevel -> "Открыть диаграмму компонентов"
                else -> "Открыть диаграмму кода"
            }
            children += wideButton(text) { controller.navigate(target) }
        }
        if (controller.canRemove(element)) {
            children += wideButton("Удалить") { controller.deleteSelection() }.apply {
                style = "-fx-text-fill: #B00020;"
            }
        } else {
            children += hint("Этот элемент добавляется и удаляется на диаграмме уровнем выше.")
        }
    }

    private fun showRelationship(relationship: Relationship) {
        val source = controller.project.element(relationship.sourceId)?.name.orEmpty()
        val target = controller.project.element(relationship.targetId)?.name.orEmpty()
        children += header("Связь")
        children += hint("$source → $target")

        children += caption("Описание связи")
        children += TextField(relationship.label).apply {
            promptText = "Например: Отправляет заказы [HTTPS]"
            textProperty().addListener { _, _, value -> controller.updateRelationship(relationship.id, label = value) }
        }

        children += caption("Линия")
        val group = ToggleGroup()
        LineStyle.entries.forEach { style ->
            children += RadioButton(style.title).apply {
                toggleGroup = group
                isSelected = relationship.style == style
                setOnAction { controller.updateRelationship(relationship.id, style = style) }
            }
        }

        children += Separator()
        children += wideButton("Поменять направление") {
            controller.reverseRelationship(relationship.id)
            shownKey = Unit
            refresh()
        }
        children += wideButton("Удалить") { controller.deleteSelection() }.apply {
            style = "-fx-text-fill: #B00020;"
        }
    }

    private fun header(text: String) = Label(text).apply { font = Font.font(null, FontWeight.BOLD, 16.0) }

    private fun caption(text: String) = Label(text).apply { style = "-fx-text-fill: #555555;" }

    private fun hint(text: String) = Label(text).apply {
        isWrapText = true
        style = "-fx-text-fill: #666666;"
    }

    private fun wideButton(text: String, action: () -> Unit) = Button(text).apply {
        maxWidth = Double.MAX_VALUE
        setOnAction { action() }
    }
}
