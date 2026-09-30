package dev.c4editor.model

/** Кнопка палитры: тип создаваемого элемента и признак «сразу сделать его выбранным» (документируемая система). */
data class PaletteItem(val type: ElementType, val label: String = type.title, val documented: Boolean = false)

/**
 * Параметры фильтрации отображения.
 * [pinned] — элементы и связи, созданные после выбора фокуса: они остаются видимыми,
 * чтобы пользователь успел их соединить, и скрываются при следующей смене фокуса.
 */
data class Visibility(val showAll: Boolean = true, val pinned: Set<String> = emptySet())

/**
 * Уровень C4-диаграммы. Чтобы добавить новый уровень (например, код),
 * достаточно реализовать этот интерфейс и вернуть его из [drillDown] родительского уровня.
 *
 * У уровня может быть *фокус* — выбранный элемент (документируемая система, выбранный контейнер).
 * Если фокус есть и не включено «Показать все элементы», на диаграмме остаются только фокус,
 * связанные с ним элементы и его связи.
 */
sealed interface DiagramLevel {
    /** Ключ раскладки элементов в [Project.views]. */
    val viewKey: String

    val palette: List<PaletteItem>

    /** true — уровень редактируется на канвасе блоками, false — у уровня свой редактор (диаграмма кода). */
    val hasCanvas: Boolean get() = true

    fun title(project: Project): String

    fun parent(project: Project): DiagramLevel?

    /** Владелец новых элементов данного типа на этом уровне (см. [Element.parentId]). */
    fun parentFor(type: ElementType): String?

    /** Все элементы, которые могут быть на этой диаграмме, без учёта фильтра по фокусу. */
    fun candidates(project: Project): List<Element>

    fun focusId(project: Project): String? = null

    fun canFocus(element: Element): Boolean = false

    fun setFocus(project: Project, elementId: String) {}

    /** Можно ли удалить элемент, находясь на этом уровне. */
    fun canRemove(element: Element): Boolean = true

    fun visibleElements(project: Project, visibility: Visibility): List<Element> {
        val all = candidates(project)
        val focus = focusId(project)?.takeIf { id -> all.any { it.id == id } } ?: return all
        if (visibility.showAll) return all
        val shown = project.neighbours(focus) + focus + visibility.pinned
        // Группы контейнеров остаются, если в них (на любой глубине) есть что-то видимое.
        val groups = all.filter { it.id in shown }
            .flatMap { generateSequence(project.element(it.groupId)) { g -> project.element(g.groupId) } }
            .mapTo(HashSet()) { it.id }
        return all.filter { it.id in shown || it.id in groups }
    }

    fun visibleRelationships(project: Project, visibility: Visibility): List<Relationship> {
        val ids = visibleElements(project, visibility).mapTo(HashSet()) { it.id }
        val focus = focusId(project)?.takeIf { it in ids }
        return project.relationships.filter { rel ->
            rel.sourceId in ids && rel.targetId in ids &&
                (focus == null || visibility.showAll || focus == rel.sourceId || focus == rel.targetId || rel.id in visibility.pinned)
        }
    }

    /** Уровень, на который проваливаемся по двойному клику на элемент, или null. */
    fun drillDown(project: Project, element: Element): DiagramLevel?

    /** Подготовка раскладки при входе на уровень. */
    fun onEnter(project: Project) {}

    /** Путь от корня до этого уровня — для «хлебных крошек». */
    fun path(project: Project): List<DiagramLevel> =
        (parent(project)?.path(project) ?: emptyList()) + this
}

private fun Element.isPersonOrSystem() =
    parentId == null && (type == ElementType.PERSON || type == ElementType.SYSTEM)

/** Уровень 1 — диаграмма системного контекста. Фокус — документируемая система. */
data object ContextLevel : DiagramLevel {
    override val viewKey = "context"

    override val palette = listOf(
        PaletteItem(ElementType.PERSON),
        PaletteItem(ElementType.SYSTEM),
        PaletteItem(ElementType.SYSTEM, "Документируемая система", documented = true),
    )

    override fun title(project: Project) = "Контекст"

    override fun parent(project: Project): DiagramLevel? = null

    override fun parentFor(type: ElementType): String? = null

    override fun candidates(project: Project) = project.elements.filter { it.isPersonOrSystem() }

    override fun focusId(project: Project) = project.documentedSystemId

    override fun canFocus(element: Element) = element.type == ElementType.SYSTEM

    override fun setFocus(project: Project, elementId: String) {
        project.documentedSystemId = elementId
    }

    override fun drillDown(project: Project, element: Element): DiagramLevel? =
        if (element.type == ElementType.SYSTEM && element.id == project.documentedSystemId) ContainerLevel(element.id) else null
}

