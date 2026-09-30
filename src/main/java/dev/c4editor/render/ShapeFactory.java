package dev.c4editor.render;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcTo;
import javafx.scene.shape.Circle;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

/**
 * Геометрия фигур нотации C4. Все фигуры строятся в локальных координатах (0,0)–(width,height).
 */
public final class ShapeFactory {

    private ShapeFactory() {
    }

    /** Прямоугольник со скруглёнными углами: система, контейнер, компонент. */
    public static Shape roundedBox(double width, double height, Color fill, Color stroke) {
        Rectangle rect = new Rectangle(width, height);
        rect.setArcWidth(18);
        rect.setArcHeight(18);
        return style(rect, fill, stroke);
    }

    /** Группа контейнеров: прозрачный прямоугольник с чёрной пунктирной рамкой. */
    public static Shape groupBox(double width, double height, Color stroke) {
        Rectangle rect = new Rectangle(width, height);
        rect.setArcWidth(18);
        rect.setArcHeight(18);
        // Прозрачная (но не null) заливка — чтобы за группу можно было схватить мышью.
        rect.setFill(Color.TRANSPARENT);
        rect.setStroke(stroke);
        rect.setStrokeWidth(1.5);
        rect.getStrokeDashArray().setAll(8.0, 6.0);
        return rect;
    }

    /** Человечек в стиле C4: голова и «плечи». Верх тела — {@link #personBodyTop(double, double)}. */
    public static Shape person(double width, double height, Color fill, Color stroke) {
        double headRadius = personHeadRadius(width, height);
        Circle head = new Circle(width / 2, headRadius, headRadius);
        Rectangle body = new Rectangle(0, personBodyTop(width, height), width, height - personBodyTop(width, height));
        body.setArcWidth(60);
        body.setArcHeight(60);
        return style(Shape.union(head, body), fill, stroke);
    }

    public static double personHeadRadius(double width, double height) {
        return Math.min(width, height) * 0.2;
    }

    public static double personBodyTop(double width, double height) {
        return personHeadRadius(width, height) * 1.75;
    }

    /** Хранилище данных — цилиндр, стоящий на плоской поверхности. */
    public static Node verticalCylinder(double width, double height, Color fill, Color stroke) {
        double rx = width / 2;
        double ry = cylinderCapRadius(height);
        Path body = new Path(
                new MoveTo(0, ry),
                new LineTo(0, height - ry),
                new ArcTo(rx, ry, 0, width, height - ry, false, false),
                new LineTo(width, ry),
                new ArcTo(rx, ry, 0, 0, ry, false, false),
                new ClosePath());
        Ellipse lid = new Ellipse(rx, ry, rx, ry);
        return new Group(style(body, fill, stroke), style(lid, fill.brighter(), stroke));
    }

    public static double cylinderCapRadius(double length) {
        return Math.min(18, length * 0.12);
    }

    /** Брокер сообщений — цилиндр, лежащий на боку. */
    public static Node horizontalCylinder(double width, double height, Color fill, Color stroke) {
        double rx = cylinderCapRadius(width);
        double ry = height / 2;
        Path body = new Path(
                new MoveTo(rx, 0),
                new LineTo(width - rx, 0),
                new ArcTo(rx, ry, 0, width - rx, height, false, true),
                new LineTo(rx, height),
                new ArcTo(rx, ry, 0, rx, 0, false, true),
                new ClosePath());
        Ellipse cap = new Ellipse(width - rx, ry, rx, ry);
        return new Group(style(body, fill, stroke), style(cap, fill.brighter(), stroke));
    }

    private static Shape style(Shape shape, Color fill, Color stroke) {
        shape.setFill(fill);
        shape.setStroke(stroke);
        shape.setStrokeWidth(1.5);
        return shape;
    }
}
