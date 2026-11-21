package org.liverreview;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import org.liverreview.Mesh.Axis;
import org.liverreview.Mesh.Bounds;
import org.liverreview.Mesh.Point;

final class MriPanel extends JPanel implements AutoCloseable {
    private static final Color MASK = new Color(250, 199, 113);
    private static final Color MESH = new Color(108, 227, 222);
    private static final Color CROSSHAIR = new Color(241, 160, 219);
    private static final int STEPS = 1000;

    private final MriSlices slices;
    private final Bounds bounds;
    private final Consumer<Point> onMove;
    private final PlaneView[] planes;
    private Point cursor;
    private boolean updating;
    private boolean navigationEnabled = true;

    MriPanel(MriCase mriCase, String mriName, Consumer<Point> onMove) {
        this.onMove = onMove;
        slices = new MriSlices(mriCase);
        bounds = mriCase.mri().bounds();
        setLayout(new BorderLayout(6, 4));
        setOpaque(false);
        setPreferredSize(new Dimension(720, 278));
        var strip = new JPanel(new BorderLayout());
        strip.setOpaque(false);
        var title = new JLabel("MRI");
        title.setToolTipText(mriName);
        strip.add(title, BorderLayout.WEST);
        strip.add(new JLabel(mriCase.hasMeshAlignment()
                ? "Gold: liver mask    Teal: mesh    Pink: cursor" : "Gold: liver mask    Pink: cursor"),
                BorderLayout.EAST);
        add(strip, BorderLayout.NORTH);
        var views = new JPanel(new GridLayout(1, 3, 6, 0));
        views.setOpaque(false);
        planes = new PlaneView[]{new PlaneView(Axis.X, "Sagittal"), new PlaneView(Axis.Y, "Coronal"),
                new PlaneView(Axis.Z, "Axial")};
        for (PlaneView pane : planes) {
            views.add(pane.container);
        }
        add(views, BorderLayout.CENTER);
        try {
            setCursorPoint(mriCase.initialCursor());
        } catch (RuntimeException error) {
            slices.close();
            throw error;
        }
    }

    void setNavigationEnabled(boolean enabled) {
        navigationEnabled = enabled;
        for (PlaneView pane : planes) {
            pane.slider.setEnabled(enabled);
        }
    }

    Point cursorPoint() {
        return cursor;
    }

    void setCursorPoint(Point point) {
        Point next = new Point(
                Math.clamp(point.x(), bounds.min().x(), bounds.max().x()),
                Math.clamp(point.y(), bounds.min().y(), bounds.max().y()),
                Math.clamp(point.z(), bounds.min().z(), bounds.max().z()));
        Point previous = cursor;
        cursor = next;
        updating = true;
        try {
            for (PlaneView pane : planes) {
                if (previous == null || previous.along(pane.axis) != next.along(pane.axis)) {
                    pane.refresh(next.along(pane.axis));
                }
                pane.repaint();
            }
        } finally {
            updating = false;
        }
        onMove.accept(cursor);
    }

    @Override
    public void close() {
        slices.close();
    }

    private final class PlaneView extends JPanel {
        private final Axis axis;
        private final String name;
        private final JPanel container = new JPanel(new BorderLayout());
        private final JLabel title = new JLabel();
        private final JSlider slider = new JSlider(0, STEPS);
        private MriSlices.Slice slice;

        PlaneView(Axis axis, String name) {
            this.axis = axis;
            this.name = name;
            setBackground(new Color(16, 23, 32));
            container.setBorder(BorderFactory.createEmptyBorder(3, 3, 0, 3));
            container.add(title, BorderLayout.NORTH);
            container.add(this, BorderLayout.CENTER);
            container.add(slider, BorderLayout.SOUTH);
            getAccessibleContext().setAccessibleName(name + " MRI view");
            slider.getAccessibleContext().setAccessibleName(name + " slice position");
            slider.addChangeListener(event -> {
                if (!updating && navigationEnabled) {
                    double[] point = cursor.coordinates();
                    point[axis.ordinal()] = bounds.location(axis, slider.getValue() / (double) STEPS);
                    setCursorPoint(Point.of(point));
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    if (navigationEnabled && slice != null && event.getButton() == MouseEvent.BUTTON1) {
                        moveCursor(event);
                    }
                }
            });
        }

