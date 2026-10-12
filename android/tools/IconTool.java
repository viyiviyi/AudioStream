import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;

/**
 * 把 PC 端的 icon.ico 转成 Android 的各密度图标。
 *
 * 用法：java tools/IconTool.java <源 ico> <app/src/main/res 目录>
 *
 * JDK 的 ImageIO 不认 ICO，所以这里自己读。ICO 只用到两种帧：PNG 压缩的（Vista 起）
 * 和未压缩的 DIB。本工程的 icon.ico 是后者，格式是：
 *   头 6 字节 + 每帧 16 字节目录项 + 每帧「40 字节 BITMAPINFOHEADER + 像素 + AND 掩码」。
 * DIB 有两个必须记住的坑：像素是**自下而上**存的，通道序是 **BGRA** 而不是 RGBA。
 *
 * 生成三样东西：
 *   1. mipmap-*dpi/ic_launcher.png            传统图标（48/72/96/144/192）
 *   2. mipmap-*dpi/ic_launcher_foreground.png adaptive 前景：108 画布，图形缩到 72（Android 定的安全区）
 *   3. drawable/ic_launcher_background.xml    adaptive 背景：图标顶部色到底部色的线性渐变
 *
 * 背景为什么是**渐变**而不是一个纯色：
 * 这个图标是蓝渐变的圆角方形、四角透明。启动器会把自适应图标裁成圆形或圆角方形，
 * 裁完只剩画布中央那块。若背景是纯色，前景那块渐变图形会在纯色背景上显出边界——
 * 看起来就是一个圆里嵌了一个方块。把背景做成**同方向**（上深下浅）的渐变，
 * 前景与背景的色带接得上，裁切后就只剩一个完整的图标，看不出接缝。
 * 顶色与底色不是在调色板上挑的，是从图标自身的上缘、下缘采样来的。
 */
public final class IconTool {

    private static final String[] DIRS = {
            "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi"
    };

    /** 各密度的传统图标边长（dp × 密度）。 */
    private static final int[] ICON_SIZES = {48, 72, 96, 144, 192};

    /** 各密度的 adaptive 画布边长：108dp。 */
    private static final int[] ADAPTIVE_SIZES = {108, 162, 216, 324, 432};

