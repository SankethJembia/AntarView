package org.liverreview;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import javax.swing.JPanel;
import org.liverreview.Mesh.Axis;

final class ContourPanel extends JPanel {
    private Path2D.Double contour = new Path2D.Double();
    private Mesh.Bounds bounds;
    private Axis axis = Axis.Z;

    ContourPanel() {
        setBackground(new Color(20, 32, 47));
        getAccessibleContext().setAccessibleName("Mesh cross-section");
    }

    void showSection(Mesh.Bounds bounds, Axis axis, Path2D.Double contour) {
        this.contour = contour;
        this.bounds = bounds;
        this.axis = axis;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        var view = (Graphics2D) graphics.create();
        try {
            view.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (bounds == null || contour.getCurrentPoint() == null) {
                view.setColor(new Color(174, 191, 202));
                String message = bounds == null ? "Open a mesh to see a cross-section."
                        : "The plane does not intersect the surface.";
                int left = Math.max(8, (getWidth() - view.getFontMetrics().stringWidth(message)) / 2);
                view.drawString(message, left, getHeight() / 2);
                return;
            }

            Axis horizontal = axis.horizontal();
            Axis vertical = axis.vertical();
            int width = getWidth();
            int height = getHeight();
            double scale = Math.min(Math.max(1, width - 56) / Math.max(bounds.size(horizontal), bounds.diagonal() * 0.01),
                    Math.max(1, height - 50) / Math.max(bounds.size(vertical), bounds.diagonal() * 0.01));
            var worldToPanel = new AffineTransform();
            worldToPanel.translate(width / 2.0, height / 2.0 - 3);
            worldToPanel.scale(scale, -scale);
            worldToPanel.translate(-bounds.location(horizontal, 0.5), -bounds.location(vertical, 0.5));

            view.setColor(new Color(51, 71, 88));
            view.drawLine(20, height - 20, width - 20, height - 20);
            view.drawLine(20, height - 20, 20, 18);
            view.setColor(new Color(180, 201, 214));
            view.drawString(horizontal.name(), width - 19, height - 8);
            view.drawString(vertical.name(), 8, 16);
            view.setColor(new Color(250, 206, 114));
            view.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            view.draw(worldToPanel.createTransformedShape(contour));
        } finally {
            view.dispose();
        }
    }
}
