package dev.c4editor.ui

import dev.c4editor.model.Bounds
import dev.c4editor.model.LineStyle
import dev.c4editor.model.Relationship
import dev.c4editor.render.Geometry
import javafx.geometry.Insets
import javafx.scene.Cursor
import javafx.scene.Group
import javafx.scene.control.Label
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.CornerRadii
import javafx.scene.paint.Color
import javafx.scene.shape.Line
import javafx.scene.shape.Polygon
import javafx.scene.text.Font

/** Стрелка связи. Пересчитывает геометрию по границам блоков, поэтому «едет» вслед за ними. */
class EdgeNode(val relationshipId: String) : Group() {
    private val hitArea = Line().apply {
        stroke = Color.TRANSPARENT
        strokeWidth = 12.0
        cursor = Cursor.HAND
    }
    private val line = Line().apply { isMouseTransparent = true }
    private val arrow = Polygon().apply { isMouseTransparent = true }
    private val caption = Label().apply {
        font = Font.font(12.0)
        padding = Insets(1.0, 4.0, 1.0, 4.0)
        background = Background(BackgroundFill(Color.web("#FFFFFFDD"), CornerRadii(3.0), Insets.EMPTY))
        isWrapText = true
        maxWidth = 180.0
    }

    init {
        children.addAll(hitArea, line, arrow, caption)
    }

    fun update(relationship: Relationship, source: Bounds, target: Bounds, selected: Boolean) {
        val color = if (selected) Theme.SELECTED else Theme.EDGE
        line.stroke = color
        line.strokeWidth = if (selected) 2.5 else 1.5
        line.strokeDashArray.setAll(if (relationship.style == LineStyle.DASHED) listOf(8.0, 6.0) else emptyList())
        arrow.fill = color
        caption.text = relationship.label
        caption.isVisible = relationship.label.isNotBlank()
        caption.textFill = if (selected) Theme.SELECTED.darker() else Color.web("#333333")
        layout(source, target)
    }

    fun layout(source: Bounds, target: Bounds) {
        val start = Geometry.clipToRect(source.x, source.y, source.width, source.height, target.centerX, target.centerY)
        val end = Geometry.clipToRect(target.x, target.y, target.width, target.height, source.centerX, source.centerY)
        for (l in listOf(line, hitArea)) {
            l.startX = start[0]
            l.startY = start[1]
            l.endX = end[0]
            l.endY = end[1]
        }
        arrow.points.setAll(Geometry.arrowHead(start[0], start[1], end[0], end[1], 12.0, 5.5).toList())

        caption.applyCss()
        caption.autosize()
        caption.layoutX = (start[0] + end[0]) / 2 - caption.width / 2
        caption.layoutY = (start[1] + end[1]) / 2 - caption.height / 2
    }
}
