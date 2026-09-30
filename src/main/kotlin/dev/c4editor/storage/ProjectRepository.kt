package dev.c4editor.storage

import dev.c4editor.model.Project
import dev.c4editor.model.newId
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Хранит проекты как JSON-файлы, по одному на проект. */
class ProjectRepository(
    private val directory: Path = Path.of(System.getProperty("user.home"), ".c4editor", "projects"),
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    init {
        Files.createDirectories(directory)
    }

    fun list(): List<Project> =
        directory.listDirectoryEntries()
            .filter { it.extension == "json" }
            .mapNotNull { runCatching { json.decodeFromString<Project>(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun create(name: String): Project = Project(name = name).also(::save)

    fun save(project: Project) {
        project.updatedAt = System.currentTimeMillis()
        val target = fileOf(project.id)
        val tmp = target.resolveSibling("${project.id}.json.tmp")
        tmp.writeText(json.encodeToString(Project.serializer(), project))
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Записывает проект в произвольный файл, выбранный пользователем. */
    fun export(project: Project, target: Path) {
        target.writeText(json.encodeToString(Project.serializer(), project))
    }

    /**
     * Импортирует проект из файла и сохраняет его в хранилище. Если проект с таким id уже есть,
     * импортируется копия с новым id, чтобы не перезаписать существующий. Некорректный id
     * (id становится именем файла) тоже заменяется.
     * Бросает исключение, если файл не является проектом.
     */
    fun import(source: Path): Project {
        val imported = json.decodeFromString<Project>(source.readText())
        val validId = runCatching { UUID.fromString(imported.id).toString() == imported.id }.getOrDefault(false)
        val duplicate = validId && fileOf(imported.id).exists()
        val project = if (!validId || duplicate) {
            Project(
                id = newId(),
                name = if (duplicate) "${imported.name} (копия)" else imported.name,
                elements = imported.elements,
                relationships = imported.relationships,
                documentedSystemId = imported.documentedSystemId,
                focusedContainers = imported.focusedContainers,
                focusedComponents = imported.focusedComponents,
                codeDiagrams = imported.codeDiagrams,
                views = imported.views,
            )
        } else {
            imported
        }
        save(project)
        return project
    }

    fun delete(project: Project) {
        val file = fileOf(project.id)
        if (file.exists()) Files.delete(file)
    }

    private fun fileOf(id: String): Path = directory.resolve("$id.json")

    companion object {
        private val forbiddenChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        /** Имя файла для экспорта: название проекта без символов, запрещённых в именах файлов. */
        fun exportFileName(project: Project): String {
            val base = project.name.replace(forbiddenChars, "_").trim().trimEnd('.', ' ').take(100)
            return "${base.ifEmpty { "project" }}.json"
        }
    }
}
