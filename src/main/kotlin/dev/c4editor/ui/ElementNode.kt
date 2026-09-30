package dev.c4editor.ui

import dev.c4editor.model.Bounds
import dev.c4editor.model.Element
import dev.c4editor.model.ElementType
import dev.c4editor.model.PaletteItem
import dev.c4editor.render.ShapeFactory
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.input.MouseButton
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment

object Theme {
    val PERSON: Color = Color.web("#08427B")
    val SYSTEM: Color = Color.web("#999999")
    val DOCUMENTED_SYSTEM: Color = Color.web("#1168BD")
    val FOCUSED_CONTAINER: Color = Color.web("#0B3A6E")
    val CONTAINER: Color = Color.web("#5AA9E6")
    val DATA: Color = Color.web("#1168BD")
    val GROUP_BORDER: Color = Color.BLACK
    val SELECTED: Color = Color.web("#FF8F00")
    val RELATION_SOURCE: Color = Color.web("#2E7D32")
    val EDGE: Color = Color.web("#555555")
    const val CANVAS_BACKGROUND = "#FAFAFA"

    fun fillOf(type: ElementType, focused: Boolean): Color = when (type) {
        ElementType.PERSON -> PERSON
        ElementType.SYSTEM -> if (focused) DOCUMENTED_SYSTEM else SYSTEM
        ElementType.CONTAINER -> if (focused) FOCUSED_CONTAINER else CONTAINER
        ElementType.DATABASE, ElementType.MESSAGE_BROKER -> DATA
        ElementType.COMPONENT -> if (focused) FOCUSED_CONTAINER else CONTAINER
        ElementType.CONTAINER_GROUP -> Color.TRANSPARENT
    }

    /** Маленькая иконка для кнопки палитры. */
    fun paletteIcon(item: PaletteItem): Node {
        val fill = fillOf(item.type, item.documented)
        return when (item.type) {
            ElementType.PERSON -> ShapeFactory.person(16.0, 18.0, fill, fill.darker())
            ElementType.DATABASE -> ShapeFactory.verticalCylinder(16.0, 18.0, fill, fill.darker())
            ElementType.MESSAGE_BROKER -> ShapeFactory.horizontalCylinder(24.0, 14.0, fill, fill.darker())
            ElementType.CONTAINER_GROUP -> ShapeFactory.groupBox(24.0, 16.0, GROUP_BORDER)
            else -> ShapeFactory.roundedBox(24.0, 16.0, fill, fill.darker())
        }
    }
}

data class NodeState(val focused: Boolean, val selected: Boolean, val relationSource: Boolean)

/**
 * Визуальное представление элемента на канвасе. Узел переиспользуется между перерисовками
 * (обработчики мыши навешиваются один раз), меняется только содержимое.
 */
class ElementNode(val elementId: String) : Group() {
    /** Вызывается при перетаскивании маркера изменения размера группы: (dx, dy). */
    var onResize: (Double, Double) -> Unit = { _, _ -> }
    var onResizeFinished: () -> Unit = {}

    var isGroup: Boolean = false
        private set

    /** Идёт ли сейчас изменение размера за маркер — в это время узел нельзя перетаскивать. */
    var isResizing: Boolean = false
        private set

    /** Фигура и подписи; пересоздаются при каждом обновлении. */
    private val content = Group()

    /**
     * Маркер создаётся один раз и никогда не удаляется из узла: если убрать его из сцены
     * посреди жеста, JavaFX перенаправит события перетаскивания группе и она начнёт двигаться.
     */
    private val resizeHandle = createResizeHandle()

    init {
        children.setAll(content, resizeHandle)
    }

    fun update(element: Element, bounds: Bounds, state: NodeState) {
        isGroup = element.type.isGroup
        relocate(bounds)
        val w = bounds.width
        val h = bounds.height
        val fill = Theme.fillOf(element.type, state.focused)
        val stroke = when {
            state.selected -> Theme.SELECTED
            state.relationSource -> Theme.RELATION_SOURCE
            element.type.isGroup -> Theme.GROUP_BORDER
            else -> fill.darker()
        }
        val textColor = if (element.type.isGroup) Color.BLACK else Color.WHITE

        val shape: Node = when (element.type) {
            ElementType.PERSON -> ShapeFactory.person(w, h, fill, stroke)
            ElementType.DATABASE -> ShapeFactory.verticalCylinder(w, h, fill, stroke)
            ElementType.MESSAGE_BROKER -> ShapeFactory.horizontalCylinder(w, h, fill, stroke)
            ElementType.CONTAINER_GROUP -> ShapeFactory.groupBox(w, h, stroke)
            else -> ShapeFactory.roundedBox(w, h, fill, stroke)
        }
        if (state.selected || state.relationSource) {
            shape.lookupAll("*").filterIsInstance<javafx.scene.shape.Shape>().forEach { it.strokeWidth = 3.0 }
        }

        val stereotype = when {
            state.focused && element.type == ElementType.SYSTEM -> "Документируемая система"
            state.focused && element.type == ElementType.CONTAINER -> "Выбранный контейнер"
            state.focused && element.type == ElementType.COMPONENT -> "Выбранный компонент"
            else -> element.type.stereotype
        }
        val description = element.description.takeIf { element.type.hasDescription && it.isNotBlank() }
        val text = if (element.type.isGroup) {
            groupCaption(element.name, textColor)
        } else {
            textBlock(element.name, stereotype, description, textColor)
        }
        placeText(text, element.type, w, h)

        content.children.setAll(shape, text)
        resizeHandle.isVisible = element.type.isGroup
        resizeHandle.x = w - RESIZE_HANDLE_SIZE
        resizeHandle.y = h - RESIZE_HANDLE_SIZE
    }

