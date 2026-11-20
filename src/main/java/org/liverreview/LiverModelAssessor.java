package org.liverreview;

import edu.jhuapl.ses.vtkjavanativelibs.VtkNativeLibraries;
import java.awt.Font;
import java.nio.file.Path;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;

public final class LiverModelAssessor {
    private LiverModelAssessor() {
    }

    public static void main(String[] args) {
        try {
            VtkNativeLibraries.initialize();
        } catch (Exception | LinkageError error) {
            error.printStackTrace();
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
                    "Could not load the VTK rendering libraries.\n" + error.getMessage(),
                    "Liver Model Assessor", JOptionPane.ERROR_MESSAGE));
            return;
        }

        SwingUtilities.invokeLater(() -> {
            UIManager.put("swing.boldMetal", Boolean.FALSE);
            var font = new FontUIResource("Segoe UI", Font.PLAIN, 14);
            for (Object key : UIManager.getDefaults().keySet().toArray()) {
                if (UIManager.get(key) instanceof FontUIResource) {
                    UIManager.put(key, font);
                }
            }
            var window = new AssessorWindow();
            window.setVisible(true);
            if (args.length > 0) {
                window.openMesh(Path.of(args[0]));
            }
        });
    }
}
