package com.projetoros.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Toca um item do Jellyfin no player do próprio app Jellyfin para TV: abre o app, espera a sessão dele
 * reconectar no servidor e manda "tocar agora" pelo controle remoto de sessão do Jellyfin.
 * Roda no processo :keys porque o firmware derruba o launcher assim que o Jellyfin abre.
 */
public class PlayService extends Service {
    static final String JELLYFIN_TV = "org.jellyfin.androidtv";

    @Override
    public int onStartCommand(Intent i, int flags, int startId) {
        String server = i.getStringExtra("server"), auth = i.getStringExtra("auth"), user = i.getStringExtra("user"), item = i.getStringExtra("item");
        long ticks = i.getLongExtra("ticks", 0);
        new Thread(() -> {
            try {
                play(server, auth, user, item, ticks);
            } catch (IOException | RuntimeException e) {
                toast("Não deu para tocar no Jellyfin: " + e.getMessage());
            }
            stopSelf(startId);
        }).start();
        return START_NOT_STICKY;
    }

    private void play(String server, String auth, String user, String item, long ticks) throws IOException {
        // a sessão antiga continua listada no servidor mesmo com o app fechado: espera ela "mexer" depois de abrir
        JSONObject old = session(server, auth, user);
        String before = old == null ? "" : old.optString("LastActivityDate");
        Intent app = getPackageManager().getLeanbackLaunchIntentForPackage(JELLYFIN_TV);
        if (app == null) app = getPackageManager().getLaunchIntentForPackage(JELLYFIN_TV);
        startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        JSONObject s = null;
        for (int t = 0; t < 25 && s == null; t++) {
            sleep(1000);
            JSONObject now = session(server, auth, user);
            if (now != null && !before.equals(now.optString("LastActivityDate"))) s = now;
        }
        if (s == null) s = session(server, auth, user);
        if (s == null) throw new IOException("o app Jellyfin não conectou ao servidor");
        sleep(800); // a tela inicial do app termina de abrir antes do player
        HttpURLConnection c = open(server + "/Sessions/" + s.optString("Id") + "/Playing?playCommand=PlayNow&itemIds=" + item + "&startPositionTicks=" + ticks, auth);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.getOutputStream().close();
        if (c.getResponseCode() >= 300) throw new IOException("o servidor respondeu " + c.getResponseCode());
    }

    /** Sessão do Jellyfin para TV deste aparelho (mesmo modelo) que aceita controle remoto, ou null. */
    private JSONObject session(String server, String auth, String user) throws IOException {
        HttpURLConnection c = open(server + "/Sessions?ControllableByUserId=" + user, auth);
        JSONArray list;
        try (InputStream in = c.getInputStream()) {
            list = new JSONArray(read(in));
        } catch (org.json.JSONException e) {
            throw new IOException("resposta estranha do servidor");
        }
        for (int k = 0; k < list.length(); k++) {
            JSONObject s = list.optJSONObject(k);
            if (!s.optString("Client").contains("Android TV") || !s.optBoolean("SupportsRemoteControl")) continue;
            if (s.optString("DeviceName").equalsIgnoreCase(Build.MODEL) || s.optString("DeviceName").equalsIgnoreCase(Build.DEVICE)) return s;
        }
        return null;
    }

    private static HttpURLConnection open(String url, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", auth);
        return c;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void toast(String msg) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
