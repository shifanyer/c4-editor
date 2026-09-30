package dev.c4editor.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Типы элементов C4. Уровень, на котором элемент доступен в палитре,
 * определяется [DiagramLevel.palette], а не самим типом.
 */
@Serializable
enum class ElementType(
    val title: String,
    /** Подпись-стереотип в блоке, как принято в нотации C4. */
    val stereotype: String,
    val hasDescription: Boolean,
    val defaultWidth: Double,
    val defaultHeight: Double,
) {
    PERSON("Пользователь", "Пользователь", true, 170.0, 180.0),
    SYSTEM("Система", "Программная система", true, 230.0, 130.0),
    CONTAINER("Контейнер", "Контейнер", true, 230.0, 130.0),
    DATABASE("Хранилище данных", "Хранилище данных", false, 150.0, 150.0),
    MESSAGE_BROKER("Брокер сообщений", "Брокер сообщений", false, 230.0, 100.0),
    CONTAINER_GROUP("Группа контейнеров", "Группа", false, 460.0, 320.0),
    COMPONENT("Компонент", "Компонент", true, 230.0, 130.0);

    val isGroup: Boolean get() = this == CONTAINER_GROUP

    /** Элементы, которые принадлежат конкретной системе (живут внутри её диаграммы контейнеров). */
    val isContainerLevel: Boolean get() = this in setOf(CONTAINER, DATABASE, MESSAGE_BROKER, CONTAINER_GROUP)
}

@Serializable
data class Element(
    val id: String = newId(),
    val type: ElementType,
    var name: String,
    var description: String = "",
    /** Для контейнеров — id системы, для компонентов — id контейнера. У людей и систем — null. */
    val parentId: String? = null,
    /** Группа контейнеров, в которую визуально вложен элемент. */
    var groupId: String? = null,
)

@Serializable
enum class LineStyle(val title: String) {
    SOLID("Сплошная"),
    DASHED("Пунктирная"),
}

@Serializable
data class Relationship(
    val id: String = newId(),
    var sourceId: String,
    var targetId: String,
    var label: String = "",
    var style: LineStyle = LineStyle.SOLID,
)

@Serializable
data class Bounds(var x: Double, var y: Double, var width: Double, var height: Double) {
    val centerX: Double get() = x + width / 2
    val centerY: Double get() = y + height / 2
    val area: Double get() = width * height

    fun contains(px: Double, py: Double): Boolean =
        px >= x && px <= x + width && py >= y && py <= y + height
}

/** Раскладка элементов на конкретной диаграмме: один и тот же элемент может стоять в разных местах на разных уровнях. */
@Serializable
data class ViewLayout(val positions: MutableMap<String, Bounds> = mutableMapOf())

@Serializable
class Project(
    val id: String = newId(),
    var name: String,
    val elements: MutableList<Element> = mutableListOf(),
    val relationships: MutableList<Relationship> = mutableListOf(),
    /** Выбранная на уровне контекста «документируемая» система. Может быть только одна. */
    var documentedSystemId: String? = null,
    /** Выбранный контейнер на диаграмме контейнеров каждой системы: id системы → id контейнера. */
    val focusedContainers: MutableMap<String, String> = mutableMapOf(),
    /** Выбранный компонент на диаграмме компонентов каждого контейнера: id контейнера → id компонента. */
    val focusedComponents: MutableMap<String, String> = mutableMapOf(),
    /** Диаграммы кода: id компонента → исходный текст PlantUML. */
    val codeDiagrams: MutableMap<String, String> = mutableMapOf(),
    val views: MutableMap<String, ViewLayout> = mutableMapOf(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    fun element(id: String?): Element? = id?.let { key -> elements.firstOrNull { it.id == key } }

    fun relationship(id: String?): Relationship? = id?.let { key -> relationships.firstOrNull { it.id == key } }

    fun layout(viewKey: String): ViewLayout = views.getOrPut(viewKey) { ViewLayout() }

    fun relationshipsOf(elementId: String): List<Relationship> =
        relationships.filter { it.sourceId == elementId || it.targetId == elementId }

    fun neighbours(elementId: String): Set<String> =
        relationshipsOf(elementId).map { if (it.sourceId == elementId) it.targetId else it.sourceId }.toSet() - elementId

    /** Удаляет элемент вместе с вложенными (контейнеры системы, компоненты контейнера) и всеми связями. */
    fun removeElement(elementId: String) {
        val removed = mutableSetOf<String>()
        fun collect(id: String) {
            if (!removed.add(id)) return
            elements.filter { it.parentId == id }.forEach { collect(it.id) }
        }
        collect(elementId)

        val removedGroup = element(elementId)?.takeIf { it.type.isGroup }
        elements.filter { it.groupId == elementId }.forEach { it.groupId = removedGroup?.groupId }

        elements.removeAll { it.id in removed }
        relationships.removeAll { it.sourceId in removed || it.targetId in removed }
        views.values.forEach { layout -> removed.forEach { layout.positions.remove(it) } }
        removed.forEach { views.remove(ContainerLevel(it).viewKey); views.remove(ComponentLevel(it).viewKey) }
        if (documentedSystemId in removed) documentedSystemId = null
        focusedContainers.entries.removeAll { (system, container) -> system in removed || container in removed }
        focusedComponents.entries.removeAll { (container, component) -> container in removed || component in removed }
        codeDiagrams.keys.removeAll(removed)
    }
}

fun newId(): String = UUID.randomUUID().toString()
