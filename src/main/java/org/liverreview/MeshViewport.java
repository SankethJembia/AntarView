package org.liverreview;

import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import vtk.vtkActor;
import vtk.vtkCellPicker;
import vtk.vtkLineSource;
import vtk.vtkPanel;
import vtk.vtkPolyDataMapper;
import vtk.vtkSphereSource;

final class MeshViewport extends vtkPanel {
    private final vtkPolyDataMapper surfaceMapper = new vtkPolyDataMapper();
    private final vtkActor surface = new vtkActor();
    private final vtkActor outline;
    private final vtkCellPicker picker = new vtkCellPicker();
    private final vtkSphereSource firstMarker = new vtkSphereSource();
    private final vtkSphereSource secondMarker = new vtkSphereSource();
    private final vtkLineSource ruler = new vtkLineSource();
    private final vtkActor firstActor;
    private final vtkActor secondActor;
    private final vtkActor rulerActor;

    MeshViewport(MeshSection section, BooleanSupplier isMeasuring, Consumer<Mesh.Point> onPick) {
        GetRenderer().SetBackground(0.060, 0.092, 0.132);
        GetRenderer().SetBackground2(0.120, 0.170, 0.222);
        GetRenderer().GradientBackgroundOn();
        surface.SetMapper(surfaceMapper);
        surface.GetProperty().SetColor(0.40, 0.74, 0.75);
        surface.GetProperty().SetAmbient(0.30);
        surface.GetProperty().SetDiffuse(0.70);
        GetRenderer().AddActor(surface);

        outline = addOverlay(section.cutter(), 0.97, 0.80, 0.44);
        outline.GetProperty().SetLineWidth(3.0f);
        firstActor = addOverlay(firstMarker, 1.0, 0.90, 0.58);
        secondActor = addOverlay(secondMarker, 1.0, 0.90, 0.58);
        rulerActor = addOverlay(ruler, 1.0, 0.90, 0.58);
        rulerActor.GetProperty().SetLineWidth(3.0f);

        picker.PickFromListOn();
        picker.AddPickList(surface);
        picker.PickClippingPlanesOff();

        addMouseListener(new MouseAdapter() {
            private Point pressedAt;

            @Override
            public void mousePressed(MouseEvent event) {
                pressedAt = event.getPoint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (isMeasuring.getAsBoolean() && SwingUtilities.isLeftMouseButton(event) && pressedAt != null
                        && pressedAt.distance(event.getPoint()) <= 3) {
                    pick(event, onPick);
                }
                pressedAt = null;
            }
        });
    }

    private vtkActor addOverlay(vtk.vtkAlgorithm source, double red, double green, double blue) {
        var mapper = new vtkPolyDataMapper();
        mapper.SetInputConnection(source.GetOutputPort());
        mapper.ScalarVisibilityOff();
        var actor = new vtkActor();
        actor.SetMapper(mapper);
        actor.GetProperty().SetColor(red, green, blue);
        actor.GetProperty().LightingOff();
        actor.PickableOff();
        actor.VisibilityOff();
        GetRenderer().AddActor(actor);
        return actor;
    }

    void setMesh(Mesh mesh) {
        surfaceMapper.SetInputData(mesh.data());
        surfaceMapper.ScalarVisibilityOff();
        firstMarker.SetRadius(mesh.bounds().diagonal() * 0.008);
        secondMarker.SetRadius(mesh.bounds().diagonal() * 0.008);
        setPicks(null, null);
        resetCameraView();
    }

    void setClipping(MeshSection section, boolean enabled) {
        surfaceMapper.RemoveAllClippingPlanes();
        if (enabled) {
            surfaceMapper.AddClippingPlane(section.plane());
        }
        outline.SetVisibility(enabled ? 1 : 0);
    }

    void setPicks(Mesh.Point first, Mesh.Point second) {
        firstActor.SetVisibility(first == null ? 0 : 1);
        secondActor.SetVisibility(second == null ? 0 : 1);
        rulerActor.SetVisibility(second == null ? 0 : 1);
        if (first != null) {
            firstMarker.SetCenter(first.coordinates());
        }
        if (second != null) {
            secondMarker.SetCenter(second.coordinates());
            ruler.SetPoint1(first.coordinates());
            ruler.SetPoint2(second.coordinates());
        }
    }

    void resetCameraView() {
        var camera = GetRenderer().GetActiveCamera();
        camera.SetPosition(1.25, -1.6, 1.15);
        camera.SetFocalPoint(0, 0, 0);
        camera.SetViewUp(0, 0, 1);
        GetRenderer().ResetCamera();
        GetRenderer().ResetCameraClippingRange();
    }

    private void pick(MouseEvent event, Consumer<Mesh.Point> onPick) {
        int[] size = GetRenderWindow().GetSize();
        if (getWidth() == 0 || getHeight() == 0 || size[0] <= 0 || size[1] <= 0) {
            return;
        }
        double x = event.getX() * (double) size[0] / getWidth();
        double y = size[1] - 1 - event.getY() * (double) size[1] / getHeight();
        if (picker.Pick(x, y, 0, GetRenderer()) != 0 && picker.GetCellId() >= 0) {
            onPick.accept(Mesh.Point.of(picker.GetPickPosition()));
        }
    }
}
