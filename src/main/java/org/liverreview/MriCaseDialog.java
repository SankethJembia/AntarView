package org.liverreview;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.nio.file.Path;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

final class MriCaseDialog {
    private static final FileNameExtensionFilter NIFTI = new FileNameExtensionFilter("NIfTI (*.nii, *.nii.gz)",
            "nii", "gz");

    private MriCaseDialog() {
    }

    static Inputs choose(Component parent, Path currentMesh) {
        var form = new JPanel(new GridBagLayout());
        var mesh = new FileField("STL or OBJ mesh",
                new FileNameExtensionFilter("Surface meshes (*.stl, *.obj)", "stl", "obj"), currentMesh);
        var mri = new FileField("Source MRI NIfTI", NIFTI, null);
        var mask = new FileField("Liver mask NIfTI", NIFTI, null);
        var transform = new FileField("4 × 4 matrix",
                new FileNameExtensionFilter("Text matrix (*.txt)", "txt"), null);
        var mode = new JComboBox<>(new String[]{
                "Unknown mesh coordinates", "Mesh already in MRI RAS+ mm", "Use a mesh-to-RAS+ matrix"});
        Row r = new Row(form);
        r.add("Mesh", mesh.panel);
        r.add("MRI", mri.panel);
        r.add("Liver mask", mask.panel);
        r.add("Mesh alignment", mode);
        r.add("Transform", transform.panel);
        var assumeMm = new JCheckBox("Use mm if NIfTI spatial units are unspecified");
        r.add("", assumeMm);
        r.add("", new JLabel("Use the MRI and liver mask from the same case."));
        transform.setEnabled(false);
        mode.addActionListener(event -> transform.setEnabled(mode.getSelectedIndex() == 2));

        while (JOptionPane.showConfirmDialog(parent, form, "Open MRI case",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            int index = mode.getSelectedIndex();
            if (mesh.path != null && mri.path != null && mask.path != null && (index != 2 || transform.path != null)) {
                return new Inputs(mesh.path, mri.path, mask.path, index == 1,
                        index == 2 ? transform.path : null, assumeMm.isSelected());
            }
            JOptionPane.showMessageDialog(parent, "Choose the mesh, MRI, liver mask, and a matrix if selected.",
                    "Missing files", JOptionPane.WARNING_MESSAGE);
        }
        return null;
    }

    record Inputs(Path mesh, Path mri, Path mask, boolean meshAlreadyInRas, Path matrix, boolean assumeMm) {
        SpatialTransform meshToRas() throws java.io.IOException {
            if (meshAlreadyInRas) {
                return SpatialTransform.identity();
            }
            return matrix == null ? null : SpatialTransform.read(matrix);
        }
    }

    private static final class Row {
        private final JPanel form;
        private int next;

        Row(JPanel form) {
            this.form = form;
        }

        void add(String title, Component value) {
            var constraints = new GridBagConstraints();
            constraints.gridy = next++;
            constraints.anchor = GridBagConstraints.WEST;
            constraints.insets = new Insets(4, 4, 4, 6);
            form.add(new JLabel(title), constraints);
            constraints.gridx = 1;
            constraints.weightx = 1;
            constraints.fill = GridBagConstraints.HORIZONTAL;
            form.add(value, constraints);
        }
    }

    private static final class FileField {
        private final JPanel panel = new JPanel(new BorderLayout(6, 0));
        private final JTextField field = new JTextField(26);
        private final JButton browse = new JButton("Browse…");
        private Path path;

        FileField(String title, FileNameExtensionFilter filter, Path initial) {
            field.setEditable(false);
            field.setMinimumSize(new Dimension(300, field.getPreferredSize().height));
            field.getAccessibleContext().setAccessibleName(title);
            browse.getAccessibleContext().setAccessibleName("Browse for " + title);
            panel.add(field, BorderLayout.CENTER);
            panel.add(browse, BorderLayout.EAST);
            setPath(initial);
            browse.addActionListener(event -> {
                var chooser = new JFileChooser();
                chooser.setDialogTitle("Choose " + title);
                chooser.setFileFilter(filter);
                chooser.setAcceptAllFileFilterUsed(false);
                if (path != null) {
                    chooser.setSelectedFile(path.toFile());
                }
                if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
                    setPath(chooser.getSelectedFile().toPath());
                }
            });
        }

        private void setPath(Path path) {
            this.path = path;
            String value = path == null ? "" : path.toString();
            field.setText(value);
            field.setToolTipText(value);
        }

        void setEnabled(boolean enabled) {
            field.setEnabled(enabled);
            browse.setEnabled(enabled);
        }
    }
}
