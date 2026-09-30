package dev.c4editor.editor

import dev.c4editor.model.Bounds
import dev.c4editor.model.CodeLevel
import dev.c4editor.model.ContainerLevel
import dev.c4editor.model.ContextLevel
import dev.c4editor.model.DiagramLevel
import dev.c4editor.model.Element
import dev.c4editor.model.ElementType
import dev.c4editor.model.LineStyle
import dev.c4editor.model.PaletteItem
import dev.c4editor.model.Project
import dev.c4editor.model.Relationship
import dev.c4editor.model.Visibility
import kotlin.math.abs
import kotlin.math.max

sealed interface Selection {
    val id: String

    data class OfElement(override val id: String) : Selection
    data class OfRelationship(override val id: String) : Selection
}

enum class Tool(val lineStyle: LineStyle?) {
    SELECT(null),
    RELATION_SOLID(LineStyle.SOLID),
    RELATION_DASHED(LineStyle.DASHED),
}

/**
 * Состояние редактора и все операции над проектом. Не зависит от JavaFX,
 * UI лишь вызывает методы и перерисовывается по [addListener].
 */
class EditorController(
    val project: Project,
    private val persist: (Project) -> Unit = {},
) {
    var level: DiagramLevel = ContextLevel
        private set
    var selection: Selection? = null
        private set
    var showAll: Boolean = true
        private set
    var tool: Tool = Tool.SELECT
        private set

    /** Первый элемент создаваемой связи (при активном инструменте связи). */
    var relationSource: String? = null
        private set

    private val pinned = mutableSetOf<String>()
    private val listeners = mutableListOf<() -> Unit>()

    val visibility: Visibility get() = Visibility(showAll, pinned.toSet())

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun visibleElements(): List<Element> = level.visibleElements(project, visibility)

    fun visibleRelationships(): List<Relationship> = level.visibleRelationships(project, visibility)

    fun boundsOf(elementId: String): Bounds {
        val positions = project.layout(level.viewKey).positions
        return positions.getOrPut(elementId) {
            // Элемент с уровня выше впервые попал на эту диаграмму — ставим его туда же, где он стоял там.
            val type = project.element(elementId)?.type ?: ElementType.SYSTEM
            val base = level.path(project).dropLast(1).asReversed()
                .firstNotNullOfOrNull { project.views[it.viewKey]?.positions?.get(elementId) }
            base?.copy() ?: Bounds(80.0, 80.0, type.defaultWidth, type.defaultHeight)
        }
    }

    /** Элемент — фокус текущего уровня: документируемая система или выбранный контейнер. */
    fun isFocused(element: Element): Boolean = element.id == level.focusId(project)

    fun canRemove(element: Element): Boolean = level.canRemove(element)

    /** Текст PlantUML диаграммы кода компонента; для нового компонента — шаблон. */
    fun codeOf(componentId: String): String =
        project.codeDiagrams[componentId] ?: CodeLevel.template(project.element(componentId)?.name.orEmpty())

    fun updateCode(componentId: String, source: String) {
        project.codeDiagrams[componentId] = source
        changed()
    }

    fun canDrillDown(element: Element): Boolean = level.drillDown(project, element) != null

    // ---------- Создание ----------

    fun addElement(item: PaletteItem, centerX: Double, centerY: Double): Element {
        val type = item.type
        val element = Element(type = type, name = type.title, parentId = level.parentFor(type))
        project.elements += element

        val positions = project.layout(level.viewKey).positions
        var x = max(0.0, centerX - type.defaultWidth / 2)
        var y = max(0.0, centerY - type.defaultHeight / 2)
        while (positions.values.any { abs(it.x - x) < 1 && abs(it.y - y) < 1 }) {
            x += 30
            y += 30
        }
        val bounds = Bounds(x, y, type.defaultWidth, type.defaultHeight)
        positions[element.id] = bounds
        if (element.parentId == null && level != ContextLevel) {
            // Люди и системы — общие для модели, поэтому появляются и на диаграмме контекста.
            project.layout(ContextLevel.viewKey).positions[element.id] = bounds.copy()
        }

        if (item.documented && level.canFocus(element)) setFocus(element.id) else pinned += element.id
        selection = Selection.OfElement(element.id)
        recomputeGroups()
        changed()
        return element
    }

    fun createRelationship(sourceId: String, targetId: String, style: LineStyle = LineStyle.SOLID): Relationship? {
        if (sourceId == targetId || project.element(sourceId) == null || project.element(targetId) == null) return null
        val relationship = Relationship(sourceId = sourceId, targetId = targetId, style = style)
        project.relationships += relationship
        pinned += relationship.id
        selection = Selection.OfRelationship(relationship.id)
        changed()
        return relationship
    }

    // ---------- Взаимодействие ----------

    fun clickElement(elementId: String) {
        val element = project.element(elementId) ?: return
        val style = tool.lineStyle
        if (style != null) {
            val source = relationSource
            if (source == null) {
                relationSource = elementId
                changed(persist = false)
            } else if (source != elementId) {
                tool = Tool.SELECT
                relationSource = null
                createRelationship(source, elementId, style)
            }
            return
        }
        selection = Selection.OfElement(elementId)
        if (level.canFocus(element)) {
            setFocus(elementId)
            changed()
        } else {
            changed(persist = false)
        }
    }

    fun doubleClickElement(elementId: String) {
        val element = project.element(elementId) ?: return
        level.drillDown(project, element)?.let(::navigate)
    }

    fun selectRelationship(relationshipId: String) {
        if (tool != Tool.SELECT) return
        selection = Selection.OfRelationship(relationshipId)
        changed(persist = false)
    }

    fun clearSelection() {
        selection = null
        relationSource = null
        changed(persist = false)
    }

    fun selectTool(newTool: Tool) {
        tool = newTool
        relationSource = null
        changed(persist = false)
    }

    fun setShowAll(value: Boolean) {
        showAll = value
        changed(persist = false)
    }

    fun navigate(target: DiagramLevel) {
        level = target
        level.onEnter(project)
        pinned.clear()
        selection = null
        tool = Tool.SELECT
        relationSource = null
        recomputeGroups()
        changed()
    }

    private fun setFocus(elementId: String) {
        if (level.focusId(project) != elementId) {
            level.setFocus(project, elementId)
            pinned.clear()
        }
    }

    // ---------- Перемещение и размеры ----------

    /** Сдвигает элемент (и всё содержимое группы). Возвращает id перемещённых элементов. */
    fun moveBy(elementId: String, dx: Double, dy: Double): Set<String> {
        val moved = setOf(elementId) + groupContents(elementId)
        moved.forEach { id ->
            val b = boundsOf(id)
            b.x += dx
            b.y += dy
        }
        return moved
    }

    fun resize(elementId: String, width: Double, height: Double) {
        val b = boundsOf(elementId)
        b.width = max(120.0, width)
        b.height = max(80.0, height)
    }

    /** Вызывается по окончании перетаскивания/изменения размера. */
    fun finishGeometryChange() {
        recomputeGroups()
        changed()
    }

    private fun groupContents(groupId: String): Set<String> {
        if (project.element(groupId)?.type?.isGroup != true) return emptySet()
        val result = mutableSetOf<String>()
        val queue = ArrayDeque(listOf(groupId))
        val visible = visibleElements()
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            visible.filter { it.groupId == current && result.add(it.id) }.forEach { queue += it.id }
        }
        return result
    }

    /**
     * Элемент принадлежит самой маленькой группе, которая больше его и содержит его центр.
     * Строгое сравнение площадей исключает циклы вложенности.
     */
    fun recomputeGroups() {
        if (level !is ContainerLevel) return
        val items = visibleElements().filter { it.type.isContainerLevel }
        val groups = items.filter { it.type.isGroup }
        items.forEach { item ->
            val b = boundsOf(item.id)
            item.groupId = groups
                .filter { it.id != item.id }
                .map { it to boundsOf(it.id) }
                .filter { (_, g) -> g.area > b.area && g.contains(b.centerX, b.centerY) }
                .minByOrNull { (_, g) -> g.area }
                ?.first?.id
        }
    }

    // ---------- Редактирование ----------

    fun updateElement(elementId: String, name: String? = null, description: String? = null) {
        val element = project.element(elementId) ?: return
        name?.let { element.name = it }
        description?.let { element.description = it }
        changed()
    }

    fun updateRelationship(relationshipId: String, label: String? = null, style: LineStyle? = null) {
        val relationship = project.relationship(relationshipId) ?: return
        label?.let { relationship.label = it }
        style?.let { relationship.style = it }
        changed()
    }

    fun reverseRelationship(relationshipId: String) {
        val relationship = project.relationship(relationshipId) ?: return
        relationship.sourceId = relationship.targetId.also { relationship.targetId = relationship.sourceId }
        changed()
    }

    fun renameProject(name: String) {
        project.name = name
        changed()
    }

    fun deleteSelection() {
        when (val current = selection) {
            is Selection.OfElement -> {
                val element = project.element(current.id) ?: return
                if (!level.canRemove(element)) return
                project.removeElement(current.id)
            }
            is Selection.OfRelationship -> project.relationships.removeAll { it.id == current.id }
            null -> return
        }
        selection = null
        recomputeGroups()
        changed()
    }

    private fun changed(persist: Boolean = true) {
        if (persist) persist(project)
        listeners.forEach { it() }
    }
}
