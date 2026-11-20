package org.liverreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.jhuapl.ses.vtkjavanativelibs.VtkNativeLibraries;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.liverreview.Mesh.Axis;
import org.liverreview.Mesh.Point;

class MriCaseTest {
    private static final double EPSILON = 1e-5;

    @TempDir
    Path temporary;

    @BeforeAll
    static void initializeVtk() throws Exception {
        VtkNativeLibraries.initialize();
    }

    @Test
    void reslicesRotatedAndReversedNiftiInPhysicalCoordinates() throws Exception {
        Path mri = nifti("synthetic MRI.nii", false, 2, 1, 50);
        Path mask = nifti("synthetic mask.nii.gz", true, 2, 1, 50);
        try (var review = MriCase.open(mri, mask, false, null, null);
             var slices = new MriSlices(review)) {
            assertFalse(review.hasMeshAlignment());
            assertPoint(new Point(32, -30, 70), review.mri().bounds().min());
            assertPoint(new Point(50, -16, 90), review.mri().bounds().max());
            assertPoint(new Point(39.5, -27, 72), review.reference().bounds().min());
            assertPoint(new Point(48.5, -19, 84), review.reference().bounds().max());

            var axial = slices.imageAt(Axis.Z, 82);
            assertNull(axial.mesh());
            assertEquals(39.5, axial.mask().getBounds2D().getMinX(), EPSILON);
            assertEquals(-19, axial.mask().getBounds2D().getMaxY(), EPSILON);
            var image = axial.image();
            assertGray(82, image.getRGB(image.getWidth() - 1, 0));
            assertGray(141, image.getRGB(0, image.getHeight() - 1));

            var sagittal = slices.imageAt(Axis.X, 44);
            assertEquals(84, sagittal.mask().getBounds2D().getMaxY(), EPSILON);
            var coronal = slices.imageAt(Axis.Y, -24);
            assertEquals(72, coronal.mask().getBounds2D().getMinY(), EPSILON);
            assertTrue(slices.imageAt(Axis.Z, 90).mask().getBounds2D().isEmpty());
        }
    }

    @Test
    void explicitModelTransformLinksOverlaysWithoutChangingMeshCoordinates() throws Exception {
        Path mri = nifti("mri.nii", false, 2, 1, 50);
        Path mask = nifti("mask.nii", true, 2, 1, 50);
        Path transform = Files.writeString(temporary.resolve("mesh-to-ras.txt"), """
                1 0 0 39.5
                0 1 0 -27
                0 0 1 72
                0 0 0 1
                """);
        Path meshPath = Files.writeString(temporary.resolve("reconstructed.obj"), """
                v 0 0 0
                v 9 0 0
                v 9 8 0
                v 0 8 0
                v 0 0 12
                v 9 0 12
                v 9 8 12
                v 0 8 12
                f 1 4 3 2
                f 5 6 7 8
                f 1 2 6 5
                f 2 3 7 6
                f 3 4 8 7
                f 4 1 5 8
                """);
        try (var generated = Mesh.open(meshPath);
             var review = MriCase.open(mri, mask, false, generated, SpatialTransform.read(transform));
             var slices = new MriSlices(review)) {
            assertTrue(review.hasMeshAlignment());
            assertPoint(new Point(0, 0, 0), generated.bounds().min());
            assertPoint(review.reference().bounds().min(), review.alignedMesh().bounds().min());
            assertPoint(generated.bounds().min(), review.referenceInModel().bounds().min());
            Point marker = new Point(4, 3, 2);
            assertPoint(marker, review.rasToMesh().apply(review.meshToRas().apply(marker)));
            assertNotNull(slices.imageAt(Axis.Z, 78).mesh());
            assertThrows(IOException.class, () -> MriCase.open(mri, mask, false, generated,
                    SpatialTransform.identity()));
        }
    }

