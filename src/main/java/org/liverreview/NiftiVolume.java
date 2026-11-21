package org.liverreview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.liverreview.Mesh.Axis;
import org.liverreview.Mesh.Bounds;
import vtk.vtkImageData;
import vtk.vtkNIFTIImageReader;

record NiftiVolume(vtkImageData data, SpatialTransform nativeToRas, int spaceCode,
                   double slope, double intercept, boolean reversedK) implements AutoCloseable {
    static NiftiVolume open(Path path, boolean assumeMillimetres) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".nii") && !name.endsWith(".nii.gz")) {
            throw new IOException("Choose a NIfTI .nii or .nii.gz file.");
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("Could not read " + path.getFileName() + ".");
        }

        var reader = new vtkNIFTIImageReader();
        try {
            String filename = path.toAbsolutePath().toString();
            if (reader.CanReadFile(filename) == 0) {
                throw new IOException(path.getFileName() + " is not a readable NIfTI volume.");
            }
            reader.SetFileName(filename);
            reader.UpdateInformation();
            if (reader.GetTimeDimension() > 1) {
                throw new IOException("Choose a single 3D volume from " + path.getFileName() + ".");
            }
            reader.Update();
            var output = reader.GetOutput();
            if (reader.GetErrorCode() != 0 || output.GetNumberOfScalarComponents() != 1
                    || Arrays.stream(output.GetDimensions()).anyMatch(size -> size < 2)
                    || output.GetPointData().GetScalars() == null) {
                throw new IOException(path.getFileName() + " must be a readable, scalar 3D volume.");
            }
            var header = reader.GetNIFTIHeader();
            boolean hasSform = reader.GetSFormMatrix() != null;
            var orientation = hasSform ? reader.GetSFormMatrix() : reader.GetQFormMatrix();
            if (orientation == null) {
                throw new IOException(path.getFileName() + " has no coded sform or qform for alignment.");
            }
            double units = millimetres(header.GetXYZTUnits() & 7, assumeMillimetres, path);
            // VTK removes pixel spacing from these matrices and accounts for any reversal of slice order.
            var geometry = SpatialTransform.fromNifti(orientation, units);
            var nativeBounds = Bounds.of(output.GetBounds());
            var worldBounds = geometry.apply(nativeBounds);
            for (Axis axis : Axis.values()) {
                if (!Double.isFinite(worldBounds.size(axis)) || worldBounds.size(axis) <= 0
                        || !Double.isFinite(nativeBounds.size(axis)) || nativeBounds.size(axis) <= 0) {
                    throw new IOException(path.getFileName() + " has invalid spatial bounds.");
                }
            }
            double scale = reader.GetRescaleSlope();
            double offset = scale == 0 ? 0 : reader.GetRescaleIntercept();
            if (!Double.isFinite(scale) || !Double.isFinite(offset)) {
                throw new IOException(path.getFileName() + " has invalid intensity scaling.");
            }
            var image = new vtkImageData();
            image.ShallowCopy(output);
            return new NiftiVolume(image, geometry, hasSform ? header.GetSFormCode() : header.GetQFormCode(),
                    scale == 0 ? 1 : scale, offset, reader.GetQFac() < 0);
        } finally {
            reader.Delete();
        }
    }

    private static double millimetres(int code, boolean assumeMillimetres, Path path) throws IOException {
        return switch (code) {
            case 1 -> 1000;
            case 2 -> 1;
            case 3 -> 0.001;
            case 0 -> {
                if (!assumeMillimetres) {
                    throw new IOException(path.getFileName() + " has unspecified spatial units. "
                            + "Confirm millimetres in the case dialog only if the export uses them.");
                }
                yield 1;
            }
            default -> throw new IOException(path.getFileName() + " uses unsupported spatial units.");
        };
    }

    Bounds bounds() {
        return nativeToRas.apply(Bounds.of(data.GetBounds()));
    }

    int mapSliceOrder(int k) {
        return reversedK ? data.GetDimensions()[2] - 1 - k : k;
    }

    double voxelStepMm(Axis axis) {
        double norm = Math.hypot(Math.hypot(nativeToRas.axisComponent(0, axis), nativeToRas.axisComponent(1, axis)),
                nativeToRas.axisComponent(2, axis));
        return norm * Math.abs(data.GetSpacing()[axis.ordinal()]);
    }

    @Override
    public void close() {
        data.Delete();
    }
}
