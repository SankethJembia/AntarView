package org.liverreview;

import java.io.IOException;
import java.util.Arrays;
import vtk.vtkDataArray;
import vtk.vtkDistancePolyDataFilter;
import vtk.vtkTriangleFilter;

record SurfaceDeviation(Summary meshToMask, Summary maskToMesh) {
    record Summary(double median, double p95) {
    }

    static SurfaceDeviation measure(Mesh mesh, Mesh reference) throws IOException {
        var generatedTriangles = new vtkTriangleFilter();
        var maskTriangles = new vtkTriangleFilter();
        var distances = new vtkDistancePolyDataFilter();
        try {
            generatedTriangles.SetInputData(mesh.data());
            maskTriangles.SetInputData(reference.data());
            distances.SetInputConnection(0, generatedTriangles.GetOutputPort());
            distances.SetInputConnection(1, maskTriangles.GetOutputPort());
            distances.SetSignedDistance(0);
            distances.SetComputeSecondDistance(1);
            distances.SetComputeCellCenterDistance(0);
            distances.Update();
            return new SurfaceDeviation(
                    summarize(distances.GetOutput().GetPointData().GetArray("Distance")),
                    summarize(distances.GetSecondDistanceOutput().GetPointData().GetArray("Distance")));
        } finally {
            distances.Delete();
            maskTriangles.Delete();
            generatedTriangles.Delete();
        }
    }

    private static Summary summarize(vtkDataArray array) throws IOException {
        if (array == null || array.GetNumberOfTuples() == 0) {
            throw new IOException("Could not compare the two surfaces.");
        }
        var values = new double[Math.toIntExact(array.GetNumberOfTuples())];
        for (int index = 0; index < values.length; index++) {
            values[index] = array.GetTuple1(index);
            if (!Double.isFinite(values[index])) {
                throw new IOException("The surface comparison produced a non-finite distance.");
            }
        }
        Arrays.sort(values);
        return new Summary(percentile(values, 0.5), percentile(values, 0.95));
    }

    private static double percentile(double[] values, double fraction) {
        double rank = fraction * (values.length - 1);
        int lower = (int) rank;
        int upper = (int) Math.ceil(rank);
        return values[lower] + (values[upper] - values[lower]) * (rank - lower);
    }
}
