package org.liverreview;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import javax.swing.JPanel;
import org.liverreview.Mesh.Axis;
import org.liverreview.TumorAnnotations.Label;

final class AnnotationSlice extends JPanel {
    private final NiftiVolume mri;
    private final MriGrayscale grayscale;
    private final MriIdentity size;
    private final IntSupplier radius;
    private final BooleanSupplier erase;
    private final Runnable onEdit;
    private final BufferedImage image;
    private final BufferedImage overlay;
    private final double voxelWidth;
    private final double voxelHeight;
    private TumorAnnotations annotations;
    private Label label;
    private int slice;
    private Point cursor;
    private Point lastPaint;
    private boolean drawing;

    AnnotationSlice(MriCase review, IntSupplier radius, BooleanSupplier erase, Runnable onEdit) {
        mri = review.mri();
        size = review.identity();
        grayscale = MriGrayscale.forVolume(mri);
        this.radius = radius;
        this.erase = erase;
        this.onEdit = onEdit;
        image = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_RGB);
        overlay = new BufferedImage(size.width(), size.height(), BufferedImage.TYPE_INT_ARGB);
        voxelWidth = mri.voxelStepMm(Axis.X);
        voxelHeight = mri.voxelStepMm(Axis.Y);
        setBackground(new Color(16, 23, 32));
        getAccessibleContext().setAccessibleName("MRI slice annotation canvas");
        var mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                cursor = voxel(event.getPoint());
                drawing = label != null && cursor != null && event.getButton() == MouseEvent.BUTTON1;
                lastPaint = null;
                paintAtCursor();
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                cursor = voxel(event.getPoint());
                paintAtCursor();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                drawing = false;
                lastPaint = null;
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                cursor = voxel(event.getPoint());
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                cursor = null;
                lastPaint = null;
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    void setLabel(TumorAnnotations annotations, Label label) {
        this.annotations = annotations;
        this.label = label;
        drawing = false;
        lastPaint = null;
        updateOverlay();
    }

    void setSlice(int fileSlice) {
        slice = fileSlice;
        drawing = false;
        lastPaint = null;
        long offset = (long) mri.mapSliceOrder(fileSlice) * size.slicePixels();
        var scalars = mri.data().GetPointData().GetScalars();
        int[] pixels = pixels(image);
        for (int j = 0; j < size.height(); j++) {
            int screen = (size.height() - j - 1) * size.width();
            long source = offset + (long) j * size.width();
            for (int i = 0; i < size.width(); i++) {
                pixels[screen + i] = grayscale.rgb(scalars.GetTuple1(source + i));
            }
        }
        updateOverlay();
    }

    private void paintAtCursor() {
        if (drawing && cursor != null) {
            Point start = lastPaint == null ? cursor : lastPaint;
            if (annotations.stroke(label, slice, start, cursor, radius.getAsInt(), !erase.getAsBoolean())) {
                updateOverlay();
                onEdit.run();
            }
        }
        lastPaint = cursor;
        repaint();
    }

    private void updateOverlay() {
        int[] pixels = pixels(overlay);
        Arrays.fill(pixels, 0);
        var mask = label == null ? null : label.pixels(slice);
        if (mask != null) {
            for (int index = mask.nextSetBit(0); index >= 0; index = mask.nextSetBit(index + 1)) {
                int row = size.height() - index / size.width() - 1;
                pixels[row * size.width() + index % size.width()] = 0xB5F5A962;
            }
        }
        repaint();
    }

    private static int[] pixels(BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    private Point voxel(Point position) {
        var projection = projection();
        double i = (position.x - projection.left()) / projection.pixelWidth();
        double row = (position.y - projection.top()) / projection.pixelHeight();
        if (i < 0 || row < 0 || i >= size.width() || row >= size.height()) {
            return null;
        }
        return new Point((int) i, size.height() - (int) row - 1);
    }

    private Projection projection() {
        double width = size.width() * voxelWidth;
        double height = size.height() * voxelHeight;
        double scale = Math.min(Math.max(1, getWidth() - 20) / width, Math.max(1, getHeight() - 20) / height);
        return new Projection((getWidth() - width * scale) / 2, (getHeight() - height * scale) / 2,
                voxelWidth * scale, voxelHeight * scale);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var view = (Graphics2D) graphics.create();
        try {
            var projection = projection();
            var transform = new AffineTransform();
            transform.translate(projection.left(), projection.top());
            transform.scale(projection.pixelWidth(), projection.pixelHeight());
            view.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            view.drawImage(image, transform, null);
            view.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            view.drawImage(overlay, transform, null);
            if (cursor != null && label != null) {
                int radius = this.radius.getAsInt();
                double x = projection.left() + (cursor.x - radius) * projection.pixelWidth();
                double y = projection.top() + (size.height() - 1 - cursor.y - radius) * projection.pixelHeight();
                view.setColor(erase.getAsBoolean() ? Color.WHITE : new Color(245, 169, 98));
                view.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                view.draw(new Ellipse2D.Double(x, y, (2 * radius + 1) * projection.pixelWidth(),
                        (2 * radius + 1) * projection.pixelHeight()));
            }
        } finally {
            view.dispose();
        }
    }

    private record Projection(double left, double top, double pixelWidth, double pixelHeight) {
    }
}
