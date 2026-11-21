package org.liverreview;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import org.liverreview.TumorAnnotations.Label;

final class TumorAnnotationDialog extends JDialog {
    private final MriIdentity identity;
    private final DefaultListModel<Label> labels = new DefaultListModel<>();
    private final JList<Label> list = new JList<>(labels);
    private final JLabel slicePosition = new JLabel();
    private final JLabel maskInfo = new JLabel();
    private final JLabel status = new JLabel(" ");
    private final JCheckBox reviewed = new JCheckBox("Reviewed");
    private final JCheckBox erase = new JCheckBox("Erase");
    private final JSpinner radius = new JSpinner(new SpinnerNumberModel(3, 0, 30, 1));
    private final JButton add = new JButton("New label…");
    private final JButton jump = new JButton("Show first marked slice");
    private final JButton remove = new JButton("Remove label…");
    private final JSlider slice;
    private final AnnotationSlice canvas;
    private TumorAnnotations annotations;
    private Path path;
    private boolean dirty;

    TumorAnnotationDialog(JFrame owner, MriCase review, Path saved, Mesh.Point currentRas) {
        super(owner, "Tumor annotations", true);
        identity = review.identity();
        annotations = new TumorAnnotations(identity);
        slice = new JSlider(0, identity.depth() - 1);
        canvas = new AnnotationSlice(review, () -> (Integer) radius.getValue(), erase::isSelected, this::edited);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeEditor();
            }
        });

        var root = new JPanel(new BorderLayout(12, 8));
        root.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        root.add(toolbar(), BorderLayout.NORTH);
        root.add(imagePanel(), BorderLayout.CENTER);
        root.add(labelPanel(), BorderLayout.EAST);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(930, 680);
        setMinimumSize(new Dimension(690, 490));
        setLocationRelativeTo(owner);

        add.addActionListener(event -> addLabel());
        remove.addActionListener(event -> removeLabel());
        reviewed.addActionListener(event -> {
            Label label = list.getSelectedValue();
            if (label != null && label.setReviewed(reviewed.isSelected())) {
                edited();
            }
        });
        jump.addActionListener(event -> {
            Label label = list.getSelectedValue();
            if (label != null && !label.isEmpty()) {
                slice.setValue(label.firstSlice());
            }
        });
        list.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                canvas.setLabel(annotations, list.getSelectedValue());
                refreshControls();
            }
        });
        erase.addActionListener(event -> canvas.repaint());
        radius.addChangeListener(event -> canvas.repaint());
        slice.addChangeListener(event -> refreshSlice());
        canvas.addMouseWheelListener(event -> slice.setValue(slice.getValue() + event.getWheelRotation()));
        if (saved != null) {
            read(saved);
        }

        var nativePoint = review.mri().nativeToRas().inverse().apply(currentRas);
        double nativeK = (nativePoint.z() - review.mri().data().GetOrigin()[2]) / review.mri().data().GetSpacing()[2];
        int selected = (int) Math.round(Math.clamp(nativeK, 0, identity.depth() - 1));
        slice.setValue(review.mri().mapSliceOrder(selected));
        refreshSlice();
        refreshControls();
    }

    Path savedPath() {
        return path;
    }

    private JPanel toolbar() {
        var panel = new JPanel(new BorderLayout());
        var files = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        var open = new JButton("Open annotations…");
        var save = new JButton("Save");
        var saveAs = new JButton("Save as…");
        open.addActionListener(event -> chooseAnnotations());
        save.addActionListener(event -> save(false));
        saveAs.addActionListener(event -> save(true));
        files.add(open);
        files.add(save);
        files.add(saveAs);
        panel.add(files, BorderLayout.WEST);
        var close = new JButton("Done");
        close.addActionListener(event -> closeEditor());
        panel.add(close, BorderLayout.EAST);
        return panel;
    }

    private JPanel imagePanel() {
        var panel = new JPanel(new BorderLayout(0, 5));
        var tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        var brushLabel = new JLabel("Brush radius (voxels)");
        brushLabel.setLabelFor(radius);
        tools.add(brushLabel);
        tools.add(radius);
        tools.add(erase);
        panel.add(tools, BorderLayout.NORTH);
        panel.add(canvas, BorderLayout.CENTER);
        var navigation = new JPanel(new BorderLayout(6, 0));
        slice.getAccessibleContext().setAccessibleName("Original MRI slice");
        navigation.add(slicePosition, BorderLayout.WEST);
        navigation.add(slice, BorderLayout.CENTER);
        panel.add(navigation, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel labelPanel() {
        var panel = new JPanel(new BorderLayout(0, 6));
        panel.setPreferredSize(new Dimension(240, 0));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.getAccessibleContext().setAccessibleName("Tumor labels");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean selected, boolean focused) {
                putClientProperty("html.disable", true);
                String text = value instanceof Label label
                        ? label.name() + (label.reviewed() ? "  ·  Reviewed" : "  ·  Draft") : "";
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
        panel.add(add, BorderLayout.NORTH);
        panel.add(new JScrollPane(list), BorderLayout.CENTER);
        var actions = new JPanel(new java.awt.GridLayout(0, 1, 0, 6));
        actions.add(maskInfo);
        actions.add(reviewed);
        actions.add(jump);
        actions.add(remove);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private void addLabel() {
        String name = JOptionPane.showInputDialog(this, "Label name", "Tumor " + (labels.size() + 1));
        if (name != null) {
            try {
                Label label = annotations.add(name.strip());
                labels.addElement(label);
                list.setSelectedValue(label, true);
                edited();
            } catch (IllegalArgumentException error) {
                JOptionPane.showMessageDialog(this, error.getMessage(), "Invalid label name", JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void removeLabel() {
        Label label = list.getSelectedValue();
        if (label != null && JOptionPane.showConfirmDialog(this, "Remove the selected label and its mask?",
                "Remove label", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            int index = list.getSelectedIndex();
            annotations.remove(label);
            labels.removeElement(label);
            if (!labels.isEmpty()) {
                list.setSelectedIndex(Math.min(index, labels.size() - 1));
            }
            edited();
        }
    }

    private void refreshSlice() {
        slicePosition.setText("Source slice " + (slice.getValue() + 1) + " of " + identity.depth());
        canvas.setSlice(slice.getValue());
    }

    private void refreshControls() {
        Label label = list.getSelectedValue();
        add.setEnabled(labels.size() < TumorAnnotations.MAX_LABELS);
        remove.setEnabled(label != null);
        erase.setEnabled(label != null);
        radius.setEnabled(label != null);
        reviewed.setEnabled(label != null && !label.isEmpty());
        reviewed.setSelected(label != null && label.reviewed());
        jump.setEnabled(label != null && !label.isEmpty());
        if (label == null) {
            maskInfo.setText("Choose or create a label.");
            maskInfo.setToolTipText(null);
        } else {
            int count = label.sliceCount();
            maskInfo.setText(count + (count == 1 ? " slice marked" : " slices marked"));
            maskInfo.setToolTipText("Label ID " + label.id());
        }
    }

    private void edited() {
        dirty = true;
        list.repaint();
        refreshControls();
        status.setText("Unsaved changes");
    }

    private void chooseAnnotations() {
        if (confirmLeave()) {
            var chooser = chooser();
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                read(chooser.getSelectedFile().toPath());
            }
        }
    }

    private void read(Path selected) {
        try {
            TumorAnnotations loaded = TumorAnnotations.load(selected, identity);
            annotations = loaded;
            path = selected;
            dirty = false;
            labels.clear();
            loaded.labels().forEach(labels::addElement);
            if (labels.isEmpty()) {
                canvas.setLabel(annotations, null);
            } else {
                list.setSelectedIndex(0);
                Label first = list.getSelectedValue();
                if (!first.isEmpty()) {
                    slice.setValue(first.firstSlice());
                }
            }
            refreshControls();
            status.setText("Opened " + path.getFileName());
        } catch (IOException error) {
            alert("Could not open annotations", error);
        }
    }

    private boolean save(boolean choosePath) {
        Path selected = path;
        if (selected == null || choosePath) {
            var chooser = chooser();
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            selected = chooser.getSelectedFile().toPath();
            if (!selected.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(TumorAnnotations.EXTENSION)) {
                selected = selected.resolveSibling(selected.getFileName() + TumorAnnotations.EXTENSION);
            }
            if (Files.exists(selected) && JOptionPane.showConfirmDialog(this, "Replace the selected annotation file?",
                    "Replace annotations", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                return false;
            }
        }
        try {
            annotations.save(selected);
            path = selected;
            dirty = false;
            status.setText("Saved " + path.getFileName());
            return true;
        } catch (IOException error) {
            alert("Could not save annotations", error);
            return false;
        }
    }

    private JFileChooser chooser() {
        var chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Liver annotations (*.liverlabels)", "liverlabels"));
        chooser.setAcceptAllFileFilterUsed(false);
        if (path != null) {
            chooser.setSelectedFile(path.toFile());
        }
        return chooser;
    }

    private void alert(String message, IOException error) {
        JOptionPane.showMessageDialog(this, error.getMessage(), message, JOptionPane.ERROR_MESSAGE);
    }

    private void closeEditor() {
        if (confirmLeave()) {
            dispose();
        }
    }

    private boolean confirmLeave() {
        if (!dirty) {
            return true;
        }
        int choice = JOptionPane.showOptionDialog(this, "Save your annotation changes?", "Unsaved changes",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new String[]{"Save", "Discard", "Cancel"}, "Save");
        return choice == 1 || (choice == 0 && save(false));
    }
}