    fun relocate(bounds: Bounds) {
        layoutX = bounds.x
        layoutY = bounds.y
    }

    private fun placeText(text: VBox, type: ElementType, w: Double, h: Double) {
        val pad = 8.0
        val (x, y, width, height) = when (type) {
            ElementType.PERSON -> {
                val top = ShapeFactory.personBodyTop(w, h)
                listOf(pad, top + 4, w - 2 * pad, h - top - 8)
            }
            ElementType.DATABASE -> {
                val cap = ShapeFactory.cylinderCapRadius(h)
                listOf(pad, cap * 2, w - 2 * pad, h - cap * 3)
            }
            ElementType.MESSAGE_BROKER -> {
                val cap = ShapeFactory.cylinderCapRadius(w)
                listOf(cap + pad, pad, w - cap * 3 - 2 * pad, h - 2 * pad)
            }
            ElementType.CONTAINER_GROUP -> listOf(12.0, 8.0, w - 24, 40.0)
            else -> listOf(pad, pad, w - 2 * pad, h - 2 * pad)
        }
        text.layoutX = x
        text.layoutY = y
        text.setMinSize(width, height)
        text.setPrefSize(width, height)
        text.setMaxSize(width, height)
        text.clip = Rectangle(width, height)
    }

    private fun textBlock(name: String, stereotype: String, description: String?, color: Color): VBox {
        val title = label(name, Font.font(null, FontWeight.BOLD, 14.0), color)
        val kind = label("[$stereotype]", Font.font(11.0), color)
        val box = VBox(2.0, title, kind)
        if (description != null) {
            box.children += label(description, Font.font(12.0), color).apply { padding = Insets(6.0, 0.0, 0.0, 0.0) }
        }
        box.alignment = Pos.CENTER
        box.isMouseTransparent = true
        return box
    }

    private fun groupCaption(name: String, color: Color): VBox {
        val title = label(name, Font.font(null, FontWeight.BOLD, 13.0), color).apply { textAlignment = TextAlignment.LEFT }
        val kind = label("[Группа контейнеров]", Font.font(11.0), color).apply { textAlignment = TextAlignment.LEFT }
        return VBox(0.0, title, kind).apply {
            alignment = Pos.TOP_LEFT
            isMouseTransparent = true
        }
    }

    private fun label(text: String, font: Font, color: Color) = Label(text).apply {
        this.font = font
        textFill = color
        isWrapText = true
        textAlignment = TextAlignment.CENTER
    }

    private fun createResizeHandle(): Rectangle =
        Rectangle(RESIZE_HANDLE_SIZE, RESIZE_HANDLE_SIZE).apply {
            fill = Color.web("#00000033")
            cursor = Cursor.SE_RESIZE
            var lastX = 0.0
            var lastY = 0.0
            var resized = false
            setOnMousePressed { e ->
                e.consume()
                if (e.button != MouseButton.PRIMARY) return@setOnMousePressed
                // Координаты родителя (слоя канваса): не зависят от масштаба и от сдвига самого узла.
                val p = this@ElementNode.parent.sceneToLocal(e.sceneX, e.sceneY)
                lastX = p.x
                lastY = p.y
                resized = false
                isResizing = true
            }
            setOnMouseDragged { e ->
                e.consume()
                if (!isResizing) return@setOnMouseDragged
                val p = this@ElementNode.parent.sceneToLocal(e.sceneX, e.sceneY)
                onResize(p.x - lastX, p.y - lastY)
                lastX = p.x
                lastY = p.y
                resized = true
            }
            setOnMouseReleased { e ->
                e.consume()
                if (isResizing && resized) onResizeFinished()
                isResizing = false
            }
            setOnMouseClicked { it.consume() }
        }

    private companion object {
        const val RESIZE_HANDLE_SIZE = 12.0
    }
}
