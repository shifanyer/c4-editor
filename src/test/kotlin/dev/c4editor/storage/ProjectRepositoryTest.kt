package dev.c4editor.storage

import dev.c4editor.model.Element
import dev.c4editor.model.ElementType
import dev.c4editor.model.Project
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProjectRepositoryTest {
    private val storage = Files.createTempDirectory("c4-storage")
    private val outside = Files.createTempDirectory("c4-export")
    private val repository = ProjectRepository(storage)

    @Test
    fun `export file is named after the project`() {
        assertEquals("Интернет-магазин.json", ProjectRepository.exportFileName(Project(name = "Интернет-магазин")))
        assertEquals("a_b_c_.json", ProjectRepository.exportFileName(Project(name = "a/b:c?")))
        assertEquals("project.json", ProjectRepository.exportFileName(Project(name = "  ..")))
    }

    @Test
    fun `exported project is imported into another storage`() {
        val project = repository.create("Магазин")
        project.elements += Element(type = ElementType.SYSTEM, name = "Shop")
        repository.save(project)
        val file = outside.resolve(ProjectRepository.exportFileName(project))
        repository.export(project, file)

        val other = ProjectRepository(Files.createTempDirectory("c4-other"))
        val imported = other.import(file)
        assertEquals(project.id, imported.id)
        assertEquals("Магазин", imported.name)
        assertEquals(listOf("Shop"), other.list().single().elements.map { it.name })
    }

    @Test
    fun `importing an existing project creates a copy`() {
        val project = repository.create("Магазин")
        val file = outside.resolve("export.json")
        repository.export(project, file)

        val copy = repository.import(file)
        assertNotEquals(project.id, copy.id)
        assertEquals("Магазин (копия)", copy.name)
        assertEquals(2, repository.list().size)
    }

    @Test
    fun `unsafe id is replaced and invalid file is rejected`() {
        val file = outside.resolve("evil.json")
        file.writeText("""{"id": "../../evil", "name": "Evil"}""")
        val imported = repository.import(file)
        assertNotEquals("../../evil", imported.id)
        assertTrue(Files.exists(storage.resolve("${imported.id}.json")))

        val garbage = outside.resolve("garbage.json")
        garbage.writeText("not a project")
        assertFailsWith<Exception> { repository.import(garbage) }
    }
}
