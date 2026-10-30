package com.projetoros.launcher;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Base das telas: setas navegam pelas linhas (Nav), OK abre no soltar e OK segurado chama onLongOk.
 * O controle IR do HY300 repete o OK segurado como uma rajada de aperta-solta; tudo que chega com menos
 * de GAP_MS de intervalo é a mesma pressão. Depois que o menu abre, a rajada inteira é engolida até soltar.
 */
abstract class TvActivity extends Activity {
    private static final long LONG_MS = 800, GAP_MS = 250;
    /** Controle que repete o OK segurado em rajada; vira false ao ver uma repetição normal (aí o clique é imediato). */
    private static boolean bursty = true;
    protected final Handler ui = new Handler(Looper.getMainLooper());
    protected FrameLayout root;
    private View okTarget;
    private long okStart, okLast;
    private boolean okSwallow, okTried, okPending;
    private int swallowUp = -1;
    private TextView toast;

    /** Onde as setas navegam agora: a camada de cima, ou a tela. */
    protected abstract ViewGroup scope();

    /** OK segurado meio segundo no item focado; true se abriu algo. */
    protected boolean onLongOk(View v) {
        return false;
    }

    /** Seta antes da navegação normal (mover app, ajustar keystone); true se tratou. */
    protected boolean onArrow(View focused, int dx, int dy) {
        return false;
    }

    protected void onMenuKey() {
    }

    /** Qualquer tecla apertada; true engole a tecla (ex.: só acordar do descanso). */
    protected boolean onAnyKey() {
        return false;
    }

    protected View enterRow(ViewGroup row, View from) {
        return Nav.nearest(row, from);
    }

    protected void reveal(View v) {
        Nav.reveal(v);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (down && e.getRepeatCount() == 0 && onAnyKey()) {
            swallowUp = code;
            return true;
        }
        if (code == swallowUp) {
            if (!down) swallowUp = -1;
            return true;
        }
        View f = getCurrentFocus();
        boolean typing = f instanceof EditText;
        switch (code) {
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) onMenuKey();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) move(code == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (typing) break;
                if (down) move(0, code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (typing) break;
                ok(down, e, f);
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void ok(boolean down, KeyEvent e, View f) {
        long t = e.getEventTime();
        boolean same = t - okLast < GAP_MS;
        okLast = t;
        if (okSwallow) {
            if (same) return;
            okSwallow = false;
        }
        if (!down) {
            // controle em rajada: o clique espera um instante; se o OK voltar logo, era um OK segurado
            if (okTarget != null) {
                okPending = true;
                ui.postDelayed(okClick, bursty ? GAP_MS : 0);
            }
            return;
        }
        if (e.getRepeatCount() > 0) bursty = false;
        if (okPending && same) {
            ui.removeCallbacks(okClick);
            okPending = false;
        } else if (e.getRepeatCount() == 0 && !okPending) {
            okTarget = f;
            okStart = t;
            okTried = false;
        }
        if (okTarget != null && !okTried && t - okStart >= LONG_MS) {
            // só tenta uma vez; se não abriu nada, o soltar vira um clique normal
            okTried = true;
            if (onLongOk(okTarget)) {
                okTarget = null;
                okSwallow = true;
            }
        }
    }

    private final Runnable okClick = () -> {
        okPending = false;
        View v = okTarget;
        okTarget = null;
        if (v != null && v == getCurrentFocus()) v.performClick();
    };

    protected void move(int dRow, int dCol) {
        View cur = getCurrentFocus();
        if (cur != null && onArrow(cur, dCol, dRow)) return;
        List<ViewGroup> rs = Nav.rows(scope());
        if (rs.isEmpty()) return;
        int ri = -1;
        for (int i = 0; i < rs.size(); i++) if (Nav.inside(cur, rs.get(i))) ri = i;
        if (ri < 0) {
            focus(Nav.items(rs.get(0)).get(0));
            return;
        }
        if (dCol != 0) {
            List<View> it = Nav.items(rs.get(ri));
            focus(it.get((it.indexOf(cur) + dCol + it.size()) % it.size()));
            return;
        }
        ViewGroup next = rs.get((ri + dRow + rs.size()) % rs.size());
        if (next != rs.get(ri)) focus(enterRow(next, cur));
    }

    protected void focus(View v) {
        if (v == null) return;
        v.requestFocus();
        reveal(v);
    }

    protected void toast(String msg) {
        if (toast == null) {
            toast = Ui.text(this, "", 17, Ui.regular, Ui.text);
            toast.setBackground(Ui.round(Ui.surface, 12));
            toast.setPadding(Ui.px(22), Ui.px(12), Ui.px(22), Ui.px(12));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            lp.bottomMargin = Ui.px(36);
            root.addView(toast, lp);
        }
        toast.setText(msg);
        toast.setVisibility(View.VISIBLE);
        toast.bringToFront();
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 3000);
    }

    private final Runnable hideToast = () -> toast.setVisibility(View.GONE);
}
