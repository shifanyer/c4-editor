package dev.c4editor

import javafx.application.Application

/**
 * Точка входа вынесена из класса [C4EditorApp]: при запуске JavaFX с classpath (а не module path)
 * главный класс не должен наследоваться от Application.
 */
fun main(args: Array<String>) {
    // PlantUML рисует через AWT. Без окон AWT не конкурирует с JavaFX за главный поток на macOS.
    System.setProperty("java.awt.headless", "true")
    Application.launch(C4EditorApp::class.java, *args)
}
