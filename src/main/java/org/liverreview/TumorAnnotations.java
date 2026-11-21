package org.liverreview;

import java.awt.Point;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

final class TumorAnnotations {
    static final String EXTENSION = ".liverlabels";
    static final int MAX_LABELS = 256;
    static final int MAX_NAME = 80;
    private static final int MAGIC = 0x4c4d414c; // LMAL
    private static final int VERSION = 1;

    private final MriIdentity mri;
    private final List<Label> labels = new ArrayList<>();

    TumorAnnotations(MriIdentity mri) {
        this.mri = mri;
    }

    List<Label> labels() {
        return List.copyOf(labels);
    }

    Label add(String name) {
        if (!validName(name) || labels.size() >= MAX_LABELS) {
            throw new IllegalArgumentException("Use a label name of 1 to " + MAX_NAME + " characters.");
        }
        var label = new Label(UUID.randomUUID(), name);
        labels.add(label);
        return label;
    }

    void remove(Label label) {
        labels.remove(label);
    }

    boolean stroke(Label label, int slice, Point start, Point end, int radius, boolean paint) {
        BitSet pixels = label.slices.get(slice);
        if (pixels == null) {
            if (!paint) {
                return false;
            }
            pixels = new BitSet();
        }
        int dx = end.x - start.x;
        int dy = end.y - start.y;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        boolean changed = stamp(pixels, start.x, start.y, radius, paint);
        for (int i = 1; i <= steps; i++) {
            int x = start.x + (int) Math.round(dx * (double) i / steps);
            int y = start.y + (int) Math.round(dy * (double) i / steps);
            changed |= stamp(pixels, x, y, radius, paint);
        }
        if (changed) {
            if (pixels.isEmpty()) {
                label.slices.remove(slice);
            } else {
                label.slices.put(slice, pixels);
            }
            label.reviewed = false;
        }
        return changed;
    }

    private boolean stamp(BitSet pixels, int x, int y, int radius, boolean paint) {
        boolean changed = false;
        for (int row = Math.max(0, y - radius); row <= Math.min(mri.height() - 1, y + radius); row++) {
            int dy = row - y;
            int half = (int) Math.sqrt(radius * radius - dy * dy);
            int start = row * mri.width() + Math.max(0, x - half);
            int end = row * mri.width() + Math.min(mri.width(), x + half + 1);
            int affected = paint ? pixels.nextClearBit(start) : pixels.nextSetBit(start);
            if (affected >= start && affected < end) {
                pixels.set(start, end, paint);
                changed = true;
            }
        }
        return changed;
    }

    void save(Path path) throws IOException {
        Path absolute = path.toAbsolutePath();
        Path temporary = Files.createTempFile(absolute.getParent(), ".liverlabels-", ".tmp");
        try {
            try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeUTF(mri.sha256());
                out.writeInt(mri.width());
                out.writeInt(mri.height());
                out.writeInt(mri.depth());
                out.writeInt(labels.size());
                for (Label label : labels) {
                    out.writeLong(label.id.getMostSignificantBits());
                    out.writeLong(label.id.getLeastSignificantBits());
                    out.writeUTF(label.name);
                    out.writeBoolean(label.reviewed);
                    out.writeInt(label.slices.size());
                    for (var entry : label.slices.entrySet()) {
                        byte[] pixels = entry.getValue().toByteArray();
                        out.writeInt(entry.getKey());
                        out.writeInt(pixels.length);
                        out.write(pixels);
                    }
                }
            }
            try {
                Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static TumorAnnotations load(Path path, MriIdentity mri) throws IOException {
        try (var in = new DataInputStream(new GZIPInputStream(Files.newInputStream(path)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("This annotation file has an unsupported format.");
            }
            var identity = new MriIdentity(in.readUTF(), in.readInt(), in.readInt(), in.readInt());
            if (!mri.equals(identity)) {
                throw new IOException("This annotation file belongs to a different MRI.");
            }
            var annotations = new TumorAnnotations(mri);
            var ids = new HashSet<UUID>();
            int count = in.readInt();
            if (count < 0 || count > MAX_LABELS) {
                throw new IOException("The annotation file has an invalid label count.");
            }
            for (int index = 0; index < count; index++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                String name = in.readUTF();
                if (!validName(name) || !ids.add(id)) {
                    throw new IOException("The annotation file contains an invalid label.");
                }
                var label = new Label(id, name);
                label.reviewed = in.readBoolean();
                readSlices(in, mri, label);
                if (label.reviewed && label.isEmpty()) {
                    throw new IOException("A reviewed annotation must contain a mask.");
                }
                annotations.labels.add(label);
            }
            if (in.read() != -1) {
                throw new IOException("The annotation file contains unexpected data.");
            }
            return annotations;
        }
    }

    private static void readSlices(DataInputStream in, MriIdentity mri, Label label) throws IOException {
        int slices = in.readInt();
        if (slices < 0 || slices > mri.depth()) {
            throw new IOException("The annotation file has an invalid slice count.");
        }
        int limit = (int) ((mri.slicePixels() + 7L) / 8);
        for (int i = 0; i < slices; i++) {
            int slice = in.readInt();
            int length = in.readInt();
            if (slice < 0 || slice >= mri.depth() || length <= 0 || length > limit || label.slices.containsKey(slice)) {
                throw new IOException("The annotation file contains an invalid slice mask.");
            }
            byte[] bytes = new byte[length];
            in.readFully(bytes);
            BitSet pixels = BitSet.valueOf(bytes);
            if (pixels.isEmpty() || pixels.length() > mri.slicePixels()) {
                throw new IOException("The annotation file contains an invalid voxel mask.");
            }
            label.slices.put(slice, pixels);
        }
    }

    private static boolean validName(String name) {
        return !name.isBlank() && name.length() <= MAX_NAME && name.codePoints().noneMatch(Character::isISOControl);
    }

    static final class Label {
        private final UUID id;
        private final String name;
        private final NavigableMap<Integer, BitSet> slices = new TreeMap<>();
        private boolean reviewed;

        private Label(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        UUID id() {
            return id;
        }

        String name() {
            return name;
        }

        boolean reviewed() {
            return reviewed;
        }

        boolean setReviewed(boolean value) {
            if ((value && isEmpty()) || reviewed == value) {
                return false;
            }
            reviewed = value;
            return true;
        }

        boolean isEmpty() {
            return slices.isEmpty();
        }

        int firstSlice() {
            return slices.firstKey();
        }

        BitSet pixels(int slice) {
            return slices.get(slice);
        }

        int sliceCount() {
            return slices.size();
        }
    }
}
