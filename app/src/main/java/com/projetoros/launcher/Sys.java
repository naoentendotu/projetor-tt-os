package com.projetoros.launcher;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.audiofx.DynamicsProcessing;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Tudo que mexe no sistema: abrir apps e telas, áudio, Bluetooth e os comandos pelo shell local. */
final class Sys {
    private static final int KEYSTONE_STEP = 4;
    private static final String KS = "persist.sys.keystone.";
    private static Sys instance;

    interface Done {
        /** out é a saída do comando, ou a mensagem de erro quando ok é false. */
        void done(boolean ok, String out);
    }

    static final class Status {
        int temp;
        boolean net, night;
        String out = "";
    }

    final Context app;
    final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService shell = Executors.newSingleThreadExecutor();
    private final AdbSelf adb;
    private DynamicsProcessing night;
    private BluetoothA2dp a2dp;

    static Sys get(Context c) {
        if (instance == null) instance = new Sys(c.getApplicationContext());
        return instance;
    }

    private Sys(Context app) {
        this.app = app;
        prefs = app.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        adb = new AdbSelf(app.getFilesDir(), () -> toast("Autorize o Projetor OS na janela do Android (marque \"Sempre permitir\")"));
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt != null) {
            bt.getProfileProxy(app, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    a2dp = (BluetoothA2dp) proxy;
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    a2dp = null;
                }
            }, BluetoothProfile.A2DP);
        }
        if (KEYS && prefs.getBoolean("night", false)) night(true);
    }

    /** Este é o processo :keys (KeyService), o único que o firmware não derruba ao trocar de app. */
    static final boolean KEYS = android.app.Application.getProcessName().endsWith(":keys");
    static final String ACTION_NIGHT = "com.projetoros.launcher.NIGHT";

    void toast(String msg) {
        main.post(() -> Toast.makeText(app, msg, Toast.LENGTH_LONG).show());
    }

    private void start(Intent i, String failMsg) {
        try {
            app.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast(failMsg);
        }
    }

    void open(String pkg) {
        if ("hdmi".equals(pkg)) {
            hdmi();
            return;
        }
        PackageManager pm = app.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i != null) start(i, "Não deu para abrir o app");
    }

    /** Tela do sistema do app: informações, limpar dados, desinstalar. */
    void appInfo(String pkg) {
        start(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)), "Não deu para abrir as informações");
    }

    void settings(String action) {
        try {
            app.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            start(new Intent(Settings.ACTION_SETTINGS), "Configurações indisponíveis");
        }
    }

    /** Manda o vídeo do Jellyfin para o player instalado; "position" é lido por VLC, MX Player e outros. */
    void play(String url, String title, long positionMs) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(Uri.parse(url), "video/*");
        i.putExtra("title", title);
        i.putExtra("position", (int) positionMs);
        start(i, "Nenhum player de vídeo instalado");
    }

    /** Abre o item no app Jellyfin para TV (tela do filme/série, já no servidor logado); sem o app, toca no player. */
    void jellyfin(String itemId, String url, String title) {
        PackageManager pm = app.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage("org.jellyfin.androidtv");
        if (i == null) i = pm.getLaunchIntentForPackage("org.jellyfin.androidtv");
        if (i == null) play(url, title, 0);
        // "ItemId" é o extra que a StartupActivity do Jellyfin lê para abrir direto no item
        else start(i.putExtra("ItemId", itemId), "Não deu para abrir o Jellyfin");
    }

    /** Entrada HDMI pelo framework de TV do Android; usado quando o firmware não tem um app de HDMI na lista. */
    private void hdmi() {
        TvInputManager tv = (TvInputManager) app.getSystemService(Context.TV_INPUT_SERVICE);
        if (tv != null) {
            for (TvInputInfo in : tv.getTvInputList()) {
                if (in.getType() == TvInputInfo.TYPE_HDMI) {
                    start(new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.getId())), "Não deu para abrir o HDMI");
                    return;
                }
            }
        }
        toast("Entrada HDMI não encontrada");
    }

    /** Temperatura, rede e áudio, para o painel rápido e o descanso. */
    Status status() {
        Status s = new Status();
        try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/thermal/thermal_zone0/temp"))) {
            s.temp = Integer.parseInt(r.readLine().trim()) / 1000;
        } catch (IOException | RuntimeException ignored) {
        }
        NetworkInfo n = ((ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
        s.net = n != null && n.isConnected();
        s.night = KEYS ? night != null : prefs.getBoolean("night", false);
        if (a2dp != null) {
            List<BluetoothDevice> on = a2dp.getConnectedDevices();
            if (!on.isEmpty()) s.out = on.get(0).getName();
        }
        return s;
    }

    static String statusLine(Status s) {
        return (s.temp > 0 ? s.temp + " °C  ·  " : "") + (s.net ? "Wi-Fi conectado" : "Sem rede");
    }

    /** Modo noturno: limitador no mix geral (sessão 0) segura os picos e o ganho depois sobe os diálogos. */
    boolean night(boolean on) {
        if (!KEYS) {
            // fora do :keys o efeito morreria junto com o launcher: só pede para o KeyService aplicar
            prefs.edit().putBoolean("night", on).apply();
            app.sendBroadcast(new Intent(ACTION_NIGHT).setPackage(app.getPackageName()).putExtra("on", on));
            return on;
        }
        try {
            if (on && night == null) {
                DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, false, 0, false, 0, true)
                        .setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(true, true, 0, 1f, 80f, 10f, -24f, 12f))
                        .build();
                night = new DynamicsProcessing(0, 0, cfg);
                night.setEnabled(true);
            } else if (!on && night != null) {
                night.release();
                night = null;
            }
        } catch (RuntimeException e) {
            night = null;
            toast("Modo noturno não é suportado neste aparelho");
        }
        prefs.edit().putBoolean("night", night != null).apply();
        return night != null;
    }

    /** Alterna entre alto-falante e o primeiro fone/caixa pareado; se o Android não deixar, abre o Bluetooth. */
    void toggleOut() {
        try {
            List<BluetoothDevice> on = a2dp.getConnectedDevices();
            if (!on.isEmpty()) {
                BluetoothA2dp.class.getMethod("disconnect", BluetoothDevice.class).invoke(a2dp, on.get(0));
                return;
            }
            for (BluetoothDevice d : BluetoothAdapter.getDefaultAdapter().getBondedDevices()) {
                BluetoothClass c = d.getBluetoothClass();
                if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                    BluetoothA2dp.class.getMethod("connect", BluetoothDevice.class).invoke(a2dp, d);
                    return;
                }
            }
            toast("Nenhum fone ou caixa de som pareado");
            settings(Settings.ACTION_BLUETOOTH_SETTINGS);
        } catch (Exception e) {
            settings(Settings.ACTION_BLUETOOTH_SETTINGS);
        }
    }

    /** Comandos de sistema (só esta lista fixa) pelo shell local; a resposta volta na thread principal. */
    void sh(String action, String arg, Done done) {
        shell.execute(() -> {
            String out;
            boolean ok = true;
            try {
                out = run(action, arg);
            } catch (Exception e) {
                ok = false;
                out = "AUTH".equals(e.getMessage()) ? "Autorize o Projetor OS na janela do Android e tente de novo" : "Não deu certo: " + e.getMessage();
            }
            boolean fOk = ok;
            String fOut = out;
            if (done != null) main.post(() -> done.done(fOk, fOut));
            else if (!ok) toast(out);
        });
    }

    private String run(String action, String arg) throws IOException {
        switch (action) {
            case "stop":
                if (arg == null || !arg.matches("[\\w.]+")) throw new IOException("pacote inválido");
                return adb.shell("am force-stop " + arg);
            case "recents": {
                Set<String> pkgs = new LinkedHashSet<>();
                for (String line : adb.shell("dumpsys activity recents").split("\n")) {
                    line = line.trim();
                    if (line.startsWith("realActivity=") && !line.contains("FallbackHome")) pkgs.add(line.substring(13).split("/")[0]);
                }
                return String.join("\n", pkgs);
            }
            case "clean": {
                // kill-all derruba tudo que está em segundo plano: espera o painel fechar para o app de baixo
                // (HDMI, vídeo) voltar à frente e não ser derrubado junto
                String[] kb = adb.shell("sleep 2; grep MemAvailable /proc/meminfo; am kill-all; pm trim-caches 999G; sleep 2; grep MemAvailable /proc/meminfo")
                        .replaceAll("[^0-9\n]", "").trim().split("\n+");
                long mb = (Long.parseLong(kb[kb.length - 1]) - Long.parseLong(kb[0])) / 1024;
                toast(mb > 0 ? mb + " MB liberados" : "Memória liberada");
                return "";
            }
            case "keystone":
                return keystone(arg);
            case "home":
                // o firmware derruba a tela inicial quando outro app abre e o Android esquece a preferência
                return adb.shell("cmd package set-home-activity " + app.getPackageName() + "/.MainActivity");
            case "wifi":
                adb.shell("setsid sh -c 'svc wifi disable; sleep 3; svc wifi enable' </dev/null >/dev/null 2>&1 &");
                return "";
            case "remote":
                // conexões ADB vindas de outro aparelho (o app do celular), sem contar a do próprio launcher
                return adb.shell("grep -hE ':15B3 [0-9A-F]+:[0-9A-F]+ 01' /proc/net/tcp /proc/net/tcp6 | grep -vc '0100007F:[0-9A-F]* 01'").trim();
            default:
                throw new IOException("ação desconhecida");
        }
    }

    /**
     * Keystone do HY300: o SurfaceFlinger lê os 4 cantos (deslocamento x,y em px) de persist.sys.keystone.* quando
     * persist.sys.keystone.update vira 1. arg: v+/v- (trapézio vertical), h+/h- (horizontal), z+/z- (zoom) ou reset.
     */
    private String keystone(String arg) throws IOException {
        String[] corner = {"lt", "rt", "lb", "rb"};
        StringBuilder cmd = new StringBuilder();
        if ("reset".equals(arg)) {
            for (String c : corner) cmd.append("setprop ").append(KS).append(c).append(" $(getprop ").append(KS).append(c).append(".init.xy); ");
        } else {
            if (arg == null || !arg.matches("[vhz][+-]")) throw new IOException("ajuste inválido");
            String[] v = adb.shell("getprop " + KS + "lt; getprop " + KS + "rt; getprop " + KS + "lb; getprop " + KS + "rb").trim().split("\\s+");
            // para dentro da imagem, cada canto anda neste sentido: lt(+x,-y) rt(-x,-y) lb(+x,+y) rb(-x,+y)
            int[][] in = {{1, -1}, {-1, -1}, {1, 1}, {-1, 1}};
            int[][] p = new int[4][2];
            try {
                for (int i = 0; i < 4; i++) {
                    String[] xy = v[i].split(",");
                    p[i][0] = Integer.parseInt(xy[0].trim()) * in[i][0];
                    p[i][1] = Integer.parseInt(xy[1].trim()) * in[i][1];
                }
            } catch (RuntimeException e) {
                throw new IOException("keystone não encontrado neste firmware");
            }
            int d = arg.charAt(1) == '+' ? KEYSTONE_STEP : -KEYSTONE_STEP;
            switch (arg.charAt(0)) {
                case 'v': // + estreita o topo
                    p[0][0] += d;
                    p[1][0] += d;
                    break;
                case 'h': // + encurta o lado esquerdo
                    p[0][1] += d;
                    p[2][1] += d;
                    break;
                default: // z+: imagem maior, todos os cantos para fora (16:9)
                    for (int[] c : p) {
                        c[0] -= d * 16 / 9;
                        c[1] -= d;
                    }
            }
            for (int i = 0; i < 4; i++) {
                int x = Math.max(0, Math.min(400, p[i][0])) * in[i][0], y = Math.max(0, Math.min(250, p[i][1])) * in[i][1];
                cmd.append("setprop ").append(KS).append(corner[i]).append(' ').append(x).append(',').append(y).append("; ");
            }
        }
        adb.shell(cmd + "setprop " + KS + "update 1");
        return "";
    }
}
