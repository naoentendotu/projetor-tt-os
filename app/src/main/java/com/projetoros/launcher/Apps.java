package com.projetoros.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Apps instalados com capa 16:9, na ordem: HDMI, favoritos, depois o resto como a pessoa arrumou. */
final class Apps {
    static final class App {
        final String pkg, name;
        /** cover: 16:9 (banner de TV ou ícone sobre a cor dele); tile: 2:3 em pé, o ícone sobre a cor dele. */
        final Bitmap cover, tile;

        App(String pkg, String name, Bitmap[] art) {
            this.pkg = pkg;
            this.name = name;
            cover = art == null ? null : art[0];
            tile = art == null ? null : art[1];
        }
    }

    private static final int W = 320, H = 180, TW = 134, TH = 200;
    /** Capas já geradas, por pacote + caminho do APK (o caminho muda quando o app atualiza). */
    private static final Map<String, Bitmap[]> COVERS = new HashMap<>();

    final List<App> list = new ArrayList<>();
    /** Apps ocultos: saem da tela inicial e ficam no fim da aba Apps. */
    final List<App> hiddenList = new ArrayList<>();
    /** Outros apps que o firmware também chama de "HDMI" (o visualizador rockchip.camera2): viram o mesmo card. */
    final Set<String> hdmiAlias = new HashSet<>();
    String hdmi = "hdmi";
    private final SharedPreferences prefs;
    List<String> order, favs, hidden;

    Apps(Context c) {
        prefs = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        order = split(prefs.getString("order", ""));
        favs = split(prefs.getString("favs", ""));
        hidden = split(prefs.getString("hidden", ""));
    }

    private static List<String> split(String s) {
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    /** Lê os apps num Apps novo (roda fora da thread principal: a primeira vez gera as capas). */
    void load(Context c) {
        PackageManager pm = c.getPackageManager();
        List<App> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(c.getPackageName());
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                String pkg = r.activityInfo.packageName;
                if (!seen.add(pkg)) continue;
                String key = pkg + r.activityInfo.applicationInfo.sourceDir;
                Bitmap[] art;
                synchronized (COVERS) {
                    art = COVERS.get(key);
                    if (art == null) {
                        Drawable icon = r.loadIcon(pm);
                        int color = average(icon);
                        art = new Bitmap[]{cover(r.activityInfo.loadBanner(pm), icon, color), tile(icon, color)};
                        COVERS.put(key, art);
                    }
                }
                found.add(new App(pkg, r.loadLabel(pm).toString(), art));
            }
        }
        found.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        // HDMI sempre primeiro; se o firmware não expõe um app de HDMI, o card abre a entrada pelo Android
        App h = null;
        for (App a : found) if (h == null && (a.name + " " + a.pkg).toLowerCase().matches(".*(hdmi|tvd|source|fonte|entrada).*")) h = a;
        hdmiAlias.clear();
        if (h != null) {
            hdmi = h.pkg;
            for (App a : found) if (a != h && a.name.equalsIgnoreCase("hdmi")) hdmiAlias.add(a.pkg);
            found.removeIf(a -> hdmiAlias.contains(a.pkg));
        } else {
            hdmi = "hdmi";
            found.add(new App("hdmi", "HDMI", null));
        }
        found.sort((a, b) -> group(a) != group(b) ? group(a) - group(b) : pos(a.pkg) - pos(b.pkg));
        for (App a : found) (hidden.contains(a.pkg) ? hiddenList : list).add(a);
    }

    void toggleHidden(String pkg) {
        App a = find(pkg);
        if (hidden.remove(pkg)) {
            hiddenList.remove(a);
            list.add(a);
            list.sort((x, y) -> group(x) != group(y) ? group(x) - group(y) : pos(x.pkg) - pos(y.pkg));
        } else {
            hidden.add(pkg);
            list.remove(a);
            hiddenList.add(a);
        }
        prefs.edit().putString("hidden", String.join(",", hidden)).apply();
    }

    int group(App a) {
        return a.pkg.equals(hdmi) ? 0 : favs.contains(a.pkg) ? 1 : 2;
    }

    private int pos(String pkg) {
        int i = order.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    App find(String pkg) {
        if (hdmiAlias.contains(pkg)) pkg = hdmi;
        for (App a : list) if (a.pkg.equals(pkg)) return a;
        for (App a : hiddenList) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    void toggleFav(String pkg) {
        if (!favs.remove(pkg)) favs.add(pkg);
        prefs.edit().putString("favs", String.join(",", favs)).apply();
        list.sort((a, b) -> group(a) - group(b));
    }

    /** Troca o app com o vizinho, só dentro do mesmo grupo (HDMI, favoritos, resto). */
    boolean shift(String pkg, int d) {
        int i = list.indexOf(find(pkg)), j = i + d;
        if (i < 0 || j < 0 || j >= list.size() || group(list.get(i)) != group(list.get(j))) return false;
        list.set(i, list.set(j, list.get(i)));
        order.clear();
        for (App a : list) order.add(a.pkg);
        prefs.edit().putString("order", String.join(",", order)).apply();
        return true;
    }

    /** Capa que cobre o card inteiro: o banner de TV (já 16:9) ou o ícone grande sobre a cor média dele. */
    private static Bitmap cover(Drawable banner, Drawable icon, int color) {
        Bitmap b = Bitmap.createBitmap(W, H, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        if (banner != null) {
            banner.setBounds(0, 0, W, H);
            banner.draw(c);
        } else {
            c.drawColor(color);
            int s = 116;
            icon.setBounds((W - s) / 2, (H - s) / 2, (W + s) / 2, (H + s) / 2);
            icon.draw(c);
        }
        return b;
    }

    /** Tile em pé (mesmo formato das capas de filme): ícone no centro sobre a cor média dele. */
    private static Bitmap tile(Drawable icon, int color) {
        Bitmap b = Bitmap.createBitmap(TW, TH, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(b);
        c.drawColor(color);
        int s = 84;
        icon.setBounds((TW - s) / 2, (TH - s) / 2, (TW + s) / 2, (TH + s) / 2);
        icon.draw(c);
        return b;
    }

    private static int average(Drawable d) {
        Bitmap s = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 16, 16);
        d.draw(new Canvas(s));
        long r = 0, g = 0, bl = 0, n = 0;
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                int p = s.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                r += Color.red(p);
                g += Color.green(p);
                bl += Color.blue(p);
                n++;
            }
        }
        s.recycle();
        if (n == 0) return 0xff1e2a36;
        // escurecida para o ícone se destacar e o card não brilhar demais na projeção
        return Color.rgb((int) (r / n * 6 / 10), (int) (g / n * 6 / 10), (int) (bl / n * 6 / 10));
    }
}