    /** Android 规定前景图形只占画布的 72/108，超出的部分会被启动器裁掉。 */
    private static final double SAFE_ZONE_RATIO = 72.0 / 108.0;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法：java IconTool <源 ico> <res 目录>");
            System.exit(2);
        }
        Path ico = Paths.get(args[0]);
        Path res = Paths.get(args[1]);

        BufferedImage source = readIco(ico.toFile());
        System.out.println("源图标 " + source.getWidth() + "x" + source.getHeight()
                + "，不透明像素 " + opaqueRatio(source));

        for (int i = 0; i < DIRS.length; i++) {
            Path dir = res.resolve(DIRS[i]);
            Files.createDirectories(dir);
            write(scale(source, ICON_SIZES[i]), dir.resolve("ic_launcher.png"));
            write(scaleToCanvas(source, ADAPTIVE_SIZES[i]), dir.resolve("ic_launcher_foreground.png"));
            System.out.println("  " + DIRS[i] + " : 图标 " + ICON_SIZES[i]
                    + "px，前景画布 " + ADAPTIVE_SIZES[i] + "px");
        }

        int top = rowColor(source, 0.04);
        int bottom = rowColor(source, 0.96);
        Path drawable = res.resolve("drawable");
        Files.createDirectories(drawable);
        Files.write(drawable.resolve("ic_launcher_background.xml"), gradientXml(top, bottom).getBytes("UTF-8"));
        System.out.println("背景渐变：顶 #" + String.format("%06X", top & 0xFFFFFF)
                + " → 底 #" + String.format("%06X", bottom & 0xFFFFFF));
    }

    /** 传统图标：整幅缩放到目标边长，四角透明原样保留。 */
    private static BufferedImage scale(BufferedImage source, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(source, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /** adaptive 前景：画布是 108dp，图形缩到安全区的 72dp 后居中，四周留透明。 */
    private static BufferedImage scaleToCanvas(BufferedImage source, int canvas) {
        BufferedImage out = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        int inner = (int) Math.round(canvas * SAFE_ZONE_RATIO);
        int offset = (canvas - inner) / 2;
        java.awt.Graphics2D g = out.createGraphics();
        hints(g);
        g.drawImage(source, offset, offset, inner, inner, null);
        g.dispose();
        return out;
    }

    private static void hints(java.awt.Graphics2D g) {
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
    }

    /**
     * 取指定高度那一行上不透明像素的平均色。只用中间那一半的像素，
     * 免得把圆角处的抗锯齿边缘（半透明、颜色被冲淡）算进去。
     */
    private static int rowColor(BufferedImage image, double fractionY) {
        int w = image.getWidth();
        int h = image.getHeight();
        int y = (int) Math.round((h - 1) * fractionY);
        long r = 0, g = 0, b = 0, n = 0;
        for (int x = w / 4; x < w * 3 / 4; x++) {
            int argb = image.getRGB(x, y);
            if (((argb >>> 24) & 0xFF) < 250) continue; // 边缘抗锯齿的半透明像素不要
            r += (argb >> 16) & 0xFF;
            g += (argb >> 8) & 0xFF;
            b += argb & 0xFF;
            n++;
        }
        if (n == 0) return 0xFF5CB2F6; // 采样不到就退回这幅图的整体平均色
        return 0xFF000000 | (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }

    private static String opaqueRatio(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        long opaque = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((image.getRGB(x, y) >>> 24) & 0xFF) > 200) opaque++;
            }
        }
        return String.format("%.1f%%", opaque * 100.0 / (w * (long) h));
    }

    private static String gradientXml(int top, int bottom) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<shape xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                + "    android:shape=\"rectangle\">\n"
                + "    <!--\n"
                + "      adaptive icon 的背景层。两端的颜色由 tools\\IconTool.java 从 PC 端 icon.ico\n"
                + "      的上下缘采样得来，与前景图形同一个渐变方向——这样启动器把图标裁成圆形或\n"
                + "      圆角方形之后，前景与背景的色带接得上，看不出「圆里嵌了个方块」的接缝。\n"
                + "      angle=270 表示自上而下（Android 的角度是逆时针，0 为从左到右）。\n"
                + "      换图标就重新跑一遍那个工具，这个文件会跟着更新。\n"
                + "    -->\n"
                + "    <gradient\n"
                + "        android:type=\"linear\"\n"
                + "        android:angle=\"270\"\n"
                + String.format("        android:startColor=\"#%06X\"\n", top & 0xFFFFFF)
                + String.format("        android:endColor=\"#%06X\" />\n", bottom & 0xFFFFFF)
                + "</shape>\n";
    }

    private static void write(BufferedImage image, Path path) throws IOException {
        ImageIO.write(image, "png", path.toFile());
    }

    /**
     * 读 ICO。只支持未压缩 DIB 帧——本工程的图标就是这一种；
     * 万一将来换成一个 PNG 压缩的 ico，这里会明确报错而不是产出一张空图。
     */
    private static BufferedImage readIco(File file) throws IOException {
        byte[] data = Files.readAllBytes(file.toPath());
        int count = u16(data, 4);
        if (count <= 0) throw new IOException("ico 里没有图像帧");

        int best = -1;
        int bestArea = -1;
        for (int i = 0; i < count; i++) {
            int o = 6 + i * 16;
            int w = data[o] & 0xFF;
            int h = data[o + 1] & 0xFF;
            if (w == 0) w = 256;
            if (h == 0) h = 256;
            if (w * h > bestArea) {
                bestArea = w * h;
                best = i;
            }
        }

        int entry = 6 + best * 16;
        int width = data[entry] & 0xFF;
        int height = data[entry + 1] & 0xFF;
        if (width == 0) width = 256;
        if (height == 0) height = 256;
        int offset = (int) u32(data, entry + 12);

        if (data[offset] == (byte) 0x89 && data[offset + 1] == (byte) 0x50) {
            throw new IOException("这个 ico 用的是 PNG 压缩帧，本工具只实现了解压 DIB 帧");
        }

        int headerSize = (int) u32(data, offset);
        if (headerSize < 40) throw new IOException("DIB 头长度异常：" + headerSize);
        int bpp = u16(data, offset + 14);
        if (bpp != 32) throw new IOException("只支持 32 位 DIB，这个是 " + bpp + " 位");

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int pixelsAt = offset + headerSize;
        for (int y = 0; y < height; y++) {
            // DIB 自下而上：文件里的最后一行是图像的第一行
            int rowStart = pixelsAt + (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int at = rowStart + x * 4;
                int b = data[at] & 0xFF;
                int g = data[at + 1] & 0xFF;
                int r = data[at + 2] & 0xFF;
                int a = data[at + 3] & 0xFF;
                image.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return image;
    }

    private static int u16(byte[] d, int at) {
        return (d[at] & 0xFF) | ((d[at + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] d, int at) {
        return (d[at] & 0xFFL) | ((d[at + 1] & 0xFFL) << 8)
                | ((d[at + 2] & 0xFFL) << 16) | ((d[at + 3] & 0xFFL) << 24);
    }
}
