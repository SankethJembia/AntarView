package org.liverreview;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

record MriIdentity(String sha256, int width, int height, int depth) {
    static MriIdentity read(Path path, NiftiVolume mri, BasicFileAttributes before) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
        try (var stream = new DigestInputStream(Files.newInputStream(path), digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        var after = Files.readAttributes(path, BasicFileAttributes.class);
        if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("The MRI file changed during loading. Reopen the case.");
        }
        int[] size = mri.data().GetDimensions();
        return new MriIdentity(HexFormat.of().formatHex(digest.digest()), size[0], size[1], size[2]);
    }

    int slicePixels() {
        return Math.multiplyExact(width, height);
    }
}
