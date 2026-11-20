package org.liverreview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.liverreview.Mesh.Axis;
import org.liverreview.Mesh.Bounds;
import org.liverreview.Mesh.Point;
import vtk.vtkMatrix4x4;

final class SpatialTransform {
    private final double[] values;

    private SpatialTransform(double[] values) {
        this.values = values;
    }

    static SpatialTransform identity() {
        return new SpatialTransform(new double[]{
                1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
    }

    static SpatialTransform read(Path path) throws IOException {
        String[] tokens = Files.readString(path).strip().split("\\s+");
        if (tokens.length != 16) {
            throw new IOException("The mesh transform must contain 16 numbers in row order.");
        }
        var data = new double[16];
        try {
            for (int index = 0; index < data.length; index++) {
                data[index] = Double.parseDouble(tokens[index]);
            }
        } catch (NumberFormatException error) {
            throw new IOException("The mesh transform contains an invalid number.", error);
        }
        return validated(data);
    }

    static SpatialTransform fromNifti(vtkMatrix4x4 nativeToRas, double mmPerUnit) throws IOException {
        var data = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                data[row * 4 + column] = nativeToRas.GetElement(row, column) * (row < 3 ? mmPerUnit : 1);
            }
        }
        return validated(data);
    }

    private static SpatialTransform validated(double[] data) throws IOException {
        for (double value : data) {
            if (!Double.isFinite(value)) {
                throw new IOException("The spatial transform contains non-finite values.");
            }
        }
        if (data[12] != 0 || data[13] != 0 || data[14] != 0 || data[15] != 1) {
            throw new IOException("The spatial transform must be affine with a final row of 0 0 0 1.");
        }
        var transform = new SpatialTransform(data);
        var matrix = transform.matrix();
        try {
            double volume = 1;
            for (int index = 0; index < 3; index++) {
                volume *= Math.hypot(Math.hypot(data[index], data[index + 4]), data[index + 8]);
            }
            double relativeDeterminant = Math.abs(matrix.Determinant()) / volume;
            if (!Double.isFinite(relativeDeterminant) || relativeDeterminant < 1e-10) {
                throw new IOException("The spatial transform cannot be inverted reliably.");
            }
            return transform;
        } finally {
            matrix.Delete();
        }
    }

    vtkMatrix4x4 matrix() {
        var matrix = new vtkMatrix4x4();
        matrix.DeepCopy(values);
        return matrix;
    }

    SpatialTransform inverse() {
        var matrix = matrix();
        try {
            var inverted = new double[16];
            matrix.Invert(values, inverted);
            return new SpatialTransform(inverted);
        } finally {
            matrix.Delete();
        }
    }

    Point apply(Point point) {
        var result = new double[3];
        for (int row = 0; row < result.length; row++) {
            int start = row * 4;
            result[row] = values[start] * point.x() + values[start + 1] * point.y()
                    + values[start + 2] * point.z() + values[start + 3];
        }
        return Point.of(result);
    }

    double axisComponent(int row, Axis axis) {
        return values[row * 4 + axis.ordinal()];
    }

    Bounds apply(Bounds bounds) {
        var min = new double[]{Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        var max = new double[]{Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (int corner = 0; corner < 8; corner++) {
            Point point = apply(new Point(
                    (corner & 1) == 0 ? bounds.min().x() : bounds.max().x(),
                    (corner & 2) == 0 ? bounds.min().y() : bounds.max().y(),
                    (corner & 4) == 0 ? bounds.min().z() : bounds.max().z()));
            for (Axis axis : Axis.values()) {
                int index = axis.ordinal();
                min[index] = Math.min(min[index], point.along(axis));
                max[index] = Math.max(max[index], point.along(axis));
            }
        }
        return new Bounds(Point.of(min), Point.of(max));
    }
}
