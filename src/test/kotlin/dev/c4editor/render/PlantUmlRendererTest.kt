package dev.c4editor.render

import dev.c4editor.model.CodeLevel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlantUmlRendererTest {
    private val pngSignature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    @Test
    fun `template renders to png`() {
        val result = PlantUmlRenderer.render(CodeLevel.template("Сервис заказов"))
        assertFalse(result.error())
        assertTrue(result.png().copyOf(4).contentEquals(pngSignature))
    }

    @Test
    fun `class diagram with relations renders without graphviz`() {
        val result = PlantUmlRenderer.render(
            """
            @startuml
            class Order {
              +id: UUID
              +total(): Money
            }
            interface OrderRepository
            Order --> OrderRepository
            @enduml
            """.trimIndent(),
        )
        assertFalse(result.error())
    }

    @Test
    fun `syntax error is reported`() {
        val result = PlantUmlRenderer.render("@startuml\nclass {{{ \n@enduml")
        assertTrue(result.error())
        assertTrue(result.png().isNotEmpty(), "PlantUML рисует описание ошибки")
    }
}
