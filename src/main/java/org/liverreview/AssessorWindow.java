package org.liverreview;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Path2D;
import java.io.IOException;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.liverreview.Mesh.Axis;
import org.liverreview.Mesh.Point;

final class AssessorWindow extends JFrame {
    private static final Color INK = new Color(22, 42, 59);
    private static final Color MUTED = new Color(75, 99, 116);
    private static final Color BACKGROUND = new Color(237, 243, 245);
    private static final Color BORDER = new Color(207, 221, 225);
    private static final Color TEAL = new Color(11, 102, 102);
    private static final int SLICE_STEPS = 1000;

    private final MeshSection section = new MeshSection();
    private final JToggleButton measure = new JToggleButton("Pick two points");
    private final MeshViewport viewport = new MeshViewport(section, measure::isSelected, this::recordPick);
    private final ContourPanel contour = new ContourPanel();
    private final CardLayout viewCards = new CardLayout();
    private final JPanel view = new JPanel(viewCards);
    private final JButton open = new JButton("Open mesh…");
    private final JButton reset = new JButton("Reset view");
    private final JComboBox<Axis> axis = new JComboBox<>(Axis.values());
    private final JSlider position = new JSlider(0, SLICE_STEPS, SLICE_STEPS / 2);
    private final JCheckBox clip = new JCheckBox("Clip 3D model");
    private final JCheckBox flip = new JCheckBox("Flip kept side");
    private final JButton clear = new JButton("Clear");
    private final JButton scaleButton = new JButton("Set scale…");
    private final JLabel file = label("No model loaded", 13, false, MUTED);
    private final JLabel sliceValue = label("—", 14, false, MUTED);
    private final JLabel dimensions = label("—", 14, false, INK);
    private final JLabel distance = label("—", 21, true, TEAL);
    private final JLabel units = label("Model units", 13, false, MUTED);
    private final JLabel status = label("Open an STL or OBJ mesh to begin.", 13, false, MUTED);
    private final Timer sliceTimer = new Timer(30, event -> refreshSection());
    private final DecimalFormat numbers = new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private Mesh mesh;
    private Path loadedPath;
    private Point first;
    private Point second;
    private double mmPerUnit = Double.NaN;

    AssessorWindow() {
        super("Liver Model Assessor");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(790, 610));
        setSize(1180, 760);
        setLocationRelativeTo(null);

