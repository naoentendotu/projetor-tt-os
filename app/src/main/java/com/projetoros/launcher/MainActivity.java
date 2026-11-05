package com.projetoros.launcher;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Tela inicial nativa: abas Início / Apps / Projetor, destaque do Jellyfin, prateleiras e as camadas por cima. */
public class MainActivity extends TvActivity {
    private static final long IDLE_MS = 10 * 60 * 1000;
    /** Cards de mídia: altura e largura parados (pôster); focado vira 16:9. */
    private static final int MEDIA_H = 200, MEDIA_NARROW = 134;
    private static final Locale PT = new Locale("pt", "BR");
    private static final File BACKUP = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "projetor-os-backup.json");
    private static final File PICTURES = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

    private Sys sys;
    private Jellyfin jf;
    private Apps apps;
    private SharedPreferences prefs;
    private String appsSig = "", current = "home", moving;
    private long lastHome, lastBack;
    private final Map<View, String> cardPkg = new HashMap<>();

    private ImageView wallpaper, backdrop;
    private GradientDrawable tint;
    private Ui.VScroll page;
    private FrameLayout top, menuLayer, loginLayer, dim;
    private LinearLayout homeView, appsView, projView, hero, heroChips, resumeBlock, resumeRow, appShelf, latestBlock, latestRow, appsGrid;
    private LinearLayout menuItems, recentsLayer, recentCards, recentClose;
    private TextView[] tabs;
    private TextView heroLabel, heroTitle, heroText, heroPlay, heroNext, clock, menuTitle, loginErr, bigClock, bigDate, bigInfo;
    private TextView jfAccount;
    private EditText jfServer, jfUser, jfPass;
    /** Onde o foco volta ao fechar o menu (card do app ou linha do ajuste). */
    private View menuReturn;

    private static final class Hero {
        String label, title, text, image, button;
        final List<String> chips = new ArrayList<>();
        boolean play;
        Runnable action;
    }

    private final List<Hero> heroes = new ArrayList<>();
    private int heroIdx;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        sys = Sys.get(this);
        prefs = sys.prefs;
        jf = new Jellyfin(this);
        jf.onLogout = this::refreshJellyfin;
        apps = new Apps(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.bg);
        buildBackground();
        buildPage();
        buildTop();
        buildLayers();
        setContentView(root);
        show("home");
        tick.run();
        ui.postDelayed(rotateHero, 15000);
        if (!"preto".equals(Ui.theme)) ui.postDelayed(tintCycle, 20000);
        heroPlay.requestFocus();
        if (settingsFocus >= 0 && settingsFocus < settingRows.size()) {
            show("proj");
            View row = settingRows.get(settingsFocus);
            row.requestFocus();
            row.post(() -> reveal(row));
        }
        settingsFocus = -1;
        openOnBoot();
        sys.sh("home", "", (ok, o) -> {
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideDim();
        loadApps();
        refreshJellyfin();
    }

    // Home chega aqui (o launcher é singleTask): um toque fecha as camadas, dois toques rápidos abrem os recentes.
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        hideDim();
        long now = SystemClock.uptimeMillis();
        if (now - lastHome < 600) openRecents();
        else closeLayers();
        lastHome = now;
    }

    /* ---------- montagem ---------- */

    private void buildBackground() {
        wallpaper = new ImageView(this);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wallpaper, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        loadWallpaper();
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(backdrop, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        if (!"preto".equals(Ui.theme)) {
            // brilho no canto que muda de tom de tempos em tempos (alfa já embutido na cor: sem camada extra)
            tint = new GradientDrawable();
            tint.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            tint.setGradientCenter(.8f, .1f);
            tint.setGradientRadius(Ui.px(820));
            tint.setColors(new int[]{(Ui.accent & 0xffffff) | 0x66000000, 0});
            View t = new View(this);
            t.setBackground(tint);
            root.addView(t, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }
        int clear = Ui.bg & 0xffffff;
        View shade = new View(this);
        shade.setBackground(new LayerDrawable(new GradientDrawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{Ui.bg, clear | 0xb3000000, 0x40000000, 0, 0}),
                new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x73000000, 0, 0, Ui.bg, Ui.bg})}));
        root.addView(shade, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
    }

    private void buildPage() {
        page = new Ui.VScroll(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.px(Ui.PAGE), 0, Ui.px(Ui.PAGE), Ui.px(40));
        content.setClipChildren(false);
        page.addView(content);
        root.addView(page, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        homeView = vertical(content, 0);
        hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.BOTTOM);
        hero.setClipChildren(false);
        hero.setClipToPadding(false);
        hero.setPadding(Ui.px(6), 0, Ui.px(6), 0);
        homeView.addView(hero, new LinearLayout.LayoutParams(Ui.px(580), Ui.px(420)));
        heroLabel = Ui.text(this, "", 14, Ui.semi, Ui.muted);
        heroLabel.setLetterSpacing(.12f);
        hero.addView(heroLabel);
        heroTitle = Ui.text(this, "", 48, Ui.semi, Ui.text);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setPadding(0, Ui.px(10), 0, Ui.px(16));
        hero.addView(heroTitle);
        heroChips = new LinearLayout(this);
        Ui.gap(heroChips, 8);
        hero.addView(heroChips);
        heroText = Ui.text(this, "", 16, Ui.regular, Ui.text);
        heroText.setAlpha(.85f);
        heroText.setMaxLines(3);
        heroText.setEllipsize(TextUtils.TruncateAt.END);
        heroText.setLineSpacing(0, 1.35f);
        heroText.setPadding(0, Ui.px(14), 0, Ui.px(22));
        hero.addView(heroText);
        LinearLayout buttons = Ui.row(this);
        buttons.setPadding(0, Ui.px(4), 0, Ui.px(4));
        Ui.gap(buttons, 14);
        heroPlay = Ui.button(this, "", true);
        heroPlay.setOnClickListener(v -> {
            if (heroIdx < heroes.size()) heroes.get(heroIdx).action.run();
        });
        heroNext = Ui.button(this, "Outra sugestão", false);
        heroNext.setOnClickListener(v -> loadSuggestion(() -> showHero(heroes.size() - 1)));
        buttons.addView(heroPlay, new LinearLayout.LayoutParams(Ui.WRAP, Ui.px(46)));
        buttons.addView(heroNext, new LinearLayout.LayoutParams(Ui.WRAP, Ui.px(46)));
        hero.addView(buttons);

        resumeBlock = vertical(homeView, 0);
        resumeBlock.addView(Ui.h2(this, "Continuar assistindo"));
        resumeRow = Ui.shelf(this, resumeBlock);
        ((Ui.HScroll) resumeRow.getParent()).pinnedStep = Ui.px(MEDIA_NARROW + 24);
        homeView.addView(Ui.h2(this, "Seus apps"));
        appShelf = Ui.shelf(this, homeView);
        ((Ui.HScroll) appShelf.getParent()).pinnedStep = Ui.px(MEDIA_NARROW + 24);
        latestBlock = vertical(homeView, 0);
        latestBlock.addView(Ui.h2(this, "Adicionados recentemente"));
        latestRow = Ui.shelf(this, latestBlock);
        ((Ui.HScroll) latestRow.getParent()).pinnedStep = Ui.px(MEDIA_NARROW + 24);

        appsView = vertical(content, 100);
        appsView.addView(Ui.h2(this, "Todos os apps"));
        appsGrid = vertical(appsView, 0);

        projView = vertical(content, 100);
        buildSettings();
    }

    private LinearLayout vertical(LinearLayout parent, float padTop) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, Ui.px(padTop), 0, 0);
        // as prateleiras vão até a borda da tela (além da margem deste bloco): sem isto o bloco as recortava na margem
        v.setClipChildren(false);
        parent.addView(v, new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP));
        return v;
    }

    private void buildTop() {
        top = new FrameLayout(this);
        top.setTag(Nav.ROW);
        top.setClipChildren(false);
        top.setPadding(Ui.px(Ui.PAGE), 0, Ui.px(Ui.PAGE), 0);
        // faixa escura atrás da barra: o conteúdo que rola some por baixo dela em vez de ficar "cortado"
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{Ui.bg, (Ui.bg & 0xffffff) | 0xcc000000, 0}));
        LinearLayout pill = new LinearLayout(this);
        pill.setBackground(Ui.round(0xbf282c30, 30));
        pill.setPadding(Ui.px(5), Ui.px(5), Ui.px(5), Ui.px(5));
        String[][] t = {{"home", "Início"}, {"apps", "Apps"}, {"proj", "Projetor"}};
        tabs = new TextView[t.length];
        for (int i = 0; i < t.length; i++) {
            String view = t[i][0];
            TextView tab = Ui.text(this, t[i][1], 17, Ui.regular, 0);
            tab.setGravity(Gravity.CENTER);
            tab.setPadding(Ui.px(26), 0, Ui.px(26), 0);
            // selecionada = pílula discreta; com o seletor em cima = cor de destaque
            tab.setTextColor(Ui.colors(Ui.onAccent, Ui.text, Ui.muted));
            tab.setBackground(Ui.states(Ui.round(Ui.accent, 20), Ui.round(0x33ffffff, 20), Ui.NONE));
            // a aba troca só de passar o foco, sem precisar apertar OK
            Ui.focusable(tab, 1, (v, f) -> {
                if (f && !current.equals(view)) show(view);
            });
            tabs[i] = tab;
            pill.addView(tab, new LinearLayout.LayoutParams(Ui.WRAP, Ui.px(40)));
        }
        top.addView(pill, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        clock = Ui.text(this, "", 18, Ui.semi, Ui.text);
        right.addView(clock);
        top.addView(right, new FrameLayout.LayoutParams(Ui.WRAP, Ui.MATCH, Gravity.END));
        root.addView(top, new FrameLayout.LayoutParams(Ui.MATCH, Ui.px(92), Gravity.TOP));
    }

    private void buildLayers() {
        // menu do OK segurado
        menuLayer = layer(0xc7000000);
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(Ui.round(Ui.surface, 20));
        menu.setPadding(Ui.px(26), Ui.px(28), Ui.px(26), Ui.px(22));
        menuTitle = Ui.text(this, "", 22, Ui.semi, Ui.text);
        menuTitle.setPadding(Ui.px(18), 0, 0, Ui.px(14));
        menu.addView(menuTitle);
        menuItems = new LinearLayout(this);
        menuItems.setOrientation(LinearLayout.VERTICAL);
        // listas longas (apps ao ligar, papéis de parede) rolam; o ScrollView segue o foco sozinho
        ScrollView menuScroll = new ScrollView(this);
        menuScroll.setFocusable(false);
        menuScroll.setVerticalScrollBarEnabled(false);
        menuScroll.addView(menuItems);
        menu.addView(menuScroll);
        menuLayer.addView(menu, new FrameLayout.LayoutParams(Ui.px(420), Ui.WRAP, Gravity.CENTER));

        // recentes
        recentsLayer = new LinearLayout(this);
        recentsLayer.setOrientation(LinearLayout.VERTICAL);
        recentsLayer.setGravity(Gravity.CENTER_VERTICAL);
        recentsLayer.setBackgroundColor(0xed000000);
        recentsLayer.setPadding(Ui.px(Ui.PAGE), 0, Ui.px(Ui.PAGE), 0);
        recentsLayer.setClipChildren(false);
        recentsLayer.setVisibility(View.GONE);
        recentsLayer.addView(Ui.h2(this, "Recentes"));
        recentCards = Ui.shelf(this, recentsLayer);
        recentClose = Ui.shelf(this, recentsLayer);
        recentsLayer.addView(Ui.text(this, "OK abre  ·  ↓ Fechar encerra o app  ·  Voltar sai", 15, Ui.regular, Ui.muted));
        root.addView(recentsLayer, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // login do Jellyfin
        loginLayer = layer(0xbf000000);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setBackground(Ui.round(Ui.surface, 20));
        form.setPadding(Ui.px(40), Ui.px(36), Ui.px(40), Ui.px(32));
        form.addView(Ui.text(this, "Conectar ao Jellyfin", 22, Ui.semi, Ui.text));
        TextView p = Ui.text(this, "Endereço do servidor na sua rede, usuário e senha.", 16, Ui.regular, Ui.muted);
        p.setPadding(0, Ui.px(8), 0, Ui.px(10));
        form.addView(p);
        jfServer = field(form, "Servidor", "http://192.168.0.10:8096", InputType.TYPE_TEXT_VARIATION_URI);
        jfUser = field(form, "Usuário", "", InputType.TYPE_TEXT_VARIATION_NORMAL);
        jfPass = field(form, "Senha", "", InputType.TYPE_TEXT_VARIATION_PASSWORD);
        loginErr = Ui.text(this, "", 16, Ui.regular, 0xffff8a80);
        loginErr.setPadding(0, Ui.px(14), 0, Ui.px(10));
        form.addView(loginErr);
        LinearLayout lb = Ui.row(this);
        Ui.gap(lb, 14);
        TextView enter = Ui.button(this, "Entrar", true);
        enter.setOnClickListener(v -> login());
        TextView cancel = Ui.button(this, "Cancelar", false);
        cancel.setOnClickListener(v -> closeLogin());
        lb.addView(enter, new LinearLayout.LayoutParams(Ui.WRAP, Ui.px(46)));
        lb.addView(cancel, new LinearLayout.LayoutParams(Ui.WRAP, Ui.px(46)));
        form.addView(lb);
        loginLayer.addView(form, new FrameLayout.LayoutParams(Ui.px(520), Ui.WRAP, Gravity.CENTER));

        // descanso: relógio grande, data e status em baixo brilho
        dim = layer(0xe0000000);
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.CENTER_HORIZONTAL);
        bigClock = Ui.text(this, "", 180, Ui.light, 0xb3ffffff);
        bigDate = Ui.text(this, "", 28, Ui.regular, 0x66ffffff);
        bigDate.setPadding(0, Ui.px(14), 0, 0);
        bigInfo = Ui.text(this, "", 20, Ui.regular, 0x47ffffff);
        bigInfo.setPadding(0, Ui.px(10), 0, 0);
        clockBox.addView(bigClock);
        clockBox.addView(bigDate);
        clockBox.addView(bigInfo);
        dim.addView(clockBox, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
    }

    private FrameLayout layer(int color) {
        FrameLayout l = new FrameLayout(this);
        l.setBackgroundColor(color);
        l.setVisibility(View.GONE);
        root.addView(l, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        return l;
    }

    private EditText field(LinearLayout form, String label, String hint, int variation) {
        TextView l = Ui.text(this, label, 14, Ui.regular, Ui.muted);
        l.setPadding(0, Ui.px(14), 0, Ui.px(6));
        form.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine();
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setTypeface(Ui.regular);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(18));
        e.setTextColor(Ui.text);
        e.setHint(hint);
        e.setHintTextColor(Ui.muted);
        e.setPadding(Ui.px(14), 0, Ui.px(14), 0);
        e.setBackground(Ui.states(Ui.stroked(Ui.bg, 10, 2, Ui.text), null, Ui.stroked(Ui.bg, 10, 1, 0x33ffffff)));
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        e.setOnEditorActionListener((v, a, ev) -> {
            move(1, 0);
            return true;
        });
        Ui.focusable(e, 1, null);
        form.addView(Ui.rowOf(this, e, 48));
        return e;
    }

    /* ---------- aba Projetor: uma lista só, com uma regra só ----------
     * Toda linha tem o nome à esquerda e o valor atual à direita.
     * Escolha "‹ valor ›": ← → trocam o valor na hora (OK também avança). Ação "›": OK abre.
     * Mudanças de aparência remontam a tela uma vez só, logo depois da última troca, e o foco volta à mesma linha.
     */
    private static final class Choice {
        String[] names;
        int idx;
        java.util.function.IntConsumer apply;
        TextView value;
    }

    private final Map<View, Choice> choices = new HashMap<>();
    private final List<View> settingRows = new ArrayList<>();
    private LinearLayout settingsList;
    private Choice bootChoice;
    private final List<String> bootPkgs = new ArrayList<>();
    /** Linha focada antes de remontar a tela (static: sobrevive ao recreate). */
    private static int settingsFocus = -1;

    private void buildSettings() {
        settingsList = new LinearLayout(this);
        settingsList.setOrientation(LinearLayout.VERTICAL);
        settingsList.setClipChildren(false);
        projView.addView(settingsList, new LinearLayout.LayoutParams(Ui.px(760), Ui.WRAP));

        // ajustes do próprio projetor primeiro (é a aba dele)
        section("IMAGEM");
        adjust("Trapézio vertical", 'v');
        adjust("Trapézio horizontal", 'h');
        adjust("Zoom", 'z');
        action("Restaurar imagem", "Ajuste de fábrica", () -> sys.sh("keystone", "reset", null));

        section("ÁUDIO");
        outRow = action("Saída de som", "", () -> {
            sys.toggleOut();
            ui.postDelayed(this::refreshProjector, 3000);
        });
        nightChoice = choice("Modo noturno", new String[]{"Desligado", "Ligado"}, sys.status().night ? 1 : 0, i -> {
            // se o aparelho não suportar, a linha volta para o que de fato ficou
            boolean on = sys.night(i == 1);
            if (on != (i == 1)) {
                nightChoice.idx = on ? 1 : 0;
                showChoice(nightChoice);
            }
        });

        section("CELULAR E REDE");
        remoteRow = action("Reiniciar rede", "", () -> sys.sh("wifi", "", (ok, o) -> {
            if (ok) toast("Reiniciando o Wi-Fi... o celular reconecta em alguns segundos");
        }));
        action("Wi-Fi", null, () -> sys.settings(Settings.ACTION_WIFI_SETTINGS));
        action("Bluetooth", null, () -> sys.settings(Settings.ACTION_BLUETOOTH_SETTINGS));

        section("DESEMPENHO");
        action("Liberar memória", "Fecha apps em segundo plano", () -> {
            toast("Liberando memória...");
            sys.sh("clean", "", null);
        });

        section("APARÊNCIA");
        String[] themes = {"abyss", "preto", "sepia"};
        choice("Tema", new String[]{"Abyss", "Preto puro", "Sépia escuro"}, Math.max(0, Arrays.asList(themes).indexOf(Ui.theme)), i -> {
            prefs.edit().putString("theme", themes[i]).apply();
            restyleLater();
        });
        String[] accents = {"Do tema", "Azul", "Verde", "Âmbar", "Vermelho", "Roxo"};
        int accent = 0;
        for (int i = 0; i < Ui.ACCENTS.length; i++) if (Ui.ACCENTS[i] == prefs.getInt("accent", 0)) accent = i;
        choice("Cor de destaque", accents, accent, i -> {
            prefs.edit().putInt("accent", Ui.ACCENTS[i]).apply();
            restyleLater();
        });
        choice("Formato dos cards", new String[]{"Retos", "Arredondados", "Pílula"}, Ui.shape, i -> {
            prefs.edit().putInt("shape", i).apply();
            restyleLater();
        });
        choice("Nome dos apps", new String[]{"Mostrar", "Ocultar"}, Ui.names ? 0 : 1, i -> {
            prefs.edit().putBoolean("names", i == 0).apply();
            restyleLater();
        });
        if (storage(false)) {
            File[] files = PICTURES.listFiles((d, n) -> n.toLowerCase().matches(".*\\.(jpe?g|png|webp)"));
            List<String> paths = new ArrayList<>(), names = new ArrayList<>();
            paths.add("");
            names.add("Nenhum");
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    paths.add(f.getAbsolutePath());
                    names.add(f.getName().replaceAll("\\.[^.]+$", ""));
                }
            }
            choice("Papel de parede", names.toArray(new String[0]), Math.max(0, paths.indexOf(prefs.getString("wallpaper", ""))), i -> {
                prefs.edit().putString("wallpaper", paths.get(i)).apply();
                restyleLater();
            });
        } else {
            action("Papel de parede", "Permitir acesso às imagens", () -> storage(true));
        }
        choice("Escurecer o fundo", new String[]{"Não", "Pouco", "Muito"}, prefs.getInt("dim", 1), i -> {
            prefs.edit().putInt("dim", i).apply();
            restyleLater();
        });

        section("AO LIGAR O PROJETOR");
        bootPkgs.add("");
        bootChoice = choice("Abrir", new String[]{"Tela inicial"}, 0, i -> prefs.edit().putString("boot", bootPkgs.get(i)).apply());

        section("JELLYFIN");
        jfAccount = action("Conta", "", () -> {
            if (jf.connected()) jf.logout();
            else openLogin();
        });

        section("SISTEMA");
        action("Configurações do Android", null, () -> sys.settings(Settings.ACTION_SETTINGS));

        section("BACKUP");
        action("Salvar configurações", "Download", this::backup);
        action("Restaurar configurações", null, this::restore);
    }

    private final Map<View, Character> adjusts = new HashMap<>();
    private TextView outRow, remoteRow;
    private Choice nightChoice;

    /** Ajuste contínuo do keystone: ← → mexem na imagem na hora (não há valor para mostrar). */
    private void adjust(String label, char kind) {
        LinearLayout row = addSetting(label, () -> toast("Use ◀ ▶ para ajustar"));
        Ui.detail(row).setText("◀   ▶");
        adjusts.put(row, kind);
    }

    /** Valores que mudam fora do launcher: saída de som e celular conectado. */
    private void refreshProjector() {
        Sys.Status st = sys.status();
        outRow.setText((st.out.isEmpty() ? "Alto-falante" : st.out) + "   ›");
        sys.sh("remote", "", (ok, n) -> remoteRow.setText((!ok ? "" : n.equals("0") ? "Sem celular" : "Celular conectado") + "   ›"));
    }

    private void section(String title) {
        TextView t = Ui.text(this, title, 13, Ui.semi, Ui.muted);
        t.setLetterSpacing(.1f);
        t.setPadding(Ui.px(18), Ui.px(settingRows.isEmpty() ? 4 : 26), 0, Ui.px(8));
        settingsList.addView(t);
    }

    private LinearLayout addSetting(String label, Runnable click) {
        LinearLayout row = Ui.item(this, label, "");
        row.setOnClickListener(v -> click.run());
        settingsList.addView(Ui.rowOf(this, row, 56));
        settingRows.add(row);
        return row;
    }

    /** Até 2 opções: "‹ valor ›", ← → trocam, OK alterna. Mais que 2: OK abre a lista e aplica a escolhida. */
    private Choice choice(String label, String[] names, int idx, java.util.function.IntConsumer apply) {
        Choice c = new Choice();
        c.names = names;
        c.idx = Math.max(0, Math.min(idx, names.length - 1));
        c.apply = apply;
        LinearLayout row = addSetting(label, () -> {
            if (c.names.length > 2) pick(label, c);
            else step(c, 1);
        });
        c.value = Ui.detail(row);
        showChoice(c);
        choices.put(row, c);
        return c;
    }

    /** Ação "›": OK abre. */
    private TextView action(String label, String detail, Runnable click) {
        TextView d = Ui.detail(addSetting(label, click));
        d.setText(detail == null ? "›" : detail + "   ›");
        return d;
    }

    private void step(Choice c, int d) {
        c.idx = (c.idx + d + c.names.length) % c.names.length;
        showChoice(c);
        c.apply.accept(c.idx);
    }

    private void showChoice(Choice c) {
        c.value.setText(c.names.length > 2 ? c.names[c.idx] + "   ›" : "‹   " + c.names[c.idx] + "   ›");
    }

    /** Lista de opções na camada do menu; a atual vem marcada e focada. */
    private void pick(String label, Choice c) {
        menuReturn = getCurrentFocus();
        menuTitle.setText(label);
        menuItems.removeAllViews();
        for (int i = 0; i < c.names.length; i++) {
            int n = i;
            Ui.detail(menuItem(c.names[i], () -> {
                if (n != c.idx) step(c, n - c.idx);
            })).setText(i == c.idx ? "✓" : "");
        }
        menuLayer.setVisibility(View.VISIBLE);
        focus(Nav.items(Nav.rows(menuLayer).get(c.idx)).get(0));
    }

    /** Ao ligar: tela inicial, HDMI e os apps (a lista chega depois que os apps carregam). */
    private void updateBootChoice() {
        List<String> names = new ArrayList<>();
        bootPkgs.clear();
        names.add("Tela inicial");
        bootPkgs.add("");
        for (Apps.App a : apps.list) {
            names.add(a.name);
            bootPkgs.add(a.pkg);
        }
        bootChoice.names = names.toArray(new String[0]);
        bootChoice.idx = Math.max(0, bootPkgs.indexOf(prefs.getString("boot", "")));
        showChoice(bootChoice);
    }

    private final Runnable restyle = () -> {
        settingsFocus = settingRows.indexOf(getCurrentFocus());
        recreate();
    };

    private void restyleLater() {
        ui.removeCallbacks(restyle);
        ui.postDelayed(restyle, 700);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            settingsFocus = settingRows.indexOf(getCurrentFocus());
            recreate();
        }
    }

    private void loadWallpaper() {
        String path = prefs.getString("wallpaper", "");
        if (path.isEmpty() || !storage(false)) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = Math.max(1, o.outWidth / 1600);
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(path, o);
        if (b == null) return;
        wallpaper.setImageBitmap(b);
        // escurecer com filtro de cor, sem camada extra por cima
        int a = new int[]{0, 0x66, 0xa6}[prefs.getInt("dim", 1)];
        if (a > 0) wallpaper.setColorFilter(Color.argb(a, 0, 0, 0), PorterDuff.Mode.SRC_ATOP);
    }

    /** Leitura/escrita de arquivos (papel de parede e backup); pede a permissão na primeira vez. */
    private boolean storage(boolean ask) {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) return true;
        if (ask) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            toast("Permita o acesso aos arquivos e tente de novo");
        }
        return false;
    }

    /** Só na primeira abertura depois de ligar (contador de boots do Android), e só nos primeiros minutos. */
    private void openOnBoot() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        if (boot == prefs.getInt("bootHandled", -1)) return;
        prefs.edit().putInt("bootHandled", boot).apply();
        String target = prefs.getString("boot", "");
        if (!target.isEmpty() && SystemClock.elapsedRealtime() < 5 * 60 * 1000) ui.postDelayed(() -> sys.open(target), 1500);
    }

    /** Backup em Download/projetor-os-backup.json: ordem, favoritos, ocultos, aparência, inicialização e Jellyfin. */
    private void backup() {
        if (!storage(true)) return;
        JSONObject j = new JSONObject();
        try {
            for (Map.Entry<String, ?> e : prefs.getAll().entrySet()) j.put(e.getKey(), e.getValue());
            try (FileOutputStream out = new FileOutputStream(BACKUP)) {
                out.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            toast("Salvo em Download/" + BACKUP.getName());
        } catch (IOException | JSONException e) {
            toast("Não deu para salvar: " + e.getMessage());
        }
    }

    private void restore() {
        if (!storage(true)) return;
        try (FileInputStream in = new FileInputStream(BACKUP)) {
            byte[] b = new byte[(int) BACKUP.length()];
            new java.io.DataInputStream(in).readFully(b);
            JSONObject j = new JSONObject(new String(b, StandardCharsets.UTF_8));
            SharedPreferences.Editor ed = prefs.edit();
            for (Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                Object v = j.get(k);
                if (v instanceof Boolean) ed.putBoolean(k, (Boolean) v);
                else if (v instanceof Integer) ed.putInt(k, (Integer) v);
                else if (v instanceof Long) ed.putLong(k, (Long) v);
                else ed.putString(k, String.valueOf(v));
            }
            ed.commit();
            recreate();
        } catch (IOException e) {
            toast("Nenhum backup em Download/" + BACKUP.getName());
        } catch (JSONException e) {
            toast("Backup inválido");
        }
    }

    /* ---------- abas e navegação ---------- */

    private boolean showing;

    /**
     * Troca o conteúdo da aba. Esconder a aba antiga pode tirar o foco de dentro dela; o Android então foca a aba
     * "Início" e o aviso desse foco chamaria show() de novo no meio desta troca (os dois conteúdos ficavam visíveis).
     * Por isso: sem reentrada, e a aba nova aparece antes de a antiga sumir.
     */
    private void show(String view) {
        if (showing) return;
        showing = true;
        current = view;
        if (view.equals("proj")) refreshProjector();
        String[] ids = {"home", "apps", "proj"};
        View[] views = {homeView, appsView, projView};
        for (int i = 0; i < ids.length; i++) {
            tabs[i].setSelected(ids[i].equals(view));
            if (ids[i].equals(view)) views[i].setVisibility(View.VISIBLE);
        }
        for (int i = 0; i < ids.length; i++) if (!ids[i].equals(view)) views[i].setVisibility(View.GONE);
        backdrop.setAlpha(view.equals("home") ? 1 : .35f);
        page.scrollTo(0, 0);
        showing = false;
    }

    private View currentTab() {
        return tabs[current.equals("home") ? 0 : current.equals("apps") ? 1 : 2];
    }

    @Override
    protected ViewGroup scope() {
        if (menuLayer.getVisibility() == View.VISIBLE) return menuLayer;
        if (recentsLayer.getVisibility() == View.VISIBLE) return recentsLayer;
        if (loginLayer.getVisibility() == View.VISIBLE) return loginLayer;
        return root;
    }

    // subir até a barra volta para a aba atual; senão o foco cairia em outra aba e trocaria a página
    @Override
    protected View enterRow(ViewGroup row, View from) {
        return row == top ? currentTab() : super.enterRow(row, from);
    }

    @Override
    protected void reveal(View v) {
        if (Nav.inside(v, hero)) page.glideTo(0);
        else if (!Nav.inside(v, top)) super.reveal(v);
    }

    @Override
    protected boolean onArrow(View focused, int dx, int dy) {
        if (moving != null) {
            if (dx != 0 && apps.shift(moving, dx)) {
                renderApps(true);
                markMoving();
            }
            return true;
        }
        Character k = adjusts.get(focused);
        if (k != null && dx != 0) {
            sys.sh("keystone", k + (dx > 0 ? "+" : "-"), null);
            return true;
        }
        Choice c = choices.get(focused);
        if (c != null && dx != 0 && c.names.length <= 2) {
            step(c, dx);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onLongOk(View v) {
        String pkg = cardPkg.get(v);
        if (pkg == null) return false;
        openMenu(pkg);
        return true;
    }

    @Override
    protected boolean onAnyKey() {
        ui.removeCallbacks(showDim);
        ui.postDelayed(showDim, IDLE_MS);
        if (dim.getVisibility() != View.VISIBLE) return false;
        hideDim();
        return true;
    }

    @Override
    public void onBackPressed() {
        if (moving != null) endMove();
        else if (menuLayer.getVisibility() == View.VISIBLE) closeMenu();
        else if (recentsLayer.getVisibility() == View.VISIBLE) closeRecents();
        else if (loginLayer.getVisibility() == View.VISIBLE) closeLogin();
        else if (!current.equals("home")) {
            show("home");
            focus(tabs[0]);
        } else {
            // Voltar duas vezes rápido na tela inicial abre os recentes
            long now = SystemClock.uptimeMillis();
            if (now - lastBack < 500) openRecents();
            else focus(heroPlay);
            lastBack = now;
        }
    }

    private void closeLayers() {
        if (moving != null) endMove();
        menuLayer.setVisibility(View.GONE);
        recentsLayer.setVisibility(View.GONE);
        loginLayer.setVisibility(View.GONE);
    }

    /* ---------- apps ---------- */

    private void loadApps() {
        new Thread(() -> {
            Apps fresh = new Apps(this);
            fresh.load(this);
            ui.post(() -> {
                if (moving != null) return;
                apps = fresh;
                renderApps(false);
            });
        }).start();
    }

    /** Monta as prateleiras de apps; não refaz nada se a lista não mudou (o caso comum ao voltar de um app). */
    private void renderApps(boolean force) {
        StringBuilder sig = new StringBuilder();
        for (Apps.App a : apps.list) sig.append(a.pkg).append(apps.favs.contains(a.pkg) ? '*' : ',');
        for (Apps.App a : apps.hiddenList) sig.append('-').append(a.pkg);
        if (!force && sig.toString().equals(appsSig)) return;
        appsSig = sig.toString();
        updateBootChoice();
        String focused = cardPkg.get(getCurrentFocus());
        cardPkg.clear();
        appShelf.removeAllViews();
        appsGrid.removeAllViews();
        for (Apps.App a : apps.list) appShelf.addView(appCard(a, MEDIA_NARROW, MEDIA_H, null, true).root);
        gridRows(apps.list, null);
        if (!apps.hiddenList.isEmpty()) {
            appsGrid.addView(Ui.h2(this, "Ocultos"));
            gridRows(apps.hiddenList, "oculto");
        }
        if (focused != null) focus(cardOf(focused));
    }

    private void gridRows(List<Apps.App> list, String sub) {
        for (int i = 0; i < list.size(); i += 6) {
            LinearLayout row = Ui.row(this);
            row.setPadding(Ui.px(24), Ui.px(12), Ui.px(24), Ui.px(12));
            Ui.gap(row, 24);
            for (Apps.App a : list.subList(i, Math.min(i + 6, list.size()))) row.addView(appCard(a, 172, 97, sub, false).root);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP);
            lp.leftMargin = lp.rightMargin = -Ui.px(24);
            appsGrid.addView(row, lp);
        }
    }

    /** Card de app; na tela inicial (tile) segue o padrão das fileiras de mídia: ícone em pé, banner ao focar. */
    private Ui.Card appCard(Apps.App a, int w, int h, String sub, boolean tile) {
        Ui.Card c = new Ui.Card(this, w, h, a.name, sub, a.cover == null ? a.name.substring(0, 1) : null);
        if (tile) {
            c.expand(MEDIA_NARROW, MEDIA_H * 16 / 9);
            if (a.tile != null) c.img.setImageBitmap(a.tile);
            if (a.cover != null) c.wideImg.setImageBitmap(a.cover);
        } else if (a.cover != null) c.img.setImageBitmap(a.cover);
        if (sub != null) c.thumb.setAlpha(.5f);
        if (apps.favs.contains(a.pkg)) c.star();
        c.root.setOnClickListener(v -> {
            if (moving != null) endMove();
            else sys.open(a.pkg);
        });
        cardPkg.put(c.root, a.pkg);
        return c;
    }

    private View cardOf(String pkg) {
        View container = current.equals("apps") ? appsGrid : appShelf;
        for (Map.Entry<View, String> e : cardPkg.entrySet()) if (e.getValue().equals(pkg) && Nav.inside(e.getKey(), container)) return e.getKey();
        return null;
    }

    private void openMenu(String pkg) {
        Apps.App a = apps.find(pkg);
        if (a == null) return;
        menuReturn = cardOf(pkg);
        menuTitle.setText(a.name);
        menuItems.removeAllViews();
        boolean isHidden = apps.hidden.contains(pkg);
        // HDMI fica sempre em primeiro: não move nem vira favorito
        if (!pkg.equals(apps.hdmi) && !isHidden) {
            menuItem("Mover", () -> startMove(pkg));
            menuItem(apps.favs.contains(pkg) ? "Remover dos favoritos" : "Adicionar aos favoritos", () -> {
                apps.toggleFav(pkg);
                renderApps(true);
                focus(cardOf(pkg));
            });
        }
        if (!pkg.equals(apps.hdmi)) menuItem(isHidden ? "Mostrar na tela inicial" : "Ocultar", () -> {
            apps.toggleHidden(pkg);
            renderApps(true);
            focus(cardOf(pkg) != null ? cardOf(pkg) : currentTab());
        });
        if (!pkg.equals("hdmi")) {
            menuItem("Forçar parada", () -> sys.sh("stop", pkg, (ok, o) -> toast(ok ? a.name + " foi encerrado" : o)));
            menuItem("Informações / desinstalar", () -> sys.appInfo(pkg));
        }
        menuLayer.setVisibility(View.VISIBLE);
        focus(Nav.items(Nav.rows(menuLayer).get(0)).get(0));
    }

    private LinearLayout menuItem(String label, Runnable action) {
        LinearLayout it = Ui.item(this, label, null);
        it.setOnClickListener(v -> {
            closeMenu();
            action.run();
        });
        menuItems.addView(Ui.rowOf(this, it, 50));
        return it;
    }

    private void closeMenu() {
        menuLayer.setVisibility(View.GONE);
        focus(menuReturn);
    }

    private void startMove(String pkg) {
        moving = pkg;
        markMoving();
        toast("◀ ▶ para mover  ·  OK para soltar");
    }

    private void markMoving() {
        View c = cardOf(moving);
        if (c == null) return;
        TextView arrows = Ui.text(this, "◀     ▶", 26, Ui.semi, 0xffffffff);
        arrows.setGravity(Gravity.CENTER);
        arrows.setBackgroundColor(0x73000000);
        ((ViewGroup) ((ViewGroup) c).getChildAt(0)).addView(arrows, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        focus(c);
    }

    private void endMove() {
        String pkg = moving;
        moving = null;
        renderApps(true);
        focus(cardOf(pkg));
    }

    /* ---------- recentes ---------- */

    private void openRecents() {
        sys.sh("recents", "", (ok, out) -> {
            if (!ok) {
                toast(out);
                return;
            }
            recentCards.removeAllViews();
            recentClose.removeAllViews();
            Set<String> seen = new LinkedHashSet<>();
            for (String p : out.split("\n")) {
                Apps.App a = apps.find(p);
                if (a == null || !seen.add(a.pkg)) continue;
                Ui.Card c = new Ui.Card(this, 270, 152, a.name, null, a.cover == null ? a.name.substring(0, 1) : null);
                if (a.cover != null) c.img.setImageBitmap(a.cover);
                c.root.setOnClickListener(v -> {
                    closeRecents();
                    sys.open(a.pkg);
                });
                recentCards.addView(c.root);
                TextView close = Ui.text(this, "✕  Fechar", 16, Ui.regular, Ui.text);
                close.setGravity(Gravity.CENTER);
                close.setBackground(Ui.states(Ui.round(0xffc62828, 10), null, Ui.round(0x1affffff, 10)));
                Ui.focusable(close, 1.05f, null);
                close.setOnClickListener(v -> sys.sh("stop", a.pkg, (k, o) -> {
                    if (!k) {
                        toast(o);
                        return;
                    }
                    int i = recentClose.indexOfChild(close);
                    recentCards.removeViewAt(i);
                    recentClose.removeViewAt(i);
                    if (recentClose.getChildCount() == 0) closeRecents();
                    else focus(recentClose.getChildAt(Math.min(i, recentClose.getChildCount() - 1)));
                }));
                recentClose.addView(close, new LinearLayout.LayoutParams(Ui.px(270), Ui.px(42)));
            }
            if (seen.isEmpty()) {
                toast("Nenhum app recente");
                return;
            }
            closeLayers();
            recentsLayer.setVisibility(View.VISIBLE);
            focus(recentCards.getChildAt(0));
        });
    }

    private void closeRecents() {
        recentsLayer.setVisibility(View.GONE);
        focus(current.equals("home") ? heroPlay : currentTab());
    }

    /* ---------- descanso ---------- */

    private final Runnable showDim = () -> {
        dim.setAlpha(0);
        dim.setVisibility(View.VISIBLE);
        dim.bringToFront();
        dim.animate().alpha(1).setDuration(3000).start();
        MainActivity.this.tick.run(); // qualificado: tick é declarado mais abaixo
    };

    private void hideDim() {
        dim.animate().cancel();
        dim.setVisibility(View.GONE);
        ui.removeCallbacks(showDim);
        ui.postDelayed(showDim, IDLE_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Date now = new Date();
            String hm = new SimpleDateFormat("HH:mm", PT).format(now);
            clock.setText(hm);
            if (dim.getVisibility() == View.VISIBLE) {
                bigClock.setText(hm);
                bigDate.setText(new SimpleDateFormat("EEEE, d 'de' MMMM", PT).format(now));
                bigInfo.setText(Sys.statusLine(sys.status()));
            }
            ui.removeCallbacks(this);
            ui.postDelayed(this, 10000);
        }
    };

    /** A cada 20 s o brilho do canto vai para outro tom, numa transição de 6 s. */
    private final Runnable tintCycle = new Runnable() {
        private int color;

        @Override
        public void run() {
            if (color == 0) color = (Ui.accent & 0xffffff) | 0x66000000;
            int next = Color.HSVToColor(0x66, new float[]{(float) (Math.random() * 360), .75f, .72f});
            ValueAnimator a = ValueAnimator.ofArgb(color, next);
            a.setDuration(6000);
            a.addUpdateListener(x -> tint.setColors(new int[]{(int) x.getAnimatedValue(), 0}));
            a.start();
            color = next;
            ui.postDelayed(this, 20000);
        }
    };

    /* ---------- destaque e Jellyfin ---------- */

    private void showHero(int i) {
        heroIdx = i;
        Hero h = heroes.get(i);
        heroLabel.setText(h.label);
        heroTitle.setText(h.title);
        heroText.setText(h.text == null ? "" : h.text);
        heroChips.removeAllViews();
        for (String c : h.chips) {
            TextView chip = Ui.text(this, c, 14, Ui.semi, Ui.text);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(Ui.px(12), 0, Ui.px(12), 0);
            chip.setBackground(Ui.round(0xb31e2428, 7));
            heroChips.addView(chip, new LinearLayout.LayoutParams(Ui.WRAP, Ui.px(30)));
        }
        heroPlay.setText((h.play ? "▶  " : "") + h.button);
        heroNext.setVisibility(jf.connected() ? View.VISIBLE : View.GONE);
        Jellyfin.load(h.image, backdrop);
    }

    private final Runnable rotateHero = new Runnable() {
        @Override
        public void run() {
            if (heroes.size() > 1 && !Nav.inside(getCurrentFocus(), hero)) showHero((heroIdx + 1) % heroes.size());
            ui.postDelayed(this, 15000);
        }
    };

    private Hero simpleHero(String title, String text, String button, Runnable action) {
        Hero h = new Hero();
        h.label = "JELLYFIN";
        h.title = title;
        h.text = text;
        h.button = button;
        h.action = action;
        return h;
    }

    private Hero itemHero(JSONObject it, String label) {
        Hero h = new Hero();
        h.label = label;
        h.title = Jellyfin.title(it);
        h.text = it.optString("Overview");
        if (it.has("OfficialRating")) h.chips.add(it.optString("OfficialRating"));
        if (it.optLong("RunTimeTicks") > 0) h.chips.add(Jellyfin.runtime(it.optLong("RunTimeTicks")));
        if (it.has("CommunityRating")) h.chips.add(String.format(PT, "★ %.1f", it.optDouble("CommunityRating")));
        h.image = jf.backdrop(it, 1280);
        h.play = true;
        JSONObject ud = it.optJSONObject("UserData");
        h.button = ud != null && ud.optLong("PlaybackPositionTicks") > 0 ? "Continuar" : "Assistir";
        h.action = () -> play(it);
        return h;
    }

    private void refreshJellyfin() {
        jfAccount.setText(jf.connected() ? jf.user + "  ·  Sair   ›" : "Conectar   ›");
        resumeBlock.setVisibility(View.GONE);
        if (!jf.connected()) {
            latestBlock.setVisibility(View.GONE);
            heroes.clear();
            heroes.add(simpleHero("Conecte seu servidor", "O que você está assistindo e sugestões da sua biblioteca aparecem aqui, direto na tela inicial.", "Conectar", this::openLogin));
            showHero(0);
            return;
        }
        jf.get("/Users/" + jf.userId + "/Items/Resume?Limit=12&MediaTypes=Video&Fields=Overview", (res, err) -> {
            if (err != null) {
                if (!jf.connected()) return; // a sessão expirou: o logout já mostrou o "Conectar"
                heroes.clear();
                heroes.add(simpleHero("Servidor fora do ar", jf.server + " não respondeu (" + err + ").", "Tentar de novo", this::refreshJellyfin));
                showHero(0);
                return;
            }
            JSONArray items = ((JSONObject) res).optJSONArray("Items");
            if (items == null) items = new JSONArray();
            heroes.clear();
            if (items.length() > 0) heroes.add(itemHero(items.optJSONObject(0), "CONTINUAR ASSISTINDO"));
            loadSuggestion(() -> {
                if (heroes.isEmpty()) heroes.add(simpleHero("Biblioteca vazia", "Adicione filmes e séries no servidor para aparecerem aqui.", "Atualizar", this::refreshJellyfin));
                showHero(0);
            });
            resumeRow.removeAllViews();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                Ui.Card c = mediaCard(Jellyfin.title(it), Jellyfin.subtitle(it));
                Jellyfin.load(jf.poster(it, 300), c.img);
                Jellyfin.load(jf.backdrop(it, 640), c.wideImg);
                JSONObject ud = it.optJSONObject("UserData");
                c.progress(ud == null ? 0 : (float) ud.optDouble("PlayedPercentage", 0));
                c.root.setOnClickListener(v -> play(it));
                resumeRow.addView(c.root);
            }
            resumeBlock.setVisibility(items.length() > 0 ? View.VISIBLE : View.GONE);
            loadLatest();
        });
    }

    /** Card de filme/série estilo Apple TV: estreito parado, se alarga em 16:9 quando focado. */
    private Ui.Card mediaCard(String title, String sub) {
        Ui.Card c = new Ui.Card(this, MEDIA_NARROW, MEDIA_H, title, sub, null);
        c.expand(MEDIA_NARROW, MEDIA_H * 16 / 9);
        return c;
    }

    /** O último assistido fica; a sugestão é sorteada de novo. */
    private void loadSuggestion(Runnable then) {
        jf.get("/Users/" + jf.userId + "/Items?IncludeItemTypes=Movie,Series&Recursive=true&SortBy=Random&Limit=1&Fields=Overview", (res, err) -> {
            for (Iterator<Hero> it = heroes.iterator(); it.hasNext(); ) if ("SUGESTÃO DO JELLYFIN".equals(it.next().label)) it.remove();
            JSONArray items = res instanceof JSONObject ? ((JSONObject) res).optJSONArray("Items") : null;
            if (items != null && items.length() > 0) heroes.add(itemHero(items.optJSONObject(0), "SUGESTÃO DO JELLYFIN"));
            then.run();
        });
    }

    /** Filmes e séries novos: abre direto no app do Jellyfin (ou no player, se ele não estiver instalado). */
    private void loadLatest() {
        // episódios novos da mesma série vêm agrupados como a série
        jf.get("/Users/" + jf.userId + "/Items/Latest?IncludeItemTypes=Movie,Episode&GroupItems=true&Limit=16", (res, err) -> {
            latestRow.removeAllViews();
            JSONArray items = res instanceof JSONArray ? (JSONArray) res : new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                boolean movie = "Movie".equals(it.optString("Type"));
                Ui.Card c = mediaCard(Jellyfin.title(it), movie ? (it.has("ProductionYear") ? String.valueOf(it.optInt("ProductionYear")) : "Filme") : "Série");
                Jellyfin.load(jf.poster(it, 300), c.img);
                Jellyfin.load(jf.backdrop(it, 640), c.wideImg);
                String id = Jellyfin.episode(it) ? it.optString("SeriesId", it.optString("Id")) : it.optString("Id");
                c.root.setOnClickListener(v -> sys.jellyfin(id, jf.stream(it.optString("Id")), Jellyfin.title(it)));
                latestRow.addView(c.root);
            }
            latestBlock.setVisibility(items.length() > 0 ? View.VISIBLE : View.GONE);
        });
    }

    /** Toca no player instalado, retomando de onde parou; série vai para o próximo episódio. */
    private void play(JSONObject it) {
        if ("Series".equals(it.optString("Type"))) {
            String sid = android.net.Uri.encode(it.optString("Id"));
            jf.get("/Shows/NextUp?SeriesId=" + sid + "&UserId=" + jf.userId + "&Limit=1&Fields=Overview", (res, err) -> {
                JSONArray a = res instanceof JSONObject ? ((JSONObject) res).optJSONArray("Items") : null;
                if (a != null && a.length() > 0) play(a.optJSONObject(0));
                else jf.get("/Shows/" + sid + "/Episodes?UserId=" + jf.userId + "&Limit=1", (r2, e2) -> {
                    JSONArray b = r2 instanceof JSONObject ? ((JSONObject) r2).optJSONArray("Items") : null;
                    if (b != null && b.length() > 0) play(b.optJSONObject(0));
                    else toast(e2 != null ? e2 : "Série sem episódios");
                });
            });
            return;
        }
        JSONObject ud = it.optJSONObject("UserData");
        long ticks = ud == null ? 0 : ud.optLong("PlaybackPositionTicks");
        // no player do próprio app Jellyfin (de onde parou); sem o app para TV, no player do sistema
        if (getPackageManager().getLaunchIntentForPackage(PlayService.JELLYFIN_TV) != null || getPackageManager().getLeanbackLaunchIntentForPackage(PlayService.JELLYFIN_TV) != null) {
            toast("Abrindo no Jellyfin...");
            startService(new Intent(this, PlayService.class).putExtra("server", jf.server).putExtra("auth", jf.auth())
                    .putExtra("user", jf.userId).putExtra("item", it.optString("Id")).putExtra("ticks", ticks));
        } else {
            sys.play(jf.stream(it.optString("Id")), Jellyfin.title(it), ticks / 10000);
        }
    }

    private void openLogin() {
        loginLayer.setVisibility(View.VISIBLE);
        loginErr.setText("");
        jfServer.setText(jf.lastServer());
        jfPass.setText("");
        focus(jfServer);
    }

    private void closeLogin() {
        loginLayer.setVisibility(View.GONE);
        focus(heroPlay);
    }

    private void login() {
        String server = jfServer.getText().toString().trim().replaceAll("/+$", "");
        if (!server.matches("^https?://[^\\s/]+.*")) {
            loginErr.setText("Use o endereço completo, ex.: http://192.168.0.10:8096");
            return;
        }
        loginErr.setText("Conectando...");
        jf.login(server, jfUser.getText().toString().trim(), jfPass.getText().toString(), (res, err) -> {
            if (err != null) {
                loginErr.setText(err);
                return;
            }
            jfPass.setText("");
            closeLogin();
            refreshJellyfin();
        });
    }
}
