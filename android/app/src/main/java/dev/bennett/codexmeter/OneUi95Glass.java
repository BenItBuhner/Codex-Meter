package dev.bennett.codexmeter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import java.util.ArrayList;
import java.util.List;

/**
 * One UI 9.5's glass touches, drawn on top of the One UI 9 shell ({@link OneUi9Page}).
 *
 * <p>The leaked One UI 9.5 builds keep One UI 9's layout, cards, switches and behavior. What
 * changes is the floating chrome: the tab bar becomes a clearer piece of glass that bends the
 * content behind it at its edges (refraction), with a bright rim and a soft drop shadow, and the
 * header buttons get the same shiny rim. This class recreates that look in our own code:
 *
 * <ul>
 *   <li>{@link Lens}: draws what is behind a floating bar, lightly blurred, through an AGSL lens
 *       that pulls the content outward near the rim with a slight color split (Android 13+).
 *       Android 12 gets the blur without the bend; older phones keep SESL's own bar.</li>
 *   <li>{@link Rim}: the specular edge. A thin highlight that is brightest along the top, a
 *       fainter one along the bottom, and an inner sheen across the top third.</li>
 * </ul>
 */
final class OneUi95Glass {
    private OneUi95Glass() {}

    /** Whether the page uses the One UI 9.5 look (the One UI 9 shell with these glass touches). */
    static boolean isOn(Context context) {
        return AppPreferences.isOneUi95Design(context);
    }

