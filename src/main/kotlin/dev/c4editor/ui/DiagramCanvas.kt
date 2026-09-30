package dev.c4editor.ui

import dev.c4editor.editor.EditorController
import dev.c4editor.editor.Selection
import dev.c4editor.editor.Tool
import dev.c4editor.model.Element
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Cursor
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import javafx.scene.shape.Line
import javafx.scene.transform.Scale
import kotlin.math.max

/**
 * Канвас диаграммы. Слои снизу вверх: группы контейнеров, связи, остальные элементы, превью связи.
 */
class DiagramCanvas(private val controller: EditorController) : Pane() {
    private val groupLayer = layer()
    private val edgeLayer = layer()
    private val elementLayer = layer()
    private val previewLine = Line().apply {
        isMouseTransparent = true
        isVisible = false
        stroke = Theme.RELATION_SOURCE
        strokeDashArray.setAll(4.0, 4.0)
    }

    private val elementNodes = mutableMapOf<String, ElementNode>()
    private val edgeNodes = mutableMapOf<String, EdgeNode>()

    /** Масштаб с опорной точкой (0, 0): ScrollPane видит увеличенные границы канваса. */
    private val scale = Scale(1.0, 1.0, 0.0, 0.0)

    /** Текущий масштаб канваса. Координаты модели от него не зависят. */
    var zoom: Double
        get() = scale.x
        set(value) {
            val z = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
            scale.x = z
            scale.y = z
        }

    init {
        transforms += scale
        style = "-fx-background-color: ${Theme.CANVAS_BACKGROUND};"
        children.addAll(groupLayer, edgeLayer, elementLayer, previewLine)

        setOnMouseClicked { e ->
            if (e.target === this && e.button == MouseButton.PRIMARY && e.isStillSincePress) controller.clearSelection()
        }
        addEventFilter(MouseEvent.MOUSE_MOVED) { e ->
            if (previewLine.isVisible) {
                previewLine.endX = e.x
                previewLine.endY = e.y
            }
        }

        controller.addListener(::render)
        render()
    }

    fun render() {
        val elements = controller.visibleElements()
        val visibleIds = elements.mapTo(HashSet()) { it.id }

        elementNodes.keys.retainAll(visibleIds)
        for (layer in listOf(groupLayer, elementLayer)) {
            layer.children.removeIf { (it as ElementNode).elementId !in visibleIds }
        }

        val selectedId = (controller.selection as? Selection.OfElement)?.id
        for (element in elements) {
            val node = elementNodes.getOrPut(element.id) { createElementNode(element.id) }
            val state = NodeState(
                focused = controller.isFocused(element),
                selected = element.id == selectedId,
                relationSource = element.id == controller.relationSource,
            )
            node.update(element, controller.boundsOf(element.id), state)
            val targetLayer = if (node.isGroup) groupLayer else elementLayer
            if (node.parent !== targetLayer) {
                (node.parent as? Pane)?.children?.remove(node)
                targetLayer.children += node
            }
        }
        // Большие группы ниже маленьких, чтобы вложенные группы оставались кликабельными.
        groupLayer.children.setAll(
            groupLayer.children.sortedByDescending { controller.boundsOf((it as ElementNode).elementId).area },
        )

        renderEdges()
        updatePreview()
        cursor = if (controller.tool == Tool.SELECT) Cursor.DEFAULT else Cursor.CROSSHAIR
        resizeToContent()
    }

    private fun renderEdges() {
        val relationships = controller.visibleRelationships()
        val ids = relationships.mapTo(HashSet()) { it.id }
        edgeNodes.keys.retainAll(ids)
        val selectedId = (controller.selection as? Selection.OfRelationship)?.id
        edgeLayer.children.setAll(
            relationships.map { rel ->
                edgeNodes.getOrPut(rel.id) { createEdgeNode(rel.id) }.also {
                    it.update(rel, controller.boundsOf(rel.sourceId), controller.boundsOf(rel.targetId), rel.id == selectedId)
                }
            },
        )
    }

    /** Лёгкий пересчёт только геометрии стрелок — во время перетаскивания. */
    private fun relayoutEdges() {
        for (rel in controller.visibleRelationships()) {
            edgeNodes[rel.id]?.layout(controller.boundsOf(rel.sourceId), controller.boundsOf(rel.targetId))
        }
    }

