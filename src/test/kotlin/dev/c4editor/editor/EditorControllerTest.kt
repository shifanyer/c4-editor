package dev.c4editor.editor

import dev.c4editor.model.CodeLevel
import dev.c4editor.model.ComponentLevel
import dev.c4editor.model.ContainerLevel
import dev.c4editor.model.ContextLevel
import dev.c4editor.model.ElementType
import dev.c4editor.model.PaletteItem
import dev.c4editor.model.Project
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorControllerTest {
    private val controller = EditorController(Project(name = "test"))

    private fun add(type: ElementType, x: Double = 0.0, y: Double = 0.0, documented: Boolean = false) =
        controller.addElement(PaletteItem(type, documented = documented), x, y)

    @Test
    fun `only one system is documented and unrelated elements are hidden`() {
        assertTrue(controller.showAll, "«Показать все элементы» включено по умолчанию")
        controller.setShowAll(false)
        val user = add(ElementType.PERSON)
        val a = add(ElementType.SYSTEM)
        val b = add(ElementType.SYSTEM)
        val c = add(ElementType.SYSTEM)
        controller.createRelationship(user.id, a.id)
        controller.createRelationship(b.id, c.id)
        controller.createRelationship(a.id, b.id)

        controller.clickElement(a.id)
        assertEquals(a.id, controller.project.documentedSystemId)
        assertEquals(setOf(user.id, a.id, b.id), controller.visibleElements().map { it.id }.toSet())
        assertEquals(2, controller.visibleRelationships().size)

        controller.clickElement(c.id)
        assertEquals(c.id, controller.project.documentedSystemId)
        assertEquals(setOf(b.id, c.id), controller.visibleElements().map { it.id }.toSet())

        controller.setShowAll(true)
        assertEquals(4, controller.visibleElements().size)
        assertEquals(3, controller.visibleRelationships().size)
    }

    @Test
    fun `new elements stay visible until focus changes`() {
        controller.setShowAll(false)
        val a = add(ElementType.SYSTEM, documented = true)
        val b = add(ElementType.SYSTEM)
        assertTrue(controller.visibleElements().any { it.id == b.id })

        val c = add(ElementType.SYSTEM)
        controller.clickElement(c.id)
        controller.clickElement(a.id)
        assertEquals(listOf(a.id), controller.visibleElements().map { it.id })
    }

    @Test
    fun `drill down from documented system to containers and components`() {
        val user = add(ElementType.PERSON)
        val system = add(ElementType.SYSTEM)
        controller.createRelationship(user.id, system.id)

        controller.doubleClickElement(system.id)
        assertEquals(ContextLevel, controller.level, "не документируемая система не раскрывается")

        controller.clickElement(system.id)
        controller.doubleClickElement(system.id)
        assertEquals(ContainerLevel(system.id), controller.level)
        assertEquals(listOf(user.id), controller.visibleElements().map { it.id }, "окружение переносится с контекста")

        val container = add(ElementType.CONTAINER)
        assertEquals(system.id, container.parentId)
        controller.doubleClickElement(container.id)
        assertEquals(ComponentLevel(container.id), controller.level)
        assertEquals(listOf(ContextLevel, ContainerLevel(system.id), ComponentLevel(container.id)), controller.level.path(controller.project))
    }

    @Test
    fun `containers join groups and move with them`() {
        val system = add(ElementType.SYSTEM, documented = true)
        controller.navigate(ContainerLevel(system.id))

        val group = add(ElementType.CONTAINER_GROUP, 400.0, 300.0)
        val container = add(ElementType.CONTAINER, 400.0, 300.0)
        val outside = add(ElementType.DATABASE, 2000.0, 2000.0)
        assertEquals(group.id, container.groupId)
        assertNull(outside.groupId)

        val before = controller.boundsOf(container.id).x
        controller.moveBy(group.id, 50.0, 0.0)
        controller.finishGeometryChange()
        assertEquals(before + 50.0, controller.boundsOf(container.id).x)
        assertEquals(group.id, container.groupId)

        controller.moveBy(container.id, 1000.0, 0.0)
        controller.finishGeometryChange()
        assertNull(container.groupId)
    }

    @Test
    fun `deleting a system removes its containers and relationships`() {
        val system = add(ElementType.SYSTEM, documented = true)
        val user = add(ElementType.PERSON)
        controller.createRelationship(user.id, system.id)
        controller.navigate(ContainerLevel(system.id))
        add(ElementType.CONTAINER)
        controller.navigate(ContextLevel)

        controller.clickElement(system.id)
        controller.deleteSelection()
        assertEquals(listOf(user.id), controller.project.elements.map { it.id })
        assertTrue(controller.project.relationships.isEmpty())
        assertNull(controller.project.documentedSystemId)
    }

    @Test
    fun `selected container shows only its relationships`() {
        val person = add(ElementType.PERSON)
        val system = add(ElementType.SYSTEM, documented = true)
        val external = add(ElementType.SYSTEM)
        val other = add(ElementType.SYSTEM)
        controller.createRelationship(system.id, external.id)
        controller.createRelationship(other.id, system.id)
        controller.createRelationship(person.id, system.id)
        controller.navigate(ContainerLevel(system.id))
        assertTrue(
            controller.level.palette.none { it.type == ElementType.SYSTEM || it.type == ElementType.PERSON },
            "пользователи и системы здесь не добавляются",
        )

        val group = add(ElementType.CONTAINER_GROUP, 400.0, 300.0)
        val api = add(ElementType.CONTAINER, 400.0, 300.0)
        val worker = add(ElementType.CONTAINER, 2000.0, 300.0)
        val db = add(ElementType.DATABASE, 2000.0, 1000.0)
        controller.createRelationship(api.id, db.id)
        controller.createRelationship(api.id, external.id)
        controller.createRelationship(worker.id, other.id)
        controller.createRelationship(person.id, worker.id)
        assertEquals(group.id, api.groupId)

        controller.setShowAll(false)
        assertEquals(7, controller.visibleElements().size, "без выбранного контейнера видно всё")

        controller.clickElement(api.id)
        assertEquals(api.id, controller.project.focusedContainers[system.id])
        assertTrue(controller.isFocused(api))
        assertEquals(setOf(api.id, db.id, external.id, group.id), controller.visibleElements().map { it.id }.toSet())
        assertEquals(2, controller.visibleRelationships().size)

        controller.clickElement(db.id)
        assertEquals(api.id, controller.project.focusedContainers[system.id], "хранилище не становится выбранным")

        controller.setShowAll(true)
        assertEquals(7, controller.visibleElements().size)
    }

    @Test
    fun `systems cannot be deleted on the container diagram`() {
        val system = add(ElementType.SYSTEM, documented = true)
        val external = add(ElementType.SYSTEM)
        val unrelated = add(ElementType.SYSTEM)
        val user = add(ElementType.PERSON)
        val stranger = add(ElementType.PERSON)
        controller.createRelationship(system.id, external.id)
        controller.createRelationship(user.id, system.id)
        controller.navigate(ContainerLevel(system.id))
        assertEquals(setOf(external.id, user.id), controller.visibleElements().map { it.id }.toSet(),
            "только связанные с документируемой системой пользователи и системы")

        controller.clickElement(user.id)
        controller.deleteSelection()
        assertTrue(controller.project.element(user.id) != null)
        assertTrue(controller.project.element(stranger.id) != null && controller.project.element(unrelated.id) != null)

        controller.clickElement(external.id)
        controller.deleteSelection()
        assertTrue(controller.project.element(external.id) != null)

        val container = add(ElementType.CONTAINER)
        controller.createRelationship(container.id, external.id)
        assertEquals(1, controller.visibleRelationships().size)
    }

    @Test
    fun `component diagram shows the container's own elements and its neighbours`() {
        val user = add(ElementType.PERSON)
        val system = add(ElementType.SYSTEM, documented = true)
        val external = add(ElementType.SYSTEM)
        controller.createRelationship(user.id, system.id)
        controller.createRelationship(system.id, external.id)
        controller.navigate(ContainerLevel(system.id))
        val api = add(ElementType.CONTAINER)
        val web = add(ElementType.CONTAINER)
        val db = add(ElementType.DATABASE)
        val lonely = add(ElementType.CONTAINER)
        val group = add(ElementType.CONTAINER_GROUP)
        controller.createRelationship(user.id, web.id)
        controller.createRelationship(web.id, api.id)
        controller.createRelationship(api.id, db.id)
        controller.createRelationship(api.id, external.id)
        controller.createRelationship(api.id, group.id)

        controller.clickElement(api.id)
        controller.doubleClickElement(api.id)
        assertEquals(ComponentLevel(api.id), controller.level)
        assertEquals(setOf(web.id, db.id, external.id), controller.visibleElements().map { it.id }.toSet(),
            "только связанные с контейнером элементы, без групп")
        assertTrue(lonely.id !in controller.visibleElements().map { it.id })
        assertEquals(
            setOf(ElementType.COMPONENT, ElementType.DATABASE, ElementType.MESSAGE_BROKER),
            controller.level.palette.map { it.type }.toSet(),
        )

        val service = add(ElementType.COMPONENT)
        val repo = add(ElementType.COMPONENT)
        val cache = add(ElementType.DATABASE)
        assertEquals(api.id, service.parentId)
        assertEquals(api.id, cache.parentId)
        controller.createRelationship(web.id, service.id)
        controller.createRelationship(service.id, repo.id)
        controller.createRelationship(repo.id, db.id)
        controller.createRelationship(repo.id, cache.id)

        // Контейнеры и системы здесь не удаляются, собственные элементы контейнера — удаляются.
        controller.clickElement(web.id)
        controller.deleteSelection()
        assertTrue(controller.project.element(web.id) != null)
        controller.clickElement(external.id)
        controller.deleteSelection()
        assertTrue(controller.project.element(external.id) != null)

        controller.setShowAll(false)
        controller.clickElement(repo.id)
        assertTrue(controller.isFocused(repo))
        assertEquals(repo.id, controller.project.focusedComponents[api.id])
        assertEquals(setOf(repo.id, service.id, db.id, cache.id), controller.visibleElements().map { it.id }.toSet())
        assertEquals(3, controller.visibleRelationships().size)

        controller.clickElement(cache.id)
        controller.deleteSelection()
        assertTrue(controller.project.element(cache.id) == null)
    }

    @Test
    fun `double click on a component opens its PlantUML code diagram`() {
        val system = add(ElementType.SYSTEM, documented = true)
        controller.navigate(ContainerLevel(system.id))
        val api = add(ElementType.CONTAINER)
        controller.navigate(ComponentLevel(api.id))
        val service = add(ElementType.COMPONENT)
        controller.updateElement(service.id, name = "OrderService")

        controller.doubleClickElement(service.id)
        assertEquals(CodeLevel(service.id), controller.level)
        assertEquals(false, controller.level.hasCanvas)
        assertTrue(controller.visibleElements().isEmpty(), "на уровне кода нет блоков — только PlantUML")
        assertTrue(controller.level.palette.isEmpty())
        assertEquals(
            listOf(ContextLevel, ContainerLevel(system.id), ComponentLevel(api.id), CodeLevel(service.id)),
            controller.level.path(controller.project),
        )

        assertTrue(controller.codeOf(service.id).contains("class \"OrderService\""), "шаблон по имени компонента")
        controller.updateCode(service.id, "@startuml\nclass Order\n@enduml")
        assertEquals("@startuml\nclass Order\n@enduml", controller.codeOf(service.id))

        controller.navigate(ContainerLevel(system.id))
        controller.clickElement(api.id)
        controller.deleteSelection()
        assertTrue(controller.project.codeDiagrams.isEmpty(), "код удаляется вместе с контейнером и компонентами")
    }
}
