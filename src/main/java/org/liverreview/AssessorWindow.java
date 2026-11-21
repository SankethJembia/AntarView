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
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
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
    private final MeshViewport viewport = new MeshViewport(section, this::shouldPick, this::recordPick);
    private final ContourPanel contour = new ContourPanel();
    private final CardLayout viewCards = new CardLayout();
    private final JPanel view = new JPanel(viewCards);
    private final JPanel mainView = new JPanel(new BorderLayout(0, 8));
    private final JButton open = new JButton("Open mesh…");
    private final JButton openCase = new JButton("Open MRI case…");
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
    private final JCheckBox showReference = new JCheckBox("3D mask", true);
    private final JCheckBox showGenerated = new JCheckBox("3D mesh", true);
    private final JButton compare = new JButton("Confirm and compare");
    private final JButton annotate = new JButton("Tumor annotations…");
    private final JButton closeCase = new JButton("Close MRI");
    private final JLabel alignment = label("", 13, false, MUTED);
    private final JLabel meshDeviation = label(" ", 13, false, INK);
    private final JLabel maskDeviation = label(" ", 13, false, INK);
    private final JPanel mriControls = new JPanel(new BorderLayout());
    private final Timer sliceTimer = new Timer(30, event -> refreshSection());
    private final DecimalFormat numbers = new DecimalFormat("0.###", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private Mesh mesh;
    private Path loadedPath;
    private MriCase mriCase;
    private MriPanel mriPanel;
    private Path annotationsPath;
    private boolean busy;
    private boolean compared;
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
        mainView.setOpaque(false);
        mainView.add(modelView(), BorderLayout.CENTER);
        root.add(mainView, BorderLayout.CENTER);
        root.add(sidebar(), BorderLayout.EAST);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);

        sliceTimer.setRepeats(false);
        open.addActionListener(event -> chooseMesh());
        openCase.addActionListener(event -> chooseMriCase());
        compare.addActionListener(event -> compareSurfaces());
        annotate.addActionListener(event -> editTumorAnnotations());
        closeCase.addActionListener(event -> clearMriCase());
        showReference.addActionListener(event -> refreshLayers());
        showGenerated.addActionListener(event -> refreshLayers());
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
        actions.add(openCase);
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
        mriControls.setOpaque(false);
        mriControls.setAlignmentX(Component.LEFT_ALIGNMENT);
        mriControls.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        mriControls.add(card("MRI comparison", alignment,
                row(showGenerated, showReference), row(compare, closeCase), meshDeviation, maskDeviation,
                row(annotate)));
        mriControls.setMaximumSize(new Dimension(Integer.MAX_VALUE, mriControls.getPreferredSize().height));
        mriControls.setVisible(false);
        sidebar.add(mriControls);
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
        runBackground("Opening " + path.getFileName() + "…", "Could not open mesh",
                () -> Mesh.open(path), loaded -> displayMesh(loaded, path));
    }

    private void chooseMriCase() {
        var inputs = MriCaseDialog.choose(this, loadedPath);
        if (inputs != null) {
            runBackground("Opening MRI case…", "Could not open MRI case", () -> loadCase(inputs), loaded -> {
                displayMesh(loaded.mesh(), inputs.mesh());
                displayMri(loaded.mriCase(), inputs.mri());
            });
        }
    }

    private static LoadedCase loadCase(MriCaseDialog.Inputs inputs) throws java.io.IOException {
        var transform = inputs.meshToRas();
        Mesh loaded = Mesh.open(inputs.mesh());
        try {
            return new LoadedCase(loaded,
                    MriCase.open(inputs.mri(), inputs.mask(), inputs.assumeMm(), loaded, transform));
        } catch (java.io.IOException | RuntimeException error) {
            loaded.close();
            throw error;
        }
    }

    private <T> void runBackground(String message, String errorTitle, Callable<T> task, Consumer<T> onSuccess) {
        setBusy(true);
        status.setText(message);
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    status.setText("The operation was interrupted.");
                } catch (ExecutionException | RuntimeException error) {
                    status.setText(errorTitle + ".");
                    Throwable cause = error instanceof ExecutionException ? error.getCause() : error;
                    JOptionPane.showMessageDialog(AssessorWindow.this, cause.getMessage(),
                            errorTitle, JOptionPane.ERROR_MESSAGE);
                } finally {
                    setBusy(false);
                }
            }
        }.execute();
    }

    private void setBusy(boolean value) {
        busy = value;
        open.setEnabled(!busy);
        openCase.setEnabled(!busy);
        closeCase.setEnabled(!busy);
        annotate.setEnabled(!busy && mriPanel != null);
        compare.setEnabled(!busy && mriCase != null && mriCase.hasMeshAlignment() && !compared);
        if (mriPanel != null) {
            mriPanel.setNavigationEnabled(!busy);
        }
    }

    private void displayMesh(Mesh loaded, Path path) {
        clearMriCase();
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

    private void displayMri(MriCase loaded, Path path) {
        mriCase = loaded;
        try {
            mriPanel = new MriPanel(loaded, path.getFileName().toString(), this::trackMriCursor);
        } catch (RuntimeException error) {
            clearMriCase();
            throw error;
        }
        mainView.add(mriPanel, BorderLayout.SOUTH);
        mainView.revalidate();
        mainView.repaint();
        viewport.setReference(loaded.referenceInModel());
        boolean linked = loaded.hasMeshAlignment();
        showReference.setVisible(linked);
        showGenerated.setVisible(linked);
        compare.setVisible(linked);
        showReference.setSelected(true);
        showGenerated.setSelected(true);
        alignment.setText(linked ? "Preview · review all three planes" : "Mesh alignment unavailable");
        meshDeviation.setText(" ");
        maskDeviation.setText(" ");
        mriControls.setVisible(true);
        refreshLayers();
        status.setText(linked ? "Click the mesh or a slice to move the MRI cursor."
                : "MRI and liver mask loaded. Choose a mesh transform to compare surfaces.");
    }

    private void clearMriCase() {
        if (mriCase == null) {
            return;
        }
        if (mriPanel != null) {
            mainView.remove(mriPanel);
            mriPanel.close();
            mriPanel = null;
        }
        viewport.setLayers(true, false);
        viewport.setReference(null);
        viewport.setMriCursor(null);
        mriCase.close();
        mriCase = null;
        annotationsPath = null;
        compared = false;
        mriControls.setVisible(false);
        mainView.revalidate();
        mainView.repaint();
        viewport.Render();
        updateReading();
    }

    private void editTumorAnnotations() {
        if (mriPanel != null) {
            var editor = new TumorAnnotationDialog(this, mriCase, annotationsPath, mriPanel.cursorPoint());
            editor.setVisible(true);
            annotationsPath = editor.savedPath();
        }
    }

    private void refreshLayers() {
        boolean linked = mriCase != null && mriCase.hasMeshAlignment();
        viewport.setLayers(!linked || showGenerated.isSelected(), linked && showReference.isSelected());
        viewport.Render();
    }

    private boolean shouldPick() {
        return measure.isSelected() || (!busy && mriCase != null && mriCase.hasMeshAlignment()
                && showGenerated.isSelected());
    }

    private void trackMriCursor(Point ras) {
        if (mriCase != null && mriCase.hasMeshAlignment()) {
            viewport.setMriCursor(mriCase.rasToMesh().apply(ras));
            viewport.Render();
        }
    }

    private void compareSurfaces() {
        MriCase current = mriCase;
        if (current == null || !current.hasMeshAlignment()) {
            return;
        }
        int answer = JOptionPane.showConfirmDialog(this,
                """
                Confirm that the MRI and liver mask belong to the same case.
                Check the mesh orientation, position, and scale against the
                liver outline in all three planes before comparing.
                """,
                "Confirm alignment", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            runBackground("Comparing surfaces…", "Could not compare surfaces",
                    () -> SurfaceDeviation.measure(current.alignedMesh(), current.reference()), result -> {
                        compared = true;
                        alignment.setText("Vertex distances · median / P95");
                        meshDeviation.setText("Mesh → mask  " + formatDeviation(result.meshToMask()));
                        maskDeviation.setText("Mask → mesh  " + formatDeviation(result.maskToMesh()));
                        status.setText("Surface comparison complete.");
                    });
        }
    }

    private String formatDeviation(SurfaceDeviation.Summary summary) {
        return format(summary.median()) + " / " + format(summary.p95()) + " mm";
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
        if (!busy && mriPanel != null && mriCase.hasMeshAlignment()) {
            Point ras = mriCase.meshToRas().apply(point);
            if (mriCase.mri().bounds().contains(ras)) {
                mriPanel.setCursorPoint(ras);
            } else if (!measure.isSelected()) {
                status.setText("The selected point is outside the MRI view.");
            }
        }
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

    private record LoadedCase(Mesh mesh, MriCase mriCase) {
    }
}