    static boolean isDark(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Whether {@link #dressBar} replaces the tab bar's background with a {@link Lens} (Android 12+
     * in One UI 9.5). The bar is then inflated without Samsung's own view blur: on Samsung phones
     * that blur fills the bar's whole view, clipped only by its outline.
     */
    static boolean drawsBarGlass(Context context) {
        return Build.VERSION.SDK_INT >= 31 && isOn(context);
    }

    /**
     * Puts the glass behind a floating bar on the page. On Android 12+ the bar's own SESL
     * background is dropped and a {@link Lens} draws the glass; older phones keep SESL's bar and
     * only get the rim.
     */
    static View dressBar(OneUi9Page page, View bar) {
        Context context = page.activity;
        if (Build.VERSION.SDK_INT < 31) {
            Rim rim = new Rim(context);
            bar.setForeground(rim);
            return null;
        }
        Lens lens = new Lens(context, bar, page.pageColor());
        lens.addSource(page.body);
        lens.addSource(page.appBar);
        ViewGroup root = page.root;
        root.addView(lens, root.indexOfChild(bar), new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        bar.setBackground(null);
        bar.setBackgroundTintList(null);
        // The lens casts the shadow. The bar keeps an invisible pill outline that it clips to, so
        // nothing it draws (a pressed tab, or a platform blur) can spill past the glass.
        bar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getHeight() / 2f);
                outline.setAlpha(0f);
            }
        });
        bar.setClipToOutline(true);
        // Keep the bar drawn above its lens, which has its own elevation for the drop shadow.
        bar.setElevation(lens.getElevation() + Ui.dp(context, 1f));
        return lens;
    }

    /** Redraws a lens from {@link #dressBar} after the content under it moved. */
    static void refresh(View lens) {
        if (lens != null && Build.VERSION.SDK_INT >= 31) {
            ((Lens) lens).refresh();
        }
    }

    /**
     * Gives SESL9's floating header button pills the One UI 9.5 rim. SESL creates and moves those
     * pills itself (its projection background views), so this looks them up after each layout and
     * adds a rim overlay once to each new one.
     */
    static void dressHeaderPills(ViewGroup floating) {
        floating.getViewTreeObserver().addOnGlobalLayoutListener(() -> dressPillsIn(floating));
    }

    // BlurSupportable is SESL-internal, but only its type is checked, against the pinned SESL9.
    @SuppressLint("RestrictedApi")
    private static void dressPillsIn(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            // SESL's pill backgrounds are its only blur-capable plain views in the toolbar. Matched
            // by type, not class name, so R8's renaming in release builds can't break it.
            if (child instanceof androidx.appcompat.oneui.common.BlurSupportable
                    && !(child instanceof ViewGroup)) {
                dressPill(child);
            } else if (child instanceof ViewGroup) {
                dressPillsIn((ViewGroup) child);
            }
        }
    }

    private static void dressPill(View pill) {
        if (pill.getTag(R.id.oneui95_rim) != null) {
            return;
        }
        Rim rim = new Rim(pill.getContext());
        // SESL resizes its pills by setting their edges directly, without a layout pass, so the
        // rim takes the pill's size whenever it draws instead of from layout callbacks.
        rim.followSizeOf(pill);
        pill.setTag(R.id.oneui95_rim, rim);
        pill.getOverlay().add(rim);
    }

    /**
     * The specular rim of a pill-shaped piece of glass: brightest along the top edge, fainter at
     * the bottom, with an inner sheen across the top. Its corner radius is half its height unless
     * set.
     */
    static final class Rim extends Drawable {
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hairline = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float stroke;
        private final boolean dark;
        private float radius = -1f;
        private int shaderHeight = -1;
        private View sizeSource;

        Rim(Context context) {
            this.stroke = Ui.dp(context, 1f);
            this.dark = isDark(context);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
            hairline.setStyle(Paint.Style.STROKE);
            hairline.setStrokeWidth(Math.max(1f, stroke * 0.5f));
            // Defines the edge on light pages, where a white rim alone would vanish.
            hairline.setColor(dark ? 0x33000000 : 0x14000000);
        }

        /** Draws over all of {@code view}, whatever size it has when the rim draws. */
        void followSizeOf(View view) {
            sizeSource = view;
        }

        void setRadius(float radius) {
            this.radius = radius;
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            View source = sizeSource;
            if (source != null) {
                Rect now = getBounds();
                if (now.left != 0 || now.top != 0 || now.right != source.getWidth()
                        || now.bottom != source.getHeight()) {
                    setBounds(0, 0, source.getWidth(), source.getHeight());
                }
            }
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            if (shaderHeight != b.height()) {
                shaderHeight = b.height();
                float h = b.height();
                edge.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                        new int[] {dark ? 0x8CFFFFFF : 0xF2FFFFFF, dark ? 0x14FFFFFF : 0x40FFFFFF,
                                dark ? 0x1FFFFFFF : 0x40FFFFFF, dark ? 0x4DFFFFFF : 0xB3FFFFFF},
                        new float[] {0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
                sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.45f,
                        dark ? 0x1AFFFFFF : 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            }
            float r = radius >= 0 ? radius : b.height() / 2f;
            float half = stroke / 2f;
            box.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
            canvas.drawRoundRect(box, r, r, sheen);
            canvas.drawRoundRect(box, Math.max(0, r - half), Math.max(0, r - half), hairline);
            box.inset(half, half);
            canvas.drawRoundRect(box, Math.max(0, r - stroke), Math.max(0, r - stroke), edge);
        }

        @Override
        public void setAlpha(int alpha) {
            edge.setAlpha(alpha);
            sheen.setAlpha(alpha);
            hairline.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            edge.setColorFilter(filter);
            sheen.setColorFilter(filter);
        }

        // Still abstract on Drawable, though deprecated.
        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    /**
     * AGSL lens, which also cuts the glass to its shape: inside a rounded rectangle of half size
     * {@code halfSize} (centered in the capture,
     * which reaches {@code pad} past it on every side), content near the rim is sampled further out
     * along the edge normal, so it looks bent into the glass, with red and blue split slightly.
     */
    static final String LENS_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 halfSize;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "uniform float depth;\n"
            + "uniform float chroma;\n"
            + "float box(float2 p) {\n"
            + "  float2 q = abs(p) - halfSize + radius;\n"
            + "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "}\n"
            + "half4 main(float2 xy) {\n"
            + "  float2 p = xy - size * 0.5;\n"
            + "  float d = box(p);\n"
            + "  if (d > 1.0) { return half4(0.0); }\n"
            + "  half cover = half(clamp(0.5 - d, 0.0, 1.0));\n"
            + "  float e = 1.0;\n"
            + "  float2 g = float2(box(p + float2(e, 0.0)) - box(p - float2(e, 0.0)),\n"
            + "                    box(p + float2(0.0, e)) - box(p - float2(0.0, e)));\n"
            + "  float len = length(g);\n"
            + "  float2 n = len > 0.0001 ? g / len : float2(0.0);\n"
            + "  float t = clamp(1.0 + d / band, 0.0, 1.0);\n"
            + "  float bend = depth * t * t * t;\n"
            + "  float2 lo = float2(2.0);\n"
            + "  float2 hi = size - 2.0;\n"
            + "  float2 s = clamp(xy + n * bend, lo, hi);\n"
            + "  float split = chroma * t * t;\n"
            + "  half4 mid = content.eval(s);\n"
            + "  half red = content.eval(clamp(s + n * split, lo, hi)).r;\n"
            + "  half blue = content.eval(clamp(s - n * split, lo, hi)).b;\n"
            + "  return half4(red, mid.g, blue, mid.a) * cover;\n"
            + "}\n";

    /**
     * Draws the page content behind {@code target} as One UI 9.5 glass. It fills the page (so it
     * can paint the shadow and the lens wherever the bar is) but only draws inside the bar's
     * rounded shape, and it never takes touches.
     */
    @RequiresApi(31)
    @SuppressLint("ViewConstructor") // built in code only, never inflated
    static final class Lens extends View {
        private final View target;
        private final List<View> sources = new ArrayList<>();
        private final int baseColor;
        private final int tint;
        private final int pad;
        private final float blur;
        private final RenderNode capture = new RenderNode("oneui95-lens");
        private final Rect area = new Rect();
        private final RectF shape = new RectF();
        private final Path clip = new Path();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rim rim;
        private final Object shader;
        private int effectW = -1;
        private int effectH = -1;
        private boolean capturing;

        Lens(Context context, View target, int baseColor) {
            super(context);
            this.target = target;
            this.baseColor = baseColor;
            boolean dark = isDark(context);
            // Clearer than One UI 9's tinted bar, but enough body to keep the labels readable.
            this.shader = Build.VERSION.SDK_INT >= 33 ? lensShader() : null;
            this.tint = shader != null ? (dark ? 0x8C1E1E21 : 0x99FAFAFC)
                    : (dark ? 0xB31E1E21 : 0xB3FAFAFC);
            this.pad = Ui.dp(context, 18f);
            this.blur = Ui.dp(context, shader != null ? 7f : 14f);
            this.rim = new Rim(context);
            fill.setColor(tint);
            setWillNotDraw(false);
            setClickable(false);
            setFocusable(false);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            setElevation(Ui.dp(context, 5f));
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    if (!targetArea()) {
                        outline.setEmpty();
                        return;
                    }
                    outline.setRoundRect(area, area.height() / 2f);
                    outline.setAlpha(dark ? 0.3f : 0.35f);
                }
            });
            target.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> refresh());
        }

        /** The lens shader, or null if this phone's GPU driver can't build it (frosted glass then). */
        @RequiresApi(33)
        private static Object lensShader() {
            try {
                return new RuntimeShader(LENS_SHADER);
            } catch (RuntimeException e) {
                return null;
            }
        }

        void addSource(View source) {
            sources.add(source);
        }

        /** Redraws the glass, e.g. after the content under it moved. */
        void refresh() {
            setVisibility(target.getVisibility() == VISIBLE ? VISIBLE : GONE);
            invalidateOutline();
            invalidate();
        }

        private boolean targetArea() {
            if (target.getVisibility() != VISIBLE || target.getWidth() == 0) {
                return false;
            }
            int left = Math.round(target.getLeft() + target.getTranslationX());
            int top = Math.round(target.getTop() + target.getTranslationY());
            area.set(left, top, left + target.getWidth(), top + target.getHeight());
            return true;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (capturing || !targetArea()) {
                return;
            }
            float radius = area.height() / 2f;
            shape.set(area);
            clip.reset();
            clip.addRoundRect(shape, radius, radius, Path.Direction.CW);
            // Drawn unclipped: the lens shader cuts the shape itself. A clip here would let the GPU
            // render only the shape, and the lens samples the margin around it.
            if (canvas.isHardwareAccelerated()) {
                drawBackdrop(canvas, radius);
            }
            canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(shape, fill);
            canvas.restore();
            rim.setBounds(area);
            rim.draw(canvas);
        }

        /** Records the content behind the bar (plus a margin the lens samples from) and draws it. */
        private void drawBackdrop(Canvas canvas, float radius) {
            int w = area.width() + pad * 2;
            int h = area.height() + pad * 2;
            capture.setPosition(0, 0, w, h);
            RecordingCanvas rc = capture.beginRecording(w, h);
            capturing = true;
            try {
                rc.drawColor(baseColor);
                rc.translate(pad - area.left, pad - area.top);
                for (View source : sources) {
                    if (source.getVisibility() != VISIBLE) {
                        continue;
                    }
                    rc.save();
                    rc.translate(source.getLeft() + source.getTranslationX(),
                            source.getTop() + source.getTranslationY());
                    source.draw(rc);
                    rc.restore();
                }
            } finally {
                capturing = false;
                capture.endRecording();
            }
            if (w != effectW || h != effectH) {
                effectW = w;
                effectH = h;
                capture.setRenderEffect(effect(w, h, radius));
            }
            canvas.save();
            if (shader == null) {
                canvas.clipPath(clip);
            }
            canvas.translate(area.left - pad, area.top - pad);
            canvas.drawRenderNode(capture);
            canvas.restore();
        }

        private RenderEffect effect(int w, int h, float radius) {
            RenderEffect soften = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP);
            if (shader == null || Build.VERSION.SDK_INT < 33) {
                return soften;
            }
            return Lens33.chain(shader, soften, w, h, area.width() / 2f, area.height() / 2f,
                    radius, getResources().getDisplayMetrics().density);
        }
    }

    @RequiresApi(33)
    private static final class Lens33 {
        static RenderEffect chain(Object shaderObject, RenderEffect soften, int w, int h,
                float halfW, float halfH, float radius, float density) {
            RuntimeShader shader = (RuntimeShader) shaderObject;
            shader.setFloatUniform("size", w, h);
            shader.setFloatUniform("halfSize", halfW, halfH);
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("band", Math.min(radius, 16f * density));
            shader.setFloatUniform("depth", 10f * density);
            shader.setFloatUniform("chroma", 1f * density);
            return RenderEffect.createChainEffect(
                    RenderEffect.createRuntimeShaderEffect(shader, "content"), soften);
        }
    }
}
