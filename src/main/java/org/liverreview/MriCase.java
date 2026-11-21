package org.liverreview;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.liverreview.Mesh.Axis;
import org.liverreview.Mesh.Bounds;
import org.liverreview.Mesh.Point;
import vtk.vtkFlyingEdges3D;
import vtk.vtkImageConstantPad;
import vtk.vtkImageThreshold;
import vtk.vtkPolyData;
import vtk.vtkTransform;
import vtk.vtkTransformPolyDataFilter;

record MriCase(NiftiVolume mri, MriIdentity identity, Mesh reference, Mesh alignedMesh, Mesh referenceInModel,
               SpatialTransform meshToRas, SpatialTransform rasToMesh) implements AutoCloseable {
    static MriCase open(Path mriPath, Path maskPath, boolean assumeMillimetres,
                        Mesh generated, SpatialTransform meshToRas) throws IOException {
        if (Files.isSameFile(mriPath, maskPath)) {
            throw new IOException("Select the MRI and its separate liver mask.");
        }
        var attributes = Files.readAttributes(mriPath, BasicFileAttributes.class);
        var mri = NiftiVolume.open(mriPath, assumeMillimetres);
        Mesh reference = null;
        Mesh aligned = null;
        Mesh referenceInModel = null;
        try (var mask = NiftiVolume.open(maskPath, assumeMillimetres)) {
            if (mri.spaceCode() != mask.spaceCode()) {
                throw new IOException("The MRI and liver mask declare different NIfTI spatial frames.");
            }
            reference = maskSurface(mask);
            if (!overlaps(mri.bounds(), reference.bounds())) {
                throw new IOException("The liver mask is outside the MRI in physical space.");
            }
            var identity = MriIdentity.read(mriPath, mri, attributes);
            if (meshToRas == null) {
                return new MriCase(mri, identity, reference, null, null, null, null);
            }
            aligned = transform(generated.data(), meshToRas);
            if (!overlaps(reference.bounds(), aligned.bounds())) {
                throw new IOException("The transformed mesh and liver mask do not overlap. "
                        + "Check the transform or open the MRI and mask without the mesh overlay.");
            }
            var inverse = meshToRas.inverse();
            referenceInModel = transform(reference.data(), inverse);
            return new MriCase(mri, identity, reference, aligned, referenceInModel, meshToRas, inverse);
        } catch (IOException | RuntimeException error) {
            closeMesh(referenceInModel);
            closeMesh(aligned);
            closeMesh(reference);
            mri.close();
            throw error;
        }
    }

    private static boolean overlaps(Bounds first, Bounds second) {
        for (Axis axis : Axis.values()) {
            double lower = Math.max(first.min().along(axis), second.min().along(axis));
            double upper = Math.min(first.max().along(axis), second.max().along(axis));
            if (!(lower < upper)) {
                return false;
            }
        }
        return true;
    }

    private static Mesh maskSurface(NiftiVolume mask) throws IOException {
        var range = mask.data().GetScalarRange();
        if (!Double.isFinite(range[0]) || !Double.isFinite(range[1]) || mask.slope() <= 0
                || mask.intercept() != 0 || range[0] < 0 || range[1] <= 0) {
            throw new IOException("The liver mask must use zero for background and positive foreground values.");
        }
        var binary = new vtkImageThreshold();
        var pad = new vtkImageConstantPad();
        var surface = new vtkFlyingEdges3D();
        try {
            binary.SetInputData(mask.data());
            binary.ThresholdByLower(0);
            binary.ReplaceInOn();
            binary.SetInValue(0);
            binary.ReplaceOutOn();
            binary.SetOutValue(1);
            binary.SetOutputScalarTypeToUnsignedChar();
            int[] extent = mask.data().GetExtent();
            pad.SetInputConnection(binary.GetOutputPort());
            pad.SetOutputWholeExtent(extent[0] - 1, extent[1] + 1, extent[2] - 1, extent[3] + 1,
                    extent[4] - 1, extent[5] + 1);
            pad.SetConstant(0);
            surface.SetInputConnection(pad.GetOutputPort());
            surface.SetValue(0, 0.5);
            surface.ComputeNormalsOff();
            surface.ComputeScalarsOff();
            surface.Update();
            if (surface.GetOutput().GetNumberOfPolys() == 0) {
                throw new IOException("The liver mask did not produce a surface.");
            }
            return transform(surface.GetOutput(), mask.nativeToRas());
        } finally {
            surface.Delete();
            pad.Delete();
            binary.Delete();
        }
    }

    private static Mesh transform(vtkPolyData input, SpatialTransform mapping) throws IOException {
        var matrix = mapping.matrix();
        var transform = new vtkTransform();
        var filter = new vtkTransformPolyDataFilter();
        try {
            transform.SetMatrix(matrix);
            filter.SetInputData(input);
            filter.SetTransform(transform);
            filter.Update();
            var output = filter.GetOutput();
            var bounds = Bounds.of(output.GetBounds());
            if (!Double.isFinite(bounds.diagonal()) || bounds.diagonal() <= 0) {
                throw new IOException("The transformed surface has invalid bounds.");
            }
            var data = new vtkPolyData();
            data.ShallowCopy(output);
            return new Mesh(data, bounds);
        } finally {
            filter.Delete();
            transform.Delete();
            matrix.Delete();
        }
    }

    Point initialCursor() {
        var bounds = mri.bounds();
        var liver = reference.bounds();
        var point = new double[3];
        for (Axis axis : Axis.values()) {
            point[axis.ordinal()] = Math.clamp(liver.location(axis, 0.5),
                    bounds.min().along(axis), bounds.max().along(axis));
        }
        return Point.of(point);
    }

    boolean hasMeshAlignment() {
        return alignedMesh != null;
    }

    @Override
    public void close() {
        closeMesh(referenceInModel);
        closeMesh(alignedMesh);
        reference.close();
        mri.close();
    }

    private static void closeMesh(Mesh mesh) {
        if (mesh != null) {
            mesh.close();
        }
    }
}
