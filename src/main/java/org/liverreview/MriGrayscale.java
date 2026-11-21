package org.liverreview;

record MriGrayscale(double low, double high, boolean inverted) {
    static MriGrayscale forVolume(NiftiVolume volume) {
        double[] range = volume.data().GetScalarRange();
        double high = range[1] > range[0] ? range[1] : range[0] + 1;
        return new MriGrayscale(range[0], high, volume.slope() < 0);
    }

    double backgroundValue() {
        return inverted ? high : low;
    }

    int rgb(double raw) {
        double normalized = (raw - low) / (high - low);
        if (inverted) {
            normalized = 1 - normalized;
        }
        return (int) Math.round(255 * Math.clamp(normalized, 0, 1)) * 0x010101;
    }
}
