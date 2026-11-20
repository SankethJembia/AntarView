package org.liverreview;

import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import org.liverreview.Mesh.Axis;
import org.liverreview.Mesh.Bounds;
import org.liverreview.Mesh.Point;
import vtk.vtkFloatArray;
import vtk.vtkImageReslice;
import vtk.vtkMatrix4x4;

final class MriSlices implements AutoCloseable {
    private static final int MAX_PIXELS = 448;
    private static final int VTK_FLOAT = 10;

    private final Bounds bounds;
    private final double sampling;
    private final double low;
    private final double high;
    private final boolean invertGrayscale;
    private final SpatialTransform rasToNative;
    private final vtkImageReslice reslice = new vtkImageReslice();
    private final vtkMatrix4x4 resliceAxes = new vtkMatrix4x4();
    private final MeshSection reference = new MeshSection();
    private final MeshSection generated;

    MriSlices(MriCase mriCase) {
        var mri = mriCase.mri();
        bounds = mri.bounds();
        var nativeBounds = Bounds.of(mri.data().GetBounds());
        int[] size = mri.data().GetDimensions();
        double smallest = Double.POSITIVE_INFINITY;
        for (Axis axis : Axis.values()) {
            double norm = Math.hypot(Math.hypot(mri.nativeToRas().axisComponent(0, axis),
                    mri.nativeToRas().axisComponent(1, axis)), mri.nativeToRas().axisComponent(2, axis));
            smallest = Math.min(smallest, norm * nativeBounds.size(axis) / (size[axis.ordinal()] - 1));
        }
        sampling = smallest;
        double[] intensity = mri.data().GetScalarRange();
        low = intensity[0];
        high = intensity[1] > low ? intensity[1] : low + 1;
        invertGrayscale = mri.slope() < 0;
        rasToNative = mri.nativeToRas().inverse();
        reslice.SetInputData(mri.data());
        reslice.SetOutputDimensionality(2);
        reslice.SetOutputOrigin(0, 0, 0);
        reslice.SetOutputScalarType(VTK_FLOAT);
        reslice.SetInterpolationModeToLinear();
        reslice.SetBackgroundLevel(invertGrayscale ? high : low);
        reslice.SetResliceAxes(resliceAxes);
        reference.setMesh(mriCase.reference());
        generated = mriCase.hasMeshAlignment() ? new MeshSection() : null;
        if (generated != null) {
            generated.setMesh(mriCase.alignedMesh());
        }
    }

    Slice imageAt(Axis normal, double location) {
        Axis horizontal = normal.horizontal();
        Axis vertical = normal.vertical();
        double[] start = bounds.min().coordinates();
        start[normal.ordinal()] = location;
        var origin = rasToNative.apply(Point.of(start)).coordinates();
        for (int row = 0; row < 3; row++) {
            resliceAxes.SetElement(row, 0, rasToNative.axisComponent(row, horizontal));
            resliceAxes.SetElement(row, 1, rasToNative.axisComponent(row, vertical));
            resliceAxes.SetElement(row, 2, rasToNative.axisComponent(row, normal));
            resliceAxes.SetElement(row, 3, origin[row]);
        }
        int width = pixels(horizontal);
        int height = pixels(vertical);
        reslice.SetOutputSpacing(bounds.size(horizontal) / (width - 1), bounds.size(vertical) / (height - 1), 1);
        reslice.SetOutputExtent(0, width - 1, 0, height - 1, 0, 0);
        reslice.Update();

        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        float[] voxels = ((vtkFloatArray) reslice.GetOutput().GetPointData().GetScalars()).GetJavaArray();
        for (int row = 0; row < height; row++) {
            int sourceRow = (height - 1 - row) * width;
            for (int column = 0; column < width; column++) {
                double normalized = (voxels[sourceRow + column] - low) / (high - low);
                if (invertGrayscale) {
                    normalized = 1 - normalized;
                }
                int grey = (int) Math.round(255 * Math.clamp(normalized, 0, 1));
                pixels[row * width + column] = (grey << 16) | (grey << 8) | grey;
            }
        }

        reference.update(normal, location, true);
        Path2D.Double meshContour = null;
        if (generated != null) {
            generated.update(normal, location, true);
            meshContour = generated.contour(normal);
        }
        return new Slice(image, reference.contour(normal), meshContour);
    }

    private int pixels(Axis axis) {
        return (int) Math.clamp(Math.ceil(bounds.size(axis) / sampling) + 1, 2, MAX_PIXELS);
    }

    @Override
    public void close() {
        if (generated != null) {
            generated.close();
        }
        reference.close();
        reslice.Delete();
        resliceAxes.Delete();
    }

    record Slice(BufferedImage image, Path2D.Double mask, Path2D.Double mesh) {
    }
}