    private fun updatePreview() {
        val source = controller.relationSource
        previewLine.isVisible = source != null
        if (source != null) {
            val b = controller.boundsOf(source)
            previewLine.startX = b.centerX
            previewLine.startY = b.centerY
            previewLine.endX = b.centerX
            previewLine.endY = b.centerY
        }
    }

    /** Границы всех видимых элементов в координатах диаграммы или null, если диаграмма пуста. */
    fun contentBounds(): Bounds? {
        val all = controller.visibleElements().map { controller.boundsOf(it.id) }
        if (all.isEmpty()) return null
        val minX = all.minOf { it.x }
        val minY = all.minOf { it.y }
        return BoundingBox(minX, minY, all.maxOf { it.x + it.width } - minX, all.maxOf { it.y + it.height } - minY)
    }

    private fun resizeToContent() {
        val elements = controller.visibleElements()
        val maxX = elements.maxOfOrNull { controller.boundsOf(it.id).let { b -> b.x + b.width } } ?: 0.0
        val maxY = elements.maxOfOrNull { controller.boundsOf(it.id).let { b -> b.y + b.height } } ?: 0.0
        setPrefSize(max(MIN_WIDTH, maxX + MARGIN), max(MIN_HEIGHT, maxY + MARGIN))
    }

    private fun createElementNode(elementId: String): ElementNode {
        val node = ElementNode(elementId)
        var lastX = 0.0
        var lastY = 0.0
        var dragged = false

        node.setOnMousePressed { e ->
            if (e.button != MouseButton.PRIMARY) return@setOnMousePressed
            val p = sceneToLocal(e.sceneX, e.sceneY)
            lastX = p.x
            lastY = p.y
            dragged = false
            e.consume()
        }
        node.setOnMouseDragged { e ->
            e.consume()
            if (e.button != MouseButton.PRIMARY || controller.tool != Tool.SELECT || node.isResizing) return@setOnMouseDragged
            // Координаты канваса, а не сцены — чтобы перетаскивание не зависело от масштаба.
            val p = sceneToLocal(e.sceneX, e.sceneY)
            var dx = p.x - lastX
            var dy = p.y - lastY
            lastX = p.x
            lastY = p.y
            // Не даём утащить элемент (вместе с содержимым группы) за левый/верхний край канваса.
            val b = controller.boundsOf(elementId)
            dx = max(dx, -b.x)
            dy = max(dy, -b.y)
            controller.moveBy(elementId, dx, dy).forEach { id ->
                elementNodes[id]?.relocate(controller.boundsOf(id))
            }
            relayoutEdges()
            dragged = true
        }
        node.setOnMouseReleased { e ->
            if (dragged) controller.finishGeometryChange()
            dragged = false
            e.consume()
        }
        node.setOnMouseClicked { e ->
            e.consume()
            if (e.button != MouseButton.PRIMARY || !e.isStillSincePress) return@setOnMouseClicked
            if (e.clickCount >= 2) controller.doubleClickElement(elementId) else controller.clickElement(elementId)
        }

        node.onResize = { dx, dy ->
            val b = controller.boundsOf(elementId)
            controller.resize(elementId, b.width + dx, b.height + dy)
            val element: Element? = controller.project.element(elementId)
            if (element != null) {
                node.update(element, b, NodeState(focused = false, selected = true, relationSource = false))
            }
            relayoutEdges()
        }
        node.onResizeFinished = controller::finishGeometryChange
        return node
    }

    private fun createEdgeNode(relationshipId: String): EdgeNode = EdgeNode(relationshipId).apply {
        setOnMousePressed { it.consume() }
        setOnMouseClicked { e ->
            e.consume()
            if (e.button == MouseButton.PRIMARY) controller.selectRelationship(relationshipId)
        }
    }

    private companion object {
        const val MIN_WIDTH = 5196.0 // 3000 × √3: площадь ×3 при тех же пропорциях
        const val MIN_HEIGHT = 3464.0 // 2000 × √3
        const val MARGIN = 400.0
        const val MIN_ZOOM = 0.1
        const val MAX_ZOOM = 3.0

        fun layer() = Pane().apply { isPickOnBounds = false }
    }
}
