package com.projetoros.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cliente mínimo do Jellyfin (só o que a tela inicial usa) e o carregador das imagens. */
final class Jellyfin {
    interface Done {
        /** result é JSONObject/JSONArray, ou null com o erro em err. */
        void done(Object result, String err);
    }

    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> IMAGES = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) {
            return b.getByteCount();
        }
    };

    private final SharedPreferences prefs;
    private final String deviceId;
    String server, token, userId, user;
    /** Chamado quando o servidor diz que a sessão expirou. */
    Runnable onLogout;

    Jellyfin(Context c) {
        prefs = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        String id = prefs.getString("deviceId", null);
        if (id == null) prefs.edit().putString("deviceId", id = UUID.randomUUID().toString()).apply();
        deviceId = id;
        try {
            JSONObject j = new JSONObject(prefs.getString("jf", "{}"));
            server = j.optString("server", null);
            token = j.optString("token", null);
            userId = j.optString("userId", null);
            user = j.optString("user", null);
        } catch (JSONException ignored) {
        }
    }

    boolean connected() {
        return token != null;
    }

    String auth() {
        return "MediaBrowser Client=\"Projetor OS\", Device=\"HY300\", DeviceId=\"" + deviceId + "\", Version=\"2.0\"" + (token != null ? ", Token=\"" + token + "\"" : "");
    }

    void get(String path, Done done) {
        request(server + path, null, done);
    }

    private void request(String url, String body, Done done) {
        String auth = auth(); // agora: o logout zera o token logo depois de pedir
        IO.execute(() -> {
            Object res = null;
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(15000);
                c.setRequestProperty("Authorization", auth);
                c.setRequestProperty("Content-Type", "application/json");
                if (body != null) {
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    try (OutputStream o = c.getOutputStream()) {
                        o.write(body.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                if (code == 401) {
                    err = token != null ? "Sessão do Jellyfin expirou" : "Usuário ou senha incorretos";
                    if (token != null) MAIN.post(this::logout);
                } else if (code >= 300) {
                    err = "O servidor respondeu " + code;
                } else if (code != 204) {
                    try (InputStream in = c.getInputStream()) {
                        res = new JSONTokener(read(in)).nextValue();
                    }
                }
            } catch (IOException e) {
                err = "Não achei o servidor nesse endereço";
            } catch (JSONException e) {
                err = "Resposta estranha do servidor";
            }
            Object fRes = res;
            String fErr = err;
            MAIN.post(() -> done.done(fRes, fErr));
        });
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    void login(String server, String name, String pass, Done done) {
        token = null;
        JSONObject body = new JSONObject();
        try {
            body.put("Username", name).put("Pw", pass);
        } catch (JSONException ignored) {
        }
        request(server + "/Users/AuthenticateByName", body.toString(), (res, err) -> {
            if (res instanceof JSONObject) {
                JSONObject d = (JSONObject) res;
                this.server = server;
                token = d.optString("AccessToken");
                JSONObject u = d.optJSONObject("User");
                userId = u == null ? "" : u.optString("Id");
                user = u == null ? name : u.optString("Name");
                // guarda só o token, nunca a senha
                JSONObject j = new JSONObject();
                try {
                    j.put("server", server).put("token", token).put("userId", userId).put("user", user);
                } catch (JSONException ignored) {
                }
                prefs.edit().putString("jf", j.toString()).putString("lastServer", server).apply();
            }
            done.done(res, err);
        });
    }

    void logout() {
        if (token != null) request(server + "/Sessions/Logout", "", (r, e) -> {
        });
        token = null;
        prefs.edit().remove("jf").apply();
        if (onLogout != null) onLogout.run();
    }

    String lastServer() {
        return server != null ? server : prefs.getString("lastServer", "");
    }

    String image(String id, String type, int w) {
        return server + "/Items/" + Uri.encode(id) + "/Images/" + type + "?maxWidth=" + w;
    }

    String backdrop(JSONObject it, int w) {
        if (it.optJSONArray("BackdropImageTags") != null && it.optJSONArray("BackdropImageTags").length() > 0) return image(it.optString("Id"), "Backdrop", w);
        if (it.has("ParentBackdropItemId")) return image(it.optString("ParentBackdropItemId"), "Backdrop", w);
        return image(it.optString("Id"), "Primary", w);
    }

    /** Capa vertical (pôster): episódio usa a da série, que é vertical; a do episódio é uma cena horizontal. */
    String poster(JSONObject it, int w) {
        if (episode(it) && it.has("SeriesId")) return image(it.optString("SeriesId"), "Primary", w);
        return image(it.optString("Id"), "Primary", w);
    }

    String stream(String id) {
        return server + "/Videos/" + Uri.encode(id) + "/stream?static=true&api_key=" + token;
    }

    static boolean episode(JSONObject it) {
        return "Episode".equals(it.optString("Type"));
    }

    static String title(JSONObject it) {
        return episode(it) ? it.optString("SeriesName") : it.optString("Name");
    }

    static String subtitle(JSONObject it) {
        if (episode(it)) return "T" + it.optInt("ParentIndexNumber") + ":E" + it.optInt("IndexNumber") + " - " + it.optString("Name");
        return it.has("ProductionYear") ? String.valueOf(it.optInt("ProductionYear")) : "";
    }

    static String runtime(long ticks) {
        long min = Math.round(ticks / 6e8);
        return min >= 60 ? min / 60 + "h " + min % 60 + "m" : min + "m";
    }

    /** Põe a imagem da URL no ImageView (com cache); ignora a resposta se a view já pediu outra imagem. */
    static void load(String url, ImageView v) {
        v.setTag(url);
        if (url == null) {
            v.setImageDrawable(null);
            return;
        }
        Bitmap cached = IMAGES.get(url);
        if (cached != null) {
            v.setImageBitmap(cached);
            return;
        }
        v.setImageDrawable(null);
        IO.execute(() -> {
            Bitmap b = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(15000);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                try (InputStream in = c.getInputStream()) {
                    b = BitmapFactory.decodeStream(in, null, o);
                }
            } catch (IOException ignored) {
            }
            if (b == null) return;
            IMAGES.put(url, b);
            Bitmap fb = b;
            MAIN.post(() -> {
                if (url.equals(v.getTag())) v.setImageBitmap(fb);
            });
        });
    }
}