        private void moveCursor(MouseEvent event) {
            var view = projection();
            double u = (event.getX() - view.left()) / view.scale() + view.minU();
            double v = view.maxV() - (event.getY() - view.top()) / view.scale();
            Axis horizontal = axis.horizontal();
            Axis vertical = axis.vertical();
            if (u < bounds.min().along(horizontal) || u > bounds.max().along(horizontal)
                    || v < bounds.min().along(vertical) || v > bounds.max().along(vertical)) {
                return;
            }
            double[] point = cursor.coordinates();
            point[horizontal.ordinal()] = u;
            point[vertical.ordinal()] = v;
            setCursorPoint(Point.of(point));
        }

        void refresh(double location) {
            slice = slices.imageAt(axis, location);
            title.setText(String.format(Locale.ROOT, "%s  %.1f mm", name, location));
            slider.setValue((int) Math.round(STEPS * (location - bounds.min().along(axis)) / bounds.size(axis)));
        }

        private Projection projection() {
            Axis horizontal = axis.horizontal();
            Axis vertical = axis.vertical();
            double width = bounds.size(horizontal);
            double height = bounds.size(vertical);
            double pixelU = width / (slice.image().getWidth() - 1);
            double pixelV = height / (slice.image().getHeight() - 1);
            double scale = Math.min(Math.max(1, getWidth() - 12) / (width + pixelU),
                    Math.max(1, getHeight() - 12) / (height + pixelV));
            return new Projection(scale, (getWidth() - (width + pixelU) * scale) / 2,
                    (getHeight() - (height + pixelV) * scale) / 2,
                    bounds.min().along(horizontal) - pixelU / 2,
                    bounds.max().along(vertical) + pixelV / 2, pixelU, pixelV);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (slice == null) {
                return;
            }
            var view = (Graphics2D) graphics.create();
            try {
                var projection = projection();
                var imageToScreen = new AffineTransform();
                imageToScreen.translate(projection.left(), projection.top());
                imageToScreen.scale(projection.scale() * projection.pixelU(),
                        projection.scale() * projection.pixelV());
                view.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                view.drawImage(slice.image(), imageToScreen, null);

                var worldToScreen = new AffineTransform();
                worldToScreen.translate(projection.left(), projection.top());
                worldToScreen.scale(projection.scale(), -projection.scale());
                worldToScreen.translate(-projection.minU(), -projection.maxV());
                view.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                view.setStroke(new BasicStroke(1.6f));
                view.setColor(MASK);
                view.draw(worldToScreen.createTransformedShape(slice.mask()));
                if (slice.mesh() != null) {
                    view.setColor(MESH);
                    view.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                            3, new float[]{4, 3}, 0));
                    view.draw(worldToScreen.createTransformedShape(slice.mesh()));
                }
                int x = (int) Math.round(projection.left()
                        + (cursor.along(axis.horizontal()) - projection.minU()) * projection.scale());
                int y = (int) Math.round(projection.top()
                        + (projection.maxV() - cursor.along(axis.vertical())) * projection.scale());
                view.setColor(CROSSHAIR);
                view.setStroke(new BasicStroke(1));
                view.drawLine(x - 10, y, x + 10, y);
                view.drawLine(x, y - 10, x, y + 10);
                view.setColor(new Color(201, 215, 226));
                view.drawString(axis == Axis.X ? "A" : "R", getWidth() - 14, getHeight() - 6);
                view.drawString(axis == Axis.Z ? "A" : "S", 5, 14);
            } finally {
                view.dispose();
            }
        }
    }

    private record Projection(double scale, double left, double top, double minU, double maxV,
                              double pixelU, double pixelV) {
    }
}
