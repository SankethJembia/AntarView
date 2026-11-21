package org.liverreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TumorAnnotationsTest {
    private static final MriIdentity MRI = new MriIdentity("a".repeat(64), 24, 16, 12);

    @TempDir
    Path temporary;

    @Test
    void preservesSeparateMaskIdsSlicesAndReviewStateAfterSaving() throws IOException {
        var annotations = new TumorAnnotations(MRI);
        var first = annotations.add("Tumor 1");
        var second = annotations.add("Tumor 2");
        assertTrue(annotations.stroke(first, 2, new Point(2, 3), new Point(6, 3), 0, true));
        assertTrue(annotations.stroke(first, 5, new Point(2, 3), new Point(2, 3), 1, true));
        assertTrue(annotations.stroke(second, 2, new Point(2, 3), new Point(2, 3), 0, true));
        assertTrue(first.setReviewed(true));
        Path file = temporary.resolve("review.liverlabels");
        annotations.save(file);

        var reopened = TumorAnnotations.load(file, MRI);
        assertEquals(2, reopened.labels().size());
        var savedFirst = reopened.labels().get(0);
        var savedSecond = reopened.labels().get(1);
        assertEquals(first.id(), savedFirst.id());
        assertEquals(second.id(), savedSecond.id());
        assertEquals("Tumor 2", savedSecond.name());
        assertTrue(savedFirst.reviewed());
        assertFalse(savedSecond.reviewed());
        assertEquals(first.pixels(2), savedFirst.pixels(2));
        assertEquals(first.pixels(5), savedFirst.pixels(5));
        assertEquals(2, savedFirst.firstSlice());
        assertEquals(2, savedFirst.sliceCount());
        assertNull(savedSecond.pixels(5));
        assertEquals(1, savedSecond.pixels(2).cardinality());

        assertTrue(reopened.stroke(savedFirst, 5, new Point(2, 3), new Point(2, 3), 1, false));
        reopened.save(file);
        var updated = TumorAnnotations.load(file, MRI).labels().getFirst();
        assertEquals(savedFirst.id(), updated.id());
        assertFalse(updated.reviewed());
        assertNull(updated.pixels(5));
    }

    @Test
    void clipsBrushAtImageEdgeAndClearsReviewOnlyWhenMaskChanges() {
        var annotations = new TumorAnnotations(MRI);
        var label = annotations.add("Review");
        var corner = new Point(0, 0);
        assertFalse(label.setReviewed(true));
        assertTrue(annotations.stroke(label, 3, corner, corner, 1, true));
        assertEquals(3, label.pixels(3).cardinality());
        assertTrue(label.setReviewed(true));
        assertFalse(annotations.stroke(label, 3, corner, corner, 1, true));
        assertTrue(label.reviewed());
        assertTrue(annotations.stroke(label, 3, corner, corner, 1, false));
        assertFalse(label.reviewed());
        assertTrue(label.isEmpty());
    }

    @Test
    void rejectsOtherMriAndTruncatedFiles() throws IOException {
        var annotations = new TumorAnnotations(MRI);
        annotations.add("Draft");
        Path file = temporary.resolve("review.liverlabels");
        annotations.save(file);
        assertThrows(IOException.class, () -> TumorAnnotations.load(file,
                new MriIdentity("b".repeat(64), MRI.width(), MRI.height(), MRI.depth())));
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(bytes, bytes.length - 4));
        assertThrows(IOException.class, () -> TumorAnnotations.load(file, MRI));
    }
}
