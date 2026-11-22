package dev.bennett.codexmeter.glass;

import static org.junit.Assert.assertTrue;

import com.benitbuhner.glasse.demo.Capture;
import com.benitbuhner.glasse.ui.ScreenMaker;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.skia.Bitmap;
import org.junit.Test;

/**
 * Edit → Done → Edit on Providers blurs one label into the other on a button that never leaves
 * the screen. Before Glassé's fix the button faded to nothing as it turned blue and Done popped in about
 * a second later. Every 60 Hz frame of both taps must still show the button. With
 * {@code -Dcap.frames=<dir>} the frames are saved as PNGs too.
 */
public class EditDoneMorphTest {
    private static final double W = 393;
    private static final double X = W - 50;
    private static final double Y = 58;

    private Capture c;
    private String out;
    private int frame;

    /** the share of the button's middle that isn't the page's background, 0 to 1 */
    private double shown() {
        Bitmap b = Bitmap.Companion.makeFromImage(c.image());
        int s = 2;
        int bg = b.getColor(10 * s, 400 * s);
        int all = 0;
        int differ = 0;
        for (int y = (int) (Y - 8) * s; y <= (int) (Y + 8) * s; y += 2) {
            for (int x = (int) (X - 18) * s; x <= (int) (X + 18) * s; x += 2) {
                int p = b.getColor(x, y);
                int d = Math.max(Math.abs(((p >> 16) & 255) - ((bg >> 16) & 255)),
                        Math.max(Math.abs(((p >> 8) & 255) - ((bg >> 8) & 255)), Math.abs((p & 255) - (bg & 255))));
                all++;
                if (d > 5) {
                    differ++;
                }
            }
        }
        b.close();
        return (double) differ / all;
    }

    private double frames(int n) {
        double least = 1;
        for (int i = 0; i < n; i++) {
            c.run(1000.0 / 60);
            if (out != null) {
                c.shot(String.format("%s/f%04d.png", out, frame++));
            }
            least = Math.min(least, shown());
        }
        return least;
    }

    @Test
    public void theButtonStaysThroughTheMorph() {
        out = System.getProperty("cap.frames");
        FakeSource src = new FakeSource();
        Map<String, ScreenMaker> makers = new LinkedHashMap<>();
        c = new Capture(W, 852, 32, 24, 2, System.getProperty("cap.scheme", "light"),
                env -> CodexGlass.page(env, src, "x:tabs"), makers);
        c.run(2500);
        frames(4);
        c.host.down(X, Y);
        frames(5);
        c.host.up(X, Y);
        double toDone = frames(90);
        c.host.down(X, Y);
        frames(5);
        c.host.up(X, Y);
        double toEdit = frames(90);
        assertTrue("Edit → Done: the button faded to " + toDone, toDone > 0.5);
        assertTrue("Done → Edit: the button faded to " + toEdit, toEdit > 0.5);
    }
}
