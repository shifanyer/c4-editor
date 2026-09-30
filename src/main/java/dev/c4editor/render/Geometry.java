package dev.c4editor.render;

/** Вспомогательная геометрия для стрелок связей. */
public final class Geometry {

    private Geometry() {
    }

    /**
     * Точка, в которой луч из центра прямоугольника к точке (towardX, towardY)
     * пересекает его границу. Используется, чтобы стрелка упиралась в край блока, а не в центр.
     */
    public static double[] clipToRect(double x, double y, double width, double height,
                                      double towardX, double towardY) {
        double cx = x + width / 2;
        double cy = y + height / 2;
        double dx = towardX - cx;
        double dy = towardY - cy;
        if (dx == 0 && dy == 0) {
            return new double[]{cx, cy};
        }
        double scaleX = dx == 0 ? Double.POSITIVE_INFINITY : (width / 2) / Math.abs(dx);
        double scaleY = dy == 0 ? Double.POSITIVE_INFINITY : (height / 2) / Math.abs(dy);
        double scale = Math.min(scaleX, scaleY);
        return new double[]{cx + dx * scale, cy + dy * scale};
    }

    /** Вершины треугольного наконечника стрелки: [tipX, tipY, leftX, leftY, rightX, rightY]. */
    public static double[] arrowHead(double fromX, double fromY, double tipX, double tipY,
                                     double length, double halfWidth) {
        double angle = Math.atan2(tipY - fromY, tipX - fromX);
        double baseX = tipX - length * Math.cos(angle);
        double baseY = tipY - length * Math.sin(angle);
        double nx = -Math.sin(angle) * halfWidth;
        double ny = Math.cos(angle) * halfWidth;
        return new double[]{tipX, tipY, baseX + nx, baseY + ny, baseX - nx, baseY - ny};
    }
}
