package com.projetoros.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serviço de acessibilidade em processo próprio (o firmware não o derruba):
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
    /** Navegador e WebView: login (OAuth) abre aqui e volta para o app; encerrar o app nessa troca quebra o login. */
    private static final Set<String> BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome", "com.google.android.webview", "com.android.webview", "com.android.browser"));

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private AdbSelf adb;
    private CharSequence front = "";

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
        if (prev.equals(now)) return;
        front = now;
        if (prev.equals(getPackageName())) main.postDelayed(this::reassertHome, 2000);
        // saiu de um app para a tela inicial ou para outro app: encerra o anterior para liberar RAM
        else if (!prev.isEmpty() && !SYSTEM.contains(prev) && !SYSTEM.contains(now) && !BROWSERS.contains(now)) main.postDelayed(() -> stop(prev), 1500);
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
            if (down) startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        }
        return false;
    }
}
