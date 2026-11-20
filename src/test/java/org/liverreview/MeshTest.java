package org.liverreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.jhuapl.ses.vtkjavanativelibs.VtkNativeLibraries;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.liverreview.Mesh.Axis;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import vtk.vtkActor;
import vtk.vtkCellPicker;
import vtk.vtkClipPolyData;
import vtk.vtkPolyDataMapper;
import vtk.vtkRenderer;
import vtk.vtkRenderWindow;

class MeshTest {
    private static final double EPSILON = 1e-6;

    @TempDir
    Path temporary;

    @BeforeAll
    static void initializeVtk() throws Exception {
        VtkNativeLibraries.initialize();
    }

    @Test
    void opensObjAndMeasuresInOriginalCoordinates() throws Exception {
        try (Mesh mesh = block()) {
            assertEquals(40, mesh.bounds().size(Axis.X), EPSILON);
            assertEquals(30, mesh.bounds().size(Axis.Y), EPSILON);
            assertEquals(20, mesh.bounds().size(Axis.Z), EPSILON);
            assertEquals(50, mesh.bounds().min().distanceTo(new Mesh.Point(50, 50, 30)), EPSILON);
        }
    }

    @ParameterizedTest
    @EnumSource(Axis.class)
    void cutsBlockOnEachAxis(Axis axis) throws Exception {
        try (Mesh mesh = block()) {
            var section = new MeshSection();
            section.setMesh(mesh);
            double location = mesh.bounds().location(axis, 0.25);
            section.update(axis, location, true);
            var contour = section.contour(axis).getBounds2D();
            assertEquals(mesh.bounds().min().along(axis.horizontal()), contour.getMinX(), EPSILON);
            assertEquals(mesh.bounds().min().along(axis.vertical()), contour.getMinY(), EPSILON);
            assertEquals(mesh.bounds().size(axis.horizontal()), contour.getWidth(), EPSILON);
            assertEquals(mesh.bounds().size(axis.vertical()), contour.getHeight(), EPSILON);

            var clip = new vtkClipPolyData();
            try {
                clip.SetInputData(mesh.data());
                clip.SetClipFunction(section.plane());
                clip.Update();
                assertEquals(location, Mesh.Bounds.of(clip.GetOutput().GetBounds()).min().along(axis), EPSILON);
                section.update(axis, location, false);
                clip.Update();
                assertEquals(location, Mesh.Bounds.of(clip.GetOutput().GetBounds()).max().along(axis), EPSILON);
                section.update(axis, mesh.bounds().max().along(axis) + 1, true);
                assertTrue(section.contour(axis).getBounds2D().isEmpty());
            } finally {
                clip.Delete();
            }
        }
    }

    @Test
    void opensAsciiAndBinaryStl() throws IOException {
        Path text = temporary.resolve("ascii triangle.stl");
        Files.writeString(text, """
                solid triangle
                  facet normal 0 0 1
                    outer loop
                      vertex 0 0 0
                      vertex 4 0 0
                      vertex 0 3 0
                    endloop
                  endfacet
                endsolid triangle
                """);
        Path binary = temporary.resolve("binary triangle.STL");
        ByteBuffer bytes = ByteBuffer.allocate(134).order(ByteOrder.LITTLE_ENDIAN);
        bytes.position(80);
        bytes.putInt(1);
        for (float value : new float[]{0, 0, 1, 0, 0, 0, 4, 0, 0, 0, 3, 0}) {
            bytes.putFloat(value);
        }
        bytes.putShort((short) 0);
        Files.write(binary, bytes.array());
        try (Mesh asciiMesh = Mesh.open(text); Mesh binaryMesh = Mesh.open(binary)) {
            assertEquals(asciiMesh.bounds(), binaryMesh.bounds());
            assertEquals(4, binaryMesh.bounds().size(Axis.X), EPSILON);
        }
    }

    @Test
    void rejectsAnObjWithoutFaces() throws IOException {
        Path path = temporary.resolve("vertices.obj");
        Files.writeString(path, "v 10 20 30\nv 40 50 60\n");
        assertThrows(IOException.class, () -> Mesh.open(path));
    }

    @Test
    void pickingYieldsSurfaceCoordinatesAndRespectsClipping() throws Exception {
        try (Mesh mesh = block()) {
            var view = new vtkRenderWindow();
            var renderer = new vtkRenderer();
            var mapper = new vtkPolyDataMapper();
            var actor = new vtkActor();
            var picker = new vtkCellPicker();
            try {
                view.SetOffScreenRendering(1);
                view.SetSize(400, 300);
                view.AddRenderer(renderer);
                mapper.SetInputData(mesh.data());
                actor.SetMapper(mapper);
                renderer.AddActor(actor);
                var camera = renderer.GetActiveCamera();
                camera.SetPosition(30, 35, 150);
                camera.SetFocalPoint(30, 35, 40);
                camera.SetViewUp(0, 1, 0);
                camera.ParallelProjectionOn();
                camera.SetParallelScale(30);
                renderer.ResetCameraClippingRange();
                picker.PickFromListOn();
                picker.AddPickList(actor);
                picker.PickClippingPlanesOff();

                Mesh.Point first = pickAt(renderer, picker, 20, 25, 50);
                Mesh.Point second = pickAt(renderer, picker, 38, 49, 50);
                assertEquals(50, first.z(), EPSILON);
                assertEquals(30, first.distanceTo(second), EPSILON);

                var section = new MeshSection();
                section.setMesh(mesh);
                section.update(Axis.Z, 40, false);
                mapper.AddClippingPlane(section.plane());
                assertEquals(30, pickAt(renderer, picker, 20, 25, 50).z(), EPSILON);
            } finally {
                picker.Delete();
                actor.Delete();
                mapper.Delete();
                renderer.Delete();
                view.Delete();
            }
        }
    }

    private Mesh.Point pickAt(vtkRenderer renderer, vtkCellPicker picker, double x, double y, double z) {
        renderer.SetWorldPoint(x, y, z, 1);
        renderer.WorldToDisplay();
        double[] display = renderer.GetDisplayPoint();
        assertEquals(1, picker.Pick(display[0], display[1], 0, renderer));
        return Mesh.Point.of(picker.GetPickPosition());
    }

    private Mesh block() throws Exception {
        return Mesh.open(Path.of(getClass().getResource("/measurement-block.obj").toURI()));
    }
}
