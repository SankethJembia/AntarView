package org.liverreview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import vtk.vtkOBJReader;
import vtk.vtkPolyData;
import vtk.vtkPolyDataAlgorithm;
import vtk.vtkSTLReader;

record Mesh(vtkPolyData data, Bounds bounds) implements AutoCloseable {
    static Mesh open(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("The file could not be read.");
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String filename = path.toAbsolutePath().toString();
        vtkPolyDataAlgorithm reader;
        if (name.endsWith(".stl")) {
            var stl = new vtkSTLReader();
            stl.SetFileName(filename);
            reader = stl;
        } else if (name.endsWith(".obj")) {
            var obj = new vtkOBJReader();
            obj.SetFileName(filename);
            reader = obj;
        } else {
            throw new IOException("Choose an STL or OBJ mesh.");
        }

        try {
            reader.Update();
            vtkPolyData output = reader.GetOutput();
            if (reader.GetErrorCode() != 0 || output.GetNumberOfPolys() == 0) {
                throw new IOException("No surface faces could be read from this file.");
            }
            Bounds bounds = Bounds.of(output.GetBounds());
            if (!Double.isFinite(bounds.diagonal()) || bounds.diagonal() <= 0) {
                throw new IOException("The mesh has invalid or empty bounds.");
            }
            var data = new vtkPolyData();
            data.ShallowCopy(output);
            return new Mesh(data, bounds);
        } finally {
            reader.Delete();
        }
    }

    @Override
    public void close() {
        data.Delete();
    }

    enum Axis {
        X, Y, Z;

        Axis horizontal() {
            return this == X ? Y : X;
        }

        Axis vertical() {
            return this == Z ? Y : Z;
        }

        double[] planeNormal(boolean keepHighSide) {
            double[] normal = new double[3];
            normal[ordinal()] = keepHighSide ? 1 : -1;
            return normal;
        }
    }

    record Point(double x, double y, double z) {
        static Point of(double[] coordinates) {
            return new Point(coordinates[0], coordinates[1], coordinates[2]);
        }

        double along(Axis axis) {
            return switch (axis) {
                case X -> x;
                case Y -> y;
                case Z -> z;
            };
        }

        double[] coordinates() {
            return new double[]{x, y, z};
        }

        double distanceTo(Point other) {
            return Math.hypot(Math.hypot(x - other.x, y - other.y), z - other.z);
        }
    }

    record Bounds(Point min, Point max) {
        static Bounds of(double[] vtkBounds) {
            return new Bounds(
                    new Point(vtkBounds[0], vtkBounds[2], vtkBounds[4]),
                    new Point(vtkBounds[1], vtkBounds[3], vtkBounds[5]));
        }

        double size(Axis axis) {
            return max.along(axis) - min.along(axis);
        }

        double location(Axis axis, double fraction) {
            return min.along(axis) + fraction * size(axis);
        }

        double diagonal() {
            return min.distanceTo(max);
        }

        boolean contains(Point point) {
            for (Axis axis : Axis.values()) {
                double value = point.along(axis);
                if (value < min.along(axis) || value > max.along(axis)) {
                    return false;
                }
            }
            return true;
        }
    }
}
