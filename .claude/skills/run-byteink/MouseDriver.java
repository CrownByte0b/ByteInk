import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Real X11 mouse input and screenshots for whatever is on $DISPLAY. driver.py runs this with the
 * viewer's own JDK: that one certainly has AWT, which a distribution's headless java does not.
 *
 * Arguments are one script of commands, run in order:
 *   window SECONDS | layout | control NAME | move X Y [STEPS] | down | up | click X Y |
 *   drag X1 Y1 X2 Y2 [STEPS] | sleep MS | shot FILE
 */
public class MouseDriver {
    /** The viewer's toolbar rows, left to right. "Minus" and "Plus" are the zoom buttons. */
    private static final String[][] CONTROLS = {
        {"Open", "Previous", "Next", "Minus", "Plus", "Fit"},
        {"Pan", "Pen", "Calligraphy", "Highlighter", "Partial eraser", "Object eraser", "Smoothing"},
    };
    /** Columns further apart than this are separate controls: wider than a space, narrower than their padding. */
    private static final int CONTROL_GAP = 9;

    private static Robot robot;
    private static int x, y;

    public static void main(String[] args) throws Exception {
        robot = new Robot();
        robot.setAutoDelay(4);
        int i = 0;
        while (i < args.length) {
            String command = args[i++];
            switch (command) {
                case "window" -> window(Integer.parseInt(args[i++]));
                case "move" -> {
                    int tx = Integer.parseInt(args[i++]), ty = Integer.parseInt(args[i++]);
                    int steps = number(args, i) ? Integer.parseInt(args[i++]) : 30;
                    glide(tx, ty, steps);
                }
                case "down" -> { robot.mousePress(InputEvent.BUTTON1_DOWN_MASK); robot.delay(40); }
                case "up" -> { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK); robot.delay(200); }
                case "click" -> click(Integer.parseInt(args[i++]), Integer.parseInt(args[i++]));
                case "layout" -> layout().forEach((name, at) -> System.out.printf("%s %d,%d%n", name, at[0], at[1]));
                case "control" -> {
                    String name = args[i++];
                    int[] at = layout().get(name);
                    if (at == null) throw new IllegalArgumentException("No control named " + name + "; run layout");
                    click(at[0], at[1]);
                }
                case "drag" -> {
                    int x1 = Integer.parseInt(args[i++]), y1 = Integer.parseInt(args[i++]);
                    int x2 = Integer.parseInt(args[i++]), y2 = Integer.parseInt(args[i++]);
                    int steps = number(args, i) ? Integer.parseInt(args[i++]) : 30;
                    jump(x1, y1);
                    robot.delay(80);
                    robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(60);
                    glide(x2, y2, steps);
                    robot.delay(60);
                    robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                    robot.delay(200);
                }
                case "sleep" -> robot.delay(Integer.parseInt(args[i++]));
                case "shot" -> {
                    File file = new File(args[i++]).getAbsoluteFile();
                    ImageIO.write(capture(), "png", file);
                    System.out.println("screenshot " + file);
                }
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            }
        }
        // AWT's own threads would otherwise keep this JVM alive.
        System.exit(0);
    }

    /**
     * Where each toolbar label and the page are, read from a screenshot: fonts move them between
     * machines, and a selected tool's brackets move the ones after it. Fails if the toolbar has
     * changed, rather than clicking whatever now sits at a remembered position.
     */
    private static Map<String, int[]> layout() {
        int[] window = paintedBounds(capture());
        if (window == null) throw new IllegalStateException("No painted window");
        // A hovered control draws a box that would join it to its neighbours.
        jump(window[0] + window[2] - 3, window[1] + 3);
        robot.delay(250);
        BufferedImage image = capture();
        int band = image.getRGB(window[0] + 2, window[1] + 2) & 0xffffff;
        int top = window[1];
        while (top < window[1] + window[3] && (image.getRGB(window[0] + 1, top) & 0xffffff) != 0xffffff) top++;
        List<int[]> rows = new ArrayList<>();
        int start = -1;
        for (int py = window[1]; py <= top; py++) {
            boolean ink = false;
            for (int px = window[0]; py < top && px < window[0] + window[2] && !ink; px++) ink = differs(image, px, py, band);
            if (ink && start < 0) start = py;
            if (!ink && start >= 0) { rows.add(new int[] {start, py}); start = -1; }
        }
        Map<String, int[]> result = new LinkedHashMap<>();
        for (int row = 0; row < CONTROLS.length; row++) {
            if (row >= rows.size()) throw new IllegalStateException("Expected toolbar row " + row + " in a screenshot");
            List<int[]> labels = labels(image, window, rows.get(row), band);
            String[] names = CONTROLS[row];
            // Row 0 also ends with the page count and zoom readout.
            if (row == 0 ? labels.size() < names.length : labels.size() != names.length) {
                throw new IllegalStateException("Toolbar row " + row + " has " + labels.size() + " labels, expected " +
                    names.length + ": update CONTROLS in MouseDriver.java to match Main.kt");
            }
            for (int i = 0; i < names.length; i++) result.put(names[i], labels.get(i));
        }
        result.put("Page", new int[] {window[0], top});
        return result;
    }

    /** The centre of each label in one row of text. */
    private static List<int[]> labels(BufferedImage image, int[] window, int[] row, int band) {
        List<int[]> labels = new ArrayList<>();
        int first = -1, last = -1, minY = 0, maxY = 0;
        for (int px = window[0]; px < window[0] + window[2]; px++) {
            int inkTop = -1, inkBottom = -1;
            for (int py = row[0]; py < row[1]; py++) {
                if (differs(image, px, py, band)) {
                    if (inkTop < 0) inkTop = py;
                    inkBottom = py;
                }
            }
            if (inkTop < 0) continue;
            if (first >= 0 && px - last > CONTROL_GAP) {
                labels.add(new int[] {(first + last) / 2, (minY + maxY) / 2});
                first = -1;
            }
            if (first < 0) { first = px; minY = inkTop; maxY = inkBottom; }
            last = px;
            minY = Math.min(minY, inkTop);
            maxY = Math.max(maxY, inkBottom);
        }
        if (first >= 0) labels.add(new int[] {(first + last) / 2, (minY + maxY) / 2});
        return labels;
    }

    private static boolean differs(BufferedImage image, int px, int py, int colour) {
        int rgb = image.getRGB(px, py);
        return Math.abs((rgb >> 16 & 255) - (colour >> 16 & 255)) > 24 || Math.abs((rgb >> 8 & 255) - (colour >> 8 & 255)) > 24 ||
            Math.abs((rgb & 255) - (colour & 255)) > 24;
    }

    /** Waits for a painted window: colourful enough to hold text, with bounds that stopped moving. */
    private static void window(int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        int[] previous = null;
        int stable = 0;
        while (System.currentTimeMillis() < deadline) {
            int[] bounds = paintedBounds(capture());
            stable = bounds != null && Arrays.equals(bounds, previous) ? stable + 1 : 0;
            previous = bounds;
            if (stable >= 3) {
                System.out.printf("window at %d,%d size %dx%d%n", bounds[0], bounds[1], bounds[2], bounds[3]);
                System.exit(0);
            }
            robot.delay(250);
        }
        System.out.println("no painted window after " + seconds + "s");
        System.exit(3);
    }

    /** x, y, width, height of everything that isn't the root window's black; null until text shows. */
    private static int[] paintedBounds(BufferedImage image) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        HashSet<Integer> colours = new HashSet<>();
        for (int py = 0; py < image.getHeight(); py++) {
            for (int px = 0; px < image.getWidth(); px++) {
                int rgb = image.getRGB(px, py) & 0xffffff;
                if (rgb == 0) continue;
                if (colours.size() < 64) colours.add(rgb);
                minX = Math.min(minX, px);
                minY = Math.min(minY, py);
                maxX = Math.max(maxX, px);
                maxY = Math.max(maxY, py);
            }
        }
        // A window that has only cleared its background has no antialiased text yet.
        if (maxX < 0 || colours.size() < 8) return null;
        return new int[] {minX, minY, maxX - minX + 1, maxY - minY + 1};
    }

    private static BufferedImage capture() {
        return robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
    }

    private static boolean number(String[] args, int i) {
        return i < args.length && args[i].chars().allMatch(Character::isDigit);
    }

    private static void click(int tx, int ty) {
        jump(tx, ty);
        robot.delay(80);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(60);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        robot.delay(200);
    }

    private static void jump(int tx, int ty) {
        robot.mouseMove(tx, ty);
        x = tx;
        y = ty;
    }

    private static void glide(int tx, int ty, int steps) {
        int sx = x, sy = y;
        for (int s = 1; s <= steps; s++) {
            robot.mouseMove(sx + (tx - sx) * s / steps, sy + (ty - sy) * s / steps);
            robot.delay(8);
        }
        x = tx;
        y = ty;
    }
}
