package com.projetoros.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serviço de acessibilidade em processo próprio (o firmware não o derruba):
 * - Menu no controle abre o painel rápido por cima de qualquer app, como camada (não como tela: o firmware
 *   encerra o app da frente sempre que uma tela nova abre, e isso derrubaria o vídeo);
 * - Home abre sempre este launcher;
 * - ao sair de um app, encerra o app (libera RAM);
 * - ao sair do launcher, refaz a preferência de tela inicial (o firmware a apaga ao derrubar o launcher);
 * - guarda o efeito do modo noturno, que assim sobrevive às trocas de app.
 */
public class KeyService extends AccessibilityService {
    /** Janelas do sistema que aparecem por cima de um app sem que a pessoa tenha saído dele. */
    private static final Set<String> SYSTEM = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.inputmethod.latin"));

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private AdbSelf adb;
    private CharSequence front = "";
    private QuickPanel panel;

    private final BroadcastReceiver nightReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Sys.get(c).night(i.getBooleanExtra("on", false));
        }
    };

    @Override
    protected void onServiceConnected() {
        // mesma chave já autorizada do launcher (arquivos do app são compartilhados entre os processos)
        adb = new AdbSelf(getFilesDir(), () -> {
        });
        Sys.get(this); // reaplica o modo noturno salvo
        registerReceiver(nightReceiver, new IntentFilter(Sys.ACTION_NIGHT));
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(nightReceiver);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String prev = front.toString(), now = e.getPackageName().toString();
        // a camada do painel também gera este evento: não é troca de app (senão o app de baixo seria encerrado)
        if (prev.equals(now) || (panel != null && now.equals(getPackageName()))) return;
        front = now;
        if (panel != null && !now.equals(getPackageName())) closePanel();
        if (prev.equals(getPackageName())) main.postDelayed(this::reassertHome, 2000);
        // saiu de um app para a tela inicial ou para outro app: encerra o anterior para liberar RAM
        else if (!prev.isEmpty() && !SYSTEM.contains(prev) && !SYSTEM.contains(now)) main.postDelayed(() -> stop(prev), 1500);
    }

    private void stop(String pkg) {
        if (adb == null || pkg.contentEquals(front)) return; // voltou para ele nesse meio tempo
        bg.execute(() -> {
            try {
                adb.shell("am force-stop " + pkg);
            } catch (IOException ignored) {
            }
        });
    }

    private void reassertHome() {
        if (adb == null) return;
        bg.execute(() -> {
            try {
                adb.shell("cmd package set-home-activity " + getPackageName() + "/.MainActivity");
            } catch (IOException ignored) {
                // sem a autorização do ADB fica como estava; o Home do controle continua funcionando
            }
        });
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        if (code == KeyEvent.KEYCODE_HOME) {
            if (down) {
                closePanel();
                startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (panel != null) return panelKey(e);
        // dentro do launcher a própria tela trata o Menu
        if (code != KeyEvent.KEYCODE_MENU || getPackageName().contentEquals(front)) return false;
        if (down) openPanel();
        return true;
    }

    /* ---------- painel rápido como camada por cima do app ---------- */

    private void openPanel() {
        Ui.init(this);
        panel = new QuickPanel(this, this::closePanel, true);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(Ui.px(QuickPanel.WIDTH), ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // sem foco de janela: o app de baixo continua sendo o da frente; as teclas chegam por onKeyEvent
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(panel, lp);
        panel.setTranslationX(Ui.px(QuickPanel.WIDTH));
        panel.animate().translationX(0).setDuration(Ui.ANIM_MS).setInterpolator(Ui.EASE).start();
        panel.refresh();
        panel.first().requestFocus();
    }

    private void closePanel() {
        if (panel == null) return;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(panel);
        panel = null;
    }

    /** Com o painel aberto, o controle navega nele: ↑ ↓ linhas (cíclico), ← → ajustes, OK, Voltar/Menu fecham. */
    private boolean panelKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
        View f = panel.findFocus();
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                List<ViewGroup> rows = Nav.rows(panel);
                int i = 0;
                for (int k = 0; k < rows.size(); k++) if (Nav.inside(f, rows.get(k))) i = k;
                int d = e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                Nav.items(rows.get((i + d + rows.size()) % rows.size())).get(0).requestFocus();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (f != null) panel.arrow(f, e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0 && f != null) f.performClick();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                closePanel();
                return true;
            default:
                return false; // volume etc. continuam funcionando
        }
    }
}