        var root = new JPanel(new BorderLayout(14, 12));
        root.setBackground(BACKGROUND);
        root.setBorder(BorderFactory.createEmptyBorder(15, 18, 12, 18));
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(modelView(), BorderLayout.CENTER);
        root.add(sidebar(), BorderLayout.EAST);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);

        sliceTimer.setRepeats(false);
        open.addActionListener(event -> chooseMesh());
        reset.addActionListener(event -> {
            viewport.resetCameraView();
            viewport.Render();
        });
        axis.setSelectedItem(Axis.Z);
        axis.addActionListener(event -> {
            position.setValue(SLICE_STEPS / 2);
            refreshSection();
        });
        position.addChangeListener(event -> {
            if (!sliceTimer.isRunning()) {
                sliceTimer.start();
            }
        });
        clip.addActionListener(event -> refreshSection());
        flip.addActionListener(event -> refreshSection());
        measure.addActionListener(event -> updateReading());
        clear.addActionListener(event -> clearPicks());
        scaleButton.addActionListener(event -> chooseScale());

        var rootPane = getRootPane();
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK), "openMesh");
        rootPane.getActionMap().put("openMesh", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (open.isEnabled()) {
                    chooseMesh();
                }
            }
        });
        enableMeshControls(false);
    }

    private JPanel toolbar() {
        var toolbar = new JPanel(new BorderLayout(10, 0));
        toolbar.setOpaque(false);
        var model = new JPanel();
        model.setOpaque(false);
        model.setLayout(new BoxLayout(model, BoxLayout.Y_AXIS));
        model.add(label("Liver Model Assessor", 24, true, INK));
        model.add(Box.createVerticalStrut(3));
        model.add(file);
        toolbar.add(model, BorderLayout.CENTER);

        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        actions.setOpaque(false);
        actions.add(reset);
        open.setBackground(TEAL);
        open.setForeground(Color.WHITE);
        open.setMnemonic(KeyEvent.VK_O);
        actions.add(open);
        toolbar.add(actions, BorderLayout.EAST);
        return toolbar;
    }

    private JPanel modelView() {
        var empty = new JPanel(new BorderLayout());
        empty.setBackground(new Color(20, 32, 47));
        var instruction = label("Open a liver mesh", 21, true, new Color(204, 221, 227));
        instruction.setHorizontalAlignment(SwingConstants.CENTER);
        empty.add(instruction, BorderLayout.CENTER);
        view.add(empty, "empty");
        view.add(viewport, "mesh");
        view.setBorder(BorderFactory.createLineBorder(BORDER));
        return view;
    }

    private JScrollPane sidebar() {
        var sidebar = new JPanel();
        sidebar.setBackground(BACKGROUND);
        sidebar.setLayout(new BoxLayout(sidebar, BoxLayout.Y_AXIS));
        sidebar.add(card("Slice",
                row(label("Model axis", 14, false, INK), axis),
                position, sliceValue, row(clip, flip)));
        sidebar.add(Box.createVerticalStrut(12));
        contour.setPreferredSize(new Dimension(260, 176));
        sidebar.add(card("Cross-section", contour));
        sidebar.add(Box.createVerticalStrut(12));
        sidebar.add(card("Measurements", dimensions, units,
                row(measure, clear), distance, row(scaleButton)));
        sidebar.add(Box.createVerticalGlue());
        position.getAccessibleContext().setAccessibleName("Slice position");
        axis.getAccessibleContext().setAccessibleName("Model axis");
        var scroll = new JScrollPane(sidebar);
        scroll.setPreferredSize(new Dimension(318, 0));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    private static JPanel card(String name, JComponent... children) {
        var panel = new JPanel();
        panel.setBackground(Color.WHITE);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(12, 13, 12, 13)));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(label(name, 16, true, INK));
        for (JComponent child : children) {
            panel.add(Box.createVerticalStrut(7));
            child.setAlignmentX(Component.LEFT_ALIGNMENT);
            child.setMaximumSize(new Dimension(Integer.MAX_VALUE, child.getPreferredSize().height));
            panel.add(child);
        }
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, panel.getPreferredSize().height));
        return panel;
    }

    private static JPanel row(JComponent... children) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        row.setOpaque(false);
        for (JComponent child : children) {
            child.setOpaque(false);
            row.add(child);
        }
        return row;
    }

    private static JLabel label(String text, int size, boolean bold, Color color) {
        var label = new JLabel(text);
        label.setFont(new Font("Segoe UI", bold ? Font.BOLD : Font.PLAIN, size));
        label.setForeground(color);
        return label;
    }

    private void chooseMesh() {
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Open a liver mesh");
        chooser.setFileFilter(new FileNameExtensionFilter("Surface meshes (*.stl, *.obj)", "stl", "obj"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (loadedPath != null) {
            chooser.setCurrentDirectory(loadedPath.getParent().toFile());
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openMesh(chooser.getSelectedFile().toPath());
        }
    }

    void openMesh(Path path) {
        open.setEnabled(false);
        status.setText("Opening " + path.getFileName() + "…");
        new SwingWorker<Mesh, Void>() {
            @Override
            protected Mesh doInBackground() throws IOException {
                return Mesh.open(path);
            }

            @Override
            protected void done() {
                open.setEnabled(true);
                try {
                    displayMesh(get(), path);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText("File open was interrupted.");
                } catch (ExecutionException error) {
                    status.setText("Could not open " + path.getFileName() + ".");
                    JOptionPane.showMessageDialog(AssessorWindow.this, error.getCause().getMessage(),
                            "Could not open mesh", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void displayMesh(Mesh loaded, Path path) {
        var previous = mesh;
        mesh = loaded;
        loadedPath = path.toAbsolutePath();
        mmPerUnit = Double.NaN;
        first = null;
        second = null;
        measure.setSelected(false);
        section.setMesh(mesh);
        viewport.setMesh(mesh);
        if (previous != null) {
            previous.close();
        }
        axis.setSelectedItem(Axis.Z);
        position.setValue(SLICE_STEPS / 2);
        flip.setSelected(false);
        clip.setSelected(false);
        viewCards.show(view, "mesh");
        enableMeshControls(true);
        file.setText(path.getFileName().toString());
        file.setToolTipText(loadedPath.toString());
        updateReading();
        refreshSection();
    }

    private void enableMeshControls(boolean enabled) {
        for (JComponent control : new JComponent[]{reset, axis, position, clip, flip, measure, clear, scaleButton}) {
            control.setEnabled(enabled);
        }
    }

    private void refreshSection() {
        if (mesh == null) {
            return;
        }
        Axis selected = (Axis) axis.getSelectedItem();
        double location = mesh.bounds().location(selected, position.getValue() / (double) SLICE_STEPS);
        boolean canClip = mesh.bounds().size(selected) > 0;
        position.setEnabled(canClip);
        flip.setEnabled(canClip && clip.isSelected());
        section.update(selected, location, !flip.isSelected());
        Path2D.Double path = section.contour(selected);
        contour.showSection(mesh.bounds(), selected, path);
        sliceValue.setText(selected + " = " + reading(location));
        viewport.setClipping(section, clip.isSelected() && canClip);
        viewport.Render();
    }

    private void recordPick(Point point) {
        if (mesh == null || !measure.isSelected()) {
            return;
        }
        if (first == null || second != null) {
            first = point;
            second = null;
        } else {
            second = point;
        }
        viewport.setPicks(first, second);
        viewport.Render();
        updateReading();
    }

    private void clearPicks() {
        first = null;
        second = null;
        viewport.setPicks(null, null);
        viewport.Render();
        updateReading();
    }

    private void updateReading() {
        if (mesh == null) {
            return;
        }
        var bounds = mesh.bounds();
        dimensions.setText("X " + format(scaled(bounds.size(Axis.X))) + "  ·  Y "
                + format(scaled(bounds.size(Axis.Y))) + "  ·  Z " + format(scaled(bounds.size(Axis.Z))));
        units.setText(Double.isNaN(mmPerUnit) ? "Dimensions in model units" : "Dimensions in mm");
        distance.setText(second == null ? "—" : reading(first.distanceTo(second)));
        clear.setEnabled(first != null);
        if (measure.isSelected()) {
            status.setText(first != null && second == null ? "Click the second point on the mesh."
                    : "Click the first point on the mesh.");
        } else {
            status.setText("Drag to orbit · middle-drag to pan · scroll to zoom");
        }
    }

    private void chooseScale() {
        String current = Double.isNaN(mmPerUnit) ? "" : Double.toString(mmPerUnit);
        String entered = JOptionPane.showInputDialog(this,
                "Millimetres per model unit. Leave blank to use model units.", current);
        if (entered == null) {
            return;
        }
        try {
            if (entered.isBlank()) {
                mmPerUnit = Double.NaN;
            } else {
                double value = Double.parseDouble(entered.strip());
                if (!Double.isFinite(value) || value <= 0
                        || !Double.isFinite(value * mesh.bounds().diagonal())) {
                    throw new NumberFormatException();
                }
                mmPerUnit = value;
            }
            updateReading();
            refreshSection();
        } catch (NumberFormatException error) {
            JOptionPane.showMessageDialog(this, "Enter a positive scale, for example 1 or 0.001.",
                    "Invalid scale", JOptionPane.WARNING_MESSAGE);
        }
    }

    private double scaled(double raw) {
        return Double.isNaN(mmPerUnit) ? raw : raw * mmPerUnit;
    }

    private String reading(double value) {
        return format(scaled(value)) + (Double.isNaN(mmPerUnit) ? " model units" : " mm");
    }

    private String format(double value) {
        if (Math.abs(value) >= 1_000_000 || (value != 0 && Math.abs(value) < 0.001)) {
            return String.format(Locale.ROOT, "%.3g", value);
        }
        return numbers.format(value);
    }
}
