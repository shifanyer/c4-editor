package dev.c4editor.render;

import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;
import net.sourceforge.plantuml.error.PSystemError;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * Отрисовка текста PlantUML в PNG. Ошибки синтаксиса PlantUML рисует прямо на картинке,
 * поэтому пользователь видит их на месте диаграммы.
 */
public final class PlantUmlRenderer {

    private static final boolean GRAPHVIZ_AVAILABLE = findGraphviz();

    private PlantUmlRenderer() {
    }

    /** Результат отрисовки: PNG и признак того, что в тексте ошибка (тогда на картинке её описание). */
    public record Rendered(byte[] png, boolean error) {
    }

    public static Rendered render(String source) throws IOException {
        SourceStringReader reader = new SourceStringReader(prepare(source));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (reader.outputImage(out, new FileFormatOption(FileFormat.PNG)) == null) {
            throw new IOException("PlantUML не нашёл диаграмму в тексте");
        }
        boolean error = reader.getBlocks().stream().anyMatch(block -> block.getDiagram() instanceof PSystemError);
        return new Rendered(out.toByteArray(), error);
    }

    /**
     * Дописывает @startuml/@enduml, если их нет, и включает встроенный движок раскладки Smetana,
     * когда Graphviz не установлен (иначе диаграммы классов не нарисуются).
     */
    static String prepare(String source) {
        String text = source.contains("@start") ? source : "@startuml\n" + source + "\n@enduml\n";
        if (GRAPHVIZ_AVAILABLE || text.contains("!pragma layout")) {
            return text;
        }
        int lineEnd = text.indexOf('\n', text.indexOf("@start"));
        if (lineEnd < 0) {
            return text;
        }
        return text.substring(0, lineEnd + 1) + "!pragma layout smetana\n" + text.substring(lineEnd + 1);
    }

    private static boolean findGraphviz() {
        String explicit = System.getenv("GRAPHVIZ_DOT");
        if (explicit != null && new File(explicit).canExecute()) {
            return true;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (new File(dir, "dot").canExecute() || new File(dir, "dot.exe").canExecute()) {
                return true;
            }
        }
        return false;
    }
}
