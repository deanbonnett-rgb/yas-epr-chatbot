package com.emp.predictor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A small panel that floats over other apps (such as The National Lottery app) showing the
 * generated lines. Ticket apps take one number per box, so tapping a number here copies just that
 * number and ticks it off; the panel can be dragged out of the way and closed with ✕.
 * Needs the "Display over other apps" permission.
 */
public final class FloatingNumbers {
    private static final int BG = Color.parseColor("#0B1437");
    private static final int CARD = Color.parseColor("#17245A");
    private static final int MUTED = Color.parseColor("#AAB4D4");

    private static FloatingNumbers showing;

    private final Context context;
    private final WindowManager wm;
    private final Game game;
    private final List<Predictor.Line> lines;
    /** Numbers already copied, per line ("m12" / "e3"), so the player can see where they're up to. */
    private final List<Set<String>> copied = new ArrayList<>();
    private int index;
    private LinearLayout root;
    private WindowManager.LayoutParams params;

    private FloatingNumbers(Context context, Game game, List<Predictor.Line> lines, int index) {
        this.context = context.getApplicationContext();
        this.wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.game = game;
        this.lines = new ArrayList<>(lines);
        this.index = index;
        for (int i = 0; i < lines.size(); i++) copied.add(new HashSet<String>());
    }

    public static boolean canShow(Context context) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    /** Shows the panel for {@code lines}, starting at {@code index}, replacing any panel already showing. */
    public static void show(Context context, Game game, List<Predictor.Line> lines, int index) {
        hide();
        showing = new FloatingNumbers(context, game, lines, index);
        showing.attach();
    }

    public static void hide() {
        if (showing != null) {
            try {
                showing.wm.removeView(showing.root);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            showing = null;
        }
    }

    public static boolean isShowing() {
        return showing != null;
    }

    private void attach() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), game.accent);
        root.setBackground(bg);
        root.setElevation(dp(8));

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // Not focusable, so the ticket app underneath keeps its keyboard and text boxes.
        params = new WindowManager.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(8);
        params.y = dp(160);
        render();
        wm.addView(root, params);
    }

    private void render() {
        root.removeAllViews();
        final Predictor.Line line = lines.get(index);
        final Set<String> done = copied.get(index);

        LinearLayout head = new LinearLayout(context);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label(game.name + (lines.size() > 1 ? "  ·  Line " + (index + 1) + " of " + lines.size() : ""), 14, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setOnTouchListener(new DragListener());
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (lines.size() > 1) {
            head.addView(headButton("‹", new View.OnClickListener() {
                @Override public void onClick(View v) { index = (index + lines.size() - 1) % lines.size(); render(); }
            }));
            head.addView(headButton("›", new View.OnClickListener() {
                @Override public void onClick(View v) { index = (index + 1) % lines.size(); render(); }
            }));
        }
        head.addView(headButton("✕", new View.OnClickListener() {
            @Override public void onClick(View v) { hide(); }
        }));
        root.addView(head);

        root.addView(row(line.main, false, done), wrap(dp(6)));
        if (line.extra.length > 0) {
            root.addView(label(game.extraName, 12, MUTED), wrap(dp(6)));
            root.addView(row(line.extra, true, done), wrap(dp(2)));
        }
        String hint = done.size() == line.main.length + line.extra.length
                ? "All copied ✓ – use › for the next line"
                : "Tap a number to copy it, then paste it into the next box. Drag the title to move.";
        TextView h = label(hint, 11, MUTED);
        h.setMaxWidth(dp(300));
        root.addView(h, wrap(dp(6)));
    }

    private LinearLayout row(int[] numbers, final boolean extra, final Set<String> done) {
        LinearLayout r = new LinearLayout(context);
        for (final int n : numbers) {
            final String key = (extra ? "e" : "m") + n;
            boolean isDone = done.contains(key);
            TextView b = label(String.valueOf(n), 17, extra ? Color.WHITE : BG);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(extra ? game.extraColor : Color.WHITE);
            b.setBackground(g);
            b.setAlpha(isDone ? 0.35f : 1f);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText(game.name + " number", String.valueOf(n)));
                    Toast.makeText(context, n + " copied", Toast.LENGTH_SHORT).show();
                    done.add(key);
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(6);
            r.addView(b, lp);
        }
        return r;
    }

    private TextView headButton(String s, View.OnClickListener click) {
        TextView t = label(s, 18, Color.WHITE);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(8));
        t.setBackground(g);
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(34));
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView label(String s, int sp, int color) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams wrap(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    /** Moves the panel while the title is dragged. */
    private final class DragListener implements View.OnTouchListener {
        private float startX, startY;
        private int originX, originY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = e.getRawX();
                    startY = e.getRawY();
                    originX = params.x;
                    originY = params.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = originX + Math.round(e.getRawX() - startX);
                    params.y = originY + Math.round(e.getRawY() - startY);
                    wm.updateViewLayout(root, params);
                    return true;
                default:
                    return false;
            }
        }
    }
}