    @Test
    void rejectsMismatchedFramesAndRequiresExplicitUnitsWhenMissing() throws Exception {
        Path mri = nifti("mri.nii", false, 2, 1, 50);
        Path differentFrame = nifti("different frame.nii", true, 2, 2, 50);
        Path outside = nifti("outside.nii", true, 2, 1, 500);
        Path unspecified = nifti("unspecified units.nii", true, 0, 1, 50);
        assertThrows(IOException.class, () -> MriCase.open(mri, differentFrame, false, null, null));
        assertThrows(IOException.class, () -> MriCase.open(mri, outside, false, null, null));
        assertThrows(IOException.class, () -> MriCase.open(mri, unspecified, false, null, null));
        try (var review = MriCase.open(mri, unspecified, true, null, null)) {
            assertPoint(new Point(39.5, -27, 72), review.reference().bounds().min());
        }
    }

    @Test
    void usesQformAndConvertsMicrometresToMillimetres() throws Exception {
        Path path = nifti("qform.nii", false, 3, 0, 0);
        var file = ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN);
        file.putShort(252, (short) 1);
        file.putFloat(268, 1000);
        file.putFloat(272, -2000);
        file.putFloat(276, 3000);
        Files.write(path, file.array());
        try (var volume = NiftiVolume.open(path, false)) {
            assertPoint(new Point(1, -2, 2.98), volume.bounds().min());
            assertPoint(new Point(1.014, -1.982, 3), volume.bounds().max());
        }
    }

    @Test
    void comparesKnownSurfaceOffsetsInBothDirections() throws Exception {
        try (var mesh = plane("mesh.obj", 0); var reference = plane("reference.obj", 3)) {
            var result = SurfaceDeviation.measure(mesh, reference);
            assertEquals(3, result.meshToMask().median(), EPSILON);
            assertEquals(3, result.meshToMask().p95(), EPSILON);
            assertEquals(3, result.maskToMesh().median(), EPSILON);
            assertEquals(3, result.maskToMesh().p95(), EPSILON);
        }
    }

    private Mesh plane(String name, int z) throws IOException {
        Path path = Files.writeString(temporary.resolve(name), ("v 0 0 %d\nv 4 0 %d\nv 0 4 %d\nf 1 2 3\n")
                .formatted(z, z, z));
        return Mesh.open(path);
    }

    private Path nifti(String name, boolean isMask, int units, int spaceCode, float xOffset) throws IOException {
        int ni = 8;
        int nj = 7;
        int nk = 6;
        var file = ByteBuffer.allocate(352 + 2 * ni * nj * nk).order(ByteOrder.LITTLE_ENDIAN);
        file.putInt(0, 348);
        file.position(40);
        for (int dimension : new int[]{3, ni, nj, nk, 1, 1, 1, 1}) {
            file.putShort((short) dimension);
        }
        file.putShort(70, (short) 4);
        file.putShort(72, (short) 16);
        file.position(76);
        for (float spacing : new float[]{-1, 2, 3, 4, 1, 1, 1, 1}) {
            file.putFloat(spacing);
        }
        file.putFloat(108, 352);
        file.putFloat(112, 1);
        file.put(123, (byte) units);
        file.putShort(254, (short) spaceCode);
        file.position(280);
        for (float matrix : new float[]{0, -3, 0, xOffset, 2, 0, 0, -30, 0, 0, -4, 90}) {
            file.putFloat(matrix);
        }
        file.position(344);
        file.put("n+1\0".getBytes(StandardCharsets.US_ASCII));
        file.position(352);
        for (int k = 0; k < nk; k++) {
            for (int j = 0; j < nj; j++) {
                for (int i = 0; i < ni; i++) {
                    boolean insideMask = i >= 2 && i <= 5 && j >= 1 && j <= 3 && k >= 2 && k <= 4;
                    file.putShort((short) (isMask ? (insideMask ? 255 : 0) : 2 * i + 10 * j + 25 * k));
                }
            }
        }
        Path path = temporary.resolve(name);
        if (name.endsWith(".gz")) {
            try (var zip = new GZIPOutputStream(Files.newOutputStream(path))) {
                zip.write(file.array());
            }
        } else {
            Files.write(path, file.array());
        }
        return path;
    }

    private void assertPoint(Point expected, Point actual) {
        assertEquals(expected.x(), actual.x(), EPSILON);
        assertEquals(expected.y(), actual.y(), EPSILON);
        assertEquals(expected.z(), actual.z(), EPSILON);
    }

    private void assertGray(int expected, int rgb) {
        assertEquals(expected, rgb & 0xff, 1);
    }
}
