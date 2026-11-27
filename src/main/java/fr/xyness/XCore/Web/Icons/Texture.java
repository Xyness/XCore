package fr.xyness.XCore.Web.Icons;

import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * One of the game's textures, decoded to plain pixels.
 *
 * @since 2.1
 */
final class Texture {

    /** The widest texture read: a game texture is 16 pixels, a high-resolution one 512. */
    static final int MAX_WIDTH = 512;

    /** The tallest: an animation is a strip of frames, a few hundred pixels for the longest. */
    static final int MAX_HEIGHT = 8192;

    /** The most pixels one texture may hold once decoded: one megapixel, four megabytes. */
    static final long MAX_PIXELS = 1L << 20;

    final int width;
    final int height;

    /** The pixels row by row, as non-premultiplied ARGB. */
    final int[] argb;

    Texture(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    /**
     * Decodes a PNG.
     *
     * @param png        The file.
     * @param firstFrame Whether a tall strip is an animation to take the first frame of. The game
     *                   stacks the frames of {@code sea_lantern} or {@code magma} in one file; an
     *                   entity's texture is never one, whatever its shape.
     * @return The texture, or {@code null} when the bytes are not an image that can be read.
     */
    static Texture decode(byte[] png, boolean firstFrame) {
        if (png == null || png.length == 0) return null;
        BufferedImage image;
        // Read from memory by hand: ImageIO.read() would spool the bytes through a temporary file.
        try (ImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                // The size first, from the header: decoding allocates width x height pixels, and a
                // few hundred bytes of PNG can declare a gigabyte of them.
                int declaredWidth = reader.getWidth(0);
                int declaredHeight = reader.getHeight(0);
                if (declaredWidth <= 0 || declaredHeight <= 0 || declaredWidth > MAX_WIDTH
                        || declaredHeight > MAX_HEIGHT || (long) declaredWidth * declaredHeight > MAX_PIXELS) {
                    return null;
                }
                image = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (Exception unreadable) {
            // A damaged file is a texture the item goes without, not a reason to stop.
            return null;
        }
        if (image == null) return null;

        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0 || width > MAX_WIDTH || height > MAX_HEIGHT) return null;
        int[] pixels = pixels(image, width, height);
        if (firstFrame && height > width && height % width == 0) {
            height = width;
            pixels = java.util.Arrays.copyOf(pixels, width * height);
        }
        return new Texture(width, height, pixels);
    }

    /**
     * Reads the pixels as they are stored.
     *
     * <p>{@link BufferedImage#getRGB} cannot be trusted with the greyscale files many of the
     * game's textures are saved as: it takes their values for linear light and brightens every one
     * of them on the way to sRGB, which turns stone visibly pale. Those are read sample by
     * sample instead.</p>
     */
    private static int[] pixels(BufferedImage image, int width, int height) {
        int[] out = new int[width * height];
        ColorModel model = image.getColorModel();
        Raster raster = image.getRaster();
        int bands = raster.getNumBands();
        if (model instanceof IndexColorModel || bands < 1 || bands > 4) {
            image.getRGB(0, 0, width, height, out, 0, width);
            return out;
        }

        int[] bits = raster.getSampleModel().getSampleSize();
        int[] sample = new int[bands];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                raster.getPixel(x, y, sample);
                for (int b = 0; b < bands; b++) sample[b] = toByte(sample[b], bits[b]);
                int r, g, b, a;
                if (bands <= 2) {
                    r = g = b = sample[0];
                    a = bands == 2 ? sample[1] : 255;
                } else {
                    r = sample[0];
                    g = sample[1];
                    b = sample[2];
                    a = bands == 4 ? sample[3] : 255;
                }
                out[y * width + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        return out;
    }

    /** Brings a sample of any depth to eight bits. */
    private static int toByte(int value, int bits) {
        if (bits == 8) return value & 0xFF;
        if (bits > 8) return (value >>> (bits - 8)) & 0xFF;
        int max = (1 << bits) - 1;
        return max == 0 ? 0 : value * 255 / max;
    }

    /**
     * The pixel under a point of the texture, nearest neighbour: the game never smooths a block.
     *
     * @param u Across, from 0 at the left edge to 1 at the right.
     * @param v Down, from 0 at the top edge to 1 at the bottom.
     */
    int at(double u, double v) {
        int x = (int) Math.floor(u * width);
        int y = (int) Math.floor(v * height);
        if (x < 0) x = 0; else if (x >= width) x = width - 1;
        if (y < 0) y = 0; else if (y >= height) y = height - 1;
        return argb[y * width + x];
    }

    /** Whether every pixel is fully opaque: what tells a block's own texture from a plant's. */
    boolean isOpaque() {
        for (int pixel : argb) {
            if ((pixel >>> 24) != 0xFF) return false;
        }
        return true;
    }
}