/**
 * Уровень 2 — диаграмма контейнеров системы. Пользователи и системы, связанные с ней, берутся с уровня
 * контекста: с ними можно соединяться, но добавлять и удалять их здесь нельзя. Фокус — выбранный контейнер.
 */
data class ContainerLevel(val systemId: String) : DiagramLevel {
    override val viewKey get() = "system:$systemId"

    override val palette = listOf(
        PaletteItem(ElementType.CONTAINER),
        PaletteItem(ElementType.DATABASE),
        PaletteItem(ElementType.MESSAGE_BROKER),
        PaletteItem(ElementType.CONTAINER_GROUP),
    )

    override fun title(project: Project) = project.element(systemId)?.name?.ifBlank { null } ?: "Система"

    override fun parent(project: Project): DiagramLevel = ContextLevel

    override fun parentFor(type: ElementType): String? = if (type.isContainerLevel) systemId else null

    /** Контейнеры системы и только те пользователи и системы, что связаны с ней на уровне контекста. */
    override fun candidates(project: Project): List<Element> {
        val related = project.neighbours(systemId)
        return project.elements.filter { it.parentId == systemId || (it.isPersonOrSystem() && it.id in related) }
    }

    override fun focusId(project: Project) = project.focusedContainers[systemId]

    override fun canFocus(element: Element) = element.type == ElementType.CONTAINER && element.parentId == systemId

    override fun setFocus(project: Project, elementId: String) {
        project.focusedContainers[systemId] = elementId
    }

    override fun canRemove(element: Element) = !element.isPersonOrSystem()

    override fun drillDown(project: Project, element: Element): DiagramLevel? =
        if (element.type == ElementType.CONTAINER && element.parentId == systemId) ComponentLevel(element.id) else null
}

/**
 * Уровень 3 — диаграмма компонентов контейнера. Показывает компоненты и собственные хранилища и брокеры
 * контейнера, а также элементы, связанные с ним на диаграмме контейнеров: с ними можно соединяться,
 * но добавлять и удалять их здесь нельзя. Фокус — выбранный компонент.
 */
data class ComponentLevel(val containerId: String) : DiagramLevel {
    override val viewKey get() = "container:$containerId"

    override val palette = listOf(
        PaletteItem(ElementType.COMPONENT),
        PaletteItem(ElementType.DATABASE),
        PaletteItem(ElementType.MESSAGE_BROKER),
    )

    override fun title(project: Project) = project.element(containerId)?.name?.ifBlank { null } ?: "Контейнер"

    override fun parent(project: Project): DiagramLevel? =
        project.element(containerId)?.parentId?.let { ContainerLevel(it) }

    override fun parentFor(type: ElementType): String? = if (type in ownTypes) containerId else null

    override fun candidates(project: Project): List<Element> {
        val related = project.neighbours(containerId)
        return project.elements.filter { it.parentId == containerId || (it.id in related && !it.type.isGroup) }
    }

    override fun focusId(project: Project) = project.focusedComponents[containerId]

    override fun canFocus(element: Element) = element.type == ElementType.COMPONENT && element.parentId == containerId

    override fun setFocus(project: Project, elementId: String) {
        project.focusedComponents[containerId] = elementId
    }

    /** Удалять можно только то, что принадлежит контейнеру: компоненты, его хранилища и брокеры. */
    override fun canRemove(element: Element) = element.parentId == containerId

    override fun drillDown(project: Project, element: Element): DiagramLevel? =
        if (canFocus(element)) CodeLevel(element.id) else null

    private companion object {
        val ownTypes = setOf(ElementType.COMPONENT, ElementType.DATABASE, ElementType.MESSAGE_BROKER)
    }
}

/**
 * Уровень 4 — диаграмма кода компонента: UML в синтаксисе PlantUML ([Project.codeDiagrams]).
 * Блоков на канвасе у этого уровня нет, всё содержимое — текст PlantUML.
 */
data class CodeLevel(val componentId: String) : DiagramLevel {
    override val viewKey get() = "component:$componentId"

    override val palette: List<PaletteItem> = emptyList()

    override val hasCanvas = false

    override fun title(project: Project) = project.element(componentId)?.name?.ifBlank { null } ?: "Компонент"

    override fun parent(project: Project): DiagramLevel? =
        project.element(componentId)?.parentId?.let { ComponentLevel(it) }

    override fun parentFor(type: ElementType): String? = null

    override fun candidates(project: Project): List<Element> = emptyList()

    override fun drillDown(project: Project, element: Element): DiagramLevel? = null

    companion object {
        /** Начальный текст диаграммы кода для нового компонента. */
        fun template(componentName: String): String {
            val name = componentName.replace("\"", "'").ifBlank { "Component" }
            return """
                |@startuml
                |' Диаграмма кода компонента «$name»
                |class "$name" as Component {
                |  +operation()
                |}
                |@enduml
                |""".trimMargin()
        }
    }
}
