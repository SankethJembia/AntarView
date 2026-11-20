package org.liverreview;

import java.awt.geom.Path2D;
import org.liverreview.Mesh.Axis;
import vtk.vtkCutter;
import vtk.vtkIdList;
import vtk.vtkPlane;

final class MeshSection {
    private final vtkPlane plane = new vtkPlane();
    private final vtkCutter cutter = new vtkCutter();
    private final vtkIdList line = new vtkIdList();

    MeshSection() {
        cutter.SetCutFunction(plane);
    }

    void setMesh(Mesh mesh) {
        cutter.SetInputData(mesh.data());
    }

    void update(Axis axis, double location, boolean keepHighSide) {
        double[] origin = new double[3];
        origin[axis.ordinal()] = location;
        plane.SetOrigin(origin);
        plane.SetNormal(axis.planeNormal(keepHighSide));
        cutter.Update();
    }

    vtkPlane plane() {
        return plane;
    }

    vtkCutter cutter() {
        return cutter;
    }

    Path2D.Double contour(Axis axis) {
        int horizontal = axis.horizontal().ordinal();
        int vertical = axis.vertical().ordinal();
        var path = new Path2D.Double();
        var output = cutter.GetOutput();
        var lines = output.GetLines();
        lines.InitTraversal();
        while (lines.GetNextCell(line) != 0) {
            if (line.GetNumberOfIds() == 0) {
                continue;
            }
            double[] start = output.GetPoint(line.GetId(0));
            path.moveTo(start[horizontal], start[vertical]);
            for (long index = 1; index < line.GetNumberOfIds(); index++) {
                double[] point = output.GetPoint(line.GetId(index));
                path.lineTo(point[horizontal], point[vertical]);
            }
        }
        return path;
    }
}
