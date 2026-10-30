package com.projetoros.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Tema, fontes e as peças da interface. O layout é pensado em 1280 de largura (como a projeção) e px() escala
 * para a tela real; o foco é sempre visível: contorno de alto contraste e um leve zoom.
 */
final class Ui {
    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int[] FOCUSED = {android.R.attr.state_focused}, SELECTED = {android.R.attr.state_selected}, ANY = {};

    /** Margem lateral segura: projetor corta bordas (overscan/keystone), então nada importante fica a menos disso. */
    static final int PAGE = 80;

    static float k = 1;
    static Typeface light, regular, semi;
    // fundos quase pretos para não "lavar" a imagem projetada; foco = cor de destaque (nunca bloco branco)
    static int bg, surface, text, muted, accent, onAccent;
    static String theme;
    /** Formato dos cards: 0 reto, 1 arredondado, 2 pílula. */
    static int shape;
    static boolean names;
    /** Cores de destaque que a pessoa pode escolher (0 = a do tema). */
    static final int[] ACCENTS = {0, 0xff5b8bd9, 0xff2bb673, 0xffe0a526, 0xffe5534b, 0xffb06ce0};

    static void init(Context c) {
        k = c.getResources().getDisplayMetrics().widthPixels / 1280f;
        if (regular == null) {
            light = Typeface.createFromAsset(c.getAssets(), "inter-light.ttf");
            regular = Typeface.createFromAsset(c.getAssets(), "inter-regular.ttf");
            semi = Typeface.createFromAsset(c.getAssets(), "inter-semibold.ttf");
        }
        android.content.SharedPreferences p = c.getSharedPreferences("launcher", Context.MODE_PRIVATE);
        setTheme(p.getString("theme", "abyss"));
        if (p.getInt("accent", 0) != 0) accent = p.getInt("accent", 0);
        // texto em cima da cor de destaque: escuro se ela for clara
        onAccent = android.graphics.Color.luminance(accent) > .45f ? 0xff0c0c0c : 0xffffffff;
        shape = p.getInt("shape", 1);
        names = p.getBoolean("names", true);
    }

    /** Raio dos cards conforme o formato escolhido; h é a altura da capa. */
    static float radius(float h) {
        return shape == 0 ? 3 : shape == 1 ? 12 : h / 2;
    }

    static void setTheme(String t) {
        theme = t;
        switch (t) {
            case "preto":
                bg = 0xff000000; surface = 0xff161616; text = 0xfff2f2f2; muted = 0xff8c8c8c; accent = 0xff6d8fc7;
                break;
            case "sepia":
                bg = 0xff120d08; surface = 0xff2a2018; text = 0xffefe2cc; muted = 0xffa8957a; accent = 0xffc9a46a;
                break;
            default:
                bg = 0xff000c18; surface = 0xff0b2236; text = 0xffe6edf3; muted = 0xff8aa0b8; accent = 0xff5b8bd9;
        }
    }

    static int px(float v) {
        return Math.round(v * k);
    }

    static TextView text(Context c, CharSequence s, float size, Typeface tf, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView line(Context c, CharSequence s, float size, Typeface tf, int color) {
        TextView t = text(c, s, size, tf, color);
        t.setSingleLine();
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static TextView h2(Context c, String s) {
        TextView t = text(c, s, 22, semi, text);
        t.setPadding(0, px(26), 0, px(8));
        return t;
    }

    static GradientDrawable round(int color, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(r));
        return g;
    }

    static GradientDrawable stroked(int fill, float r, float w, int stroke) {
        GradientDrawable g = round(fill, r);
        g.setStroke(px(w), stroke);
        return g;
    }

    static StateListDrawable states(Drawable focused, Drawable selected, Drawable normal) {
        StateListDrawable s = new StateListDrawable();
        s.addState(FOCUSED, focused);
        if (selected != null) s.addState(SELECTED, selected);
        s.addState(ANY, normal);
        return s;
    }

    static ColorStateList colors(int focused, int selected, int normal) {
        return new ColorStateList(new int[][]{FOCUSED, SELECTED, ANY}, new int[]{focused, selected, normal});
    }

    static final ColorDrawable NONE = new ColorDrawable(0);
    /** Curva única de todas as animações: sai rápido e assenta devagar (o "fluido" estilo Apple TV). */
    static final android.view.animation.Interpolator EASE = new android.view.animation.PathInterpolator(.2f, 0f, 0f, 1f);
    static final int ANIM_MS = 260;

    /** Torna focável pelo controle, com zoom no foco; extra roda junto (ex.: trocar de aba). */
    static <T extends View> T focusable(T v, float zoom, View.OnFocusChangeListener extra) {
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setOnFocusChangeListener((x, f) -> {
            x.animate().scaleX(f ? zoom : 1).scaleY(f ? zoom : 1).setDuration(ANIM_MS).setInterpolator(EASE).start();
            if (extra != null) extra.onFocusChange(x, f);
        });
        return v;
    }

    /** Botão principal (claro) ou secundário (vazado); focado, os dois ficam na cor de destaque. */
    static TextView button(Context c, String label, boolean primary) {
        TextView b = text(c, label, 18, semi, 0);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(px(26), 0, px(26), 0);
        b.setTextColor(colors(onAccent, onAccent, primary ? bg : text));
        b.setBackground(states(round(accent, 10), null,
                primary ? round(text, 10) : stroked(0x1fffffff, 10, 1, 0x4dffffff)));
        return focusable(b, 1.06f, null);
    }

    /** Linha de lista (menus e painel): rótulo à esquerda, detalhe pequeno à direita. */
    static LinearLayout item(Context c, String label, String detail) {
        LinearLayout row = new LinearLayout(c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(18), 0, px(18), 0);
        row.setBackground(states(round(accent, 12), null, NONE));
        TextView l = line(c, label, 18, regular, 0);
        l.setTextColor(colors(onAccent, text, text));
        l.setDuplicateParentStateEnabled(true);
        row.addView(l, new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView d = line(c, detail == null ? "" : detail, 15, regular, 0);
        d.setTextColor(colors(onAccent, muted, muted));
        d.setDuplicateParentStateEnabled(true);
        row.addView(d);
        return focusable(row, 1.02f, null);
    }

    static TextView detail(LinearLayout item) {
        return (TextView) item.getChildAt(1);
    }

    /** Linha navegável que fica isolada (um item só), com altura fixa. */
    static LinearLayout rowOf(Context c, View v, float h) {
        LinearLayout r = row(c);
        r.addView(v, new LinearLayout.LayoutParams(MATCH, px(h)));
        return r;
    }

    /** Linha navegável que deixa o item focado (ampliado) desenhar além dela: sem isso o zoom saía cortado. */
    static LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setTag(Nav.ROW);
        r.setClipChildren(false);
        r.setClipToPadding(false);
        return r;
    }

    /** Espaço fixo entre os itens de uma linha. */
    static void gap(LinearLayout row, float w) {
        GradientDrawable g = new GradientDrawable();
        g.setSize(px(w), px(w));
        g.setColor(0);
        row.setDividerDrawable(g);
        row.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
    }

    /**
     * Card com capa (ou letra), nome e subtítulo. Foco: sobe, cresce e ganha contorno na cor de destaque.
     * Com expand(), vira o card da Apple TV: estreito parado, e o focado se alarga suavemente.
     */
    static final class Card {
        final LinearLayout root;
        final FrameLayout thumb;
        final ImageView img;
        /** Só nos cards com expand(): imagem horizontal que aparece por cima da capa quando o card alarga. */
        ImageView wideImg;
        private int narrow, wide;
        private ValueAnimator resize;

        Card(Context c, int w, int h, String name, String sub, String letter) {
            root = new LinearLayout(c);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setClipChildren(false);
            // a largura fica no card inteiro; capa e textos acompanham (é ela que anima no expand)
            root.setLayoutParams(new LinearLayout.LayoutParams(px(w), WRAP));
            float r = radius(h);
            thumb = new FrameLayout(c);
            thumb.setBackground(round(surface, r));
            thumb.setClipToOutline(true);
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumb.addView(img, new FrameLayout.LayoutParams(MATCH, MATCH));
            if (letter != null) {
                TextView l = text(c, letter, 44, semi, text);
                thumb.addView(l, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
            }
            root.addView(thumb, new LinearLayout.LayoutParams(MATCH, px(h)));
            // conteúdo do Jellyfin (com subtítulo) sempre mostra o nome; apps seguem a opção "Nomes"
            if (names || sub != null) {
                TextView n = line(c, name, 18, semi, text);
                n.setPadding(0, px(10), 0, 0);
                root.addView(n, new LinearLayout.LayoutParams(MATCH, WRAP));
            }
            if (sub != null) root.addView(line(c, sub, 15, regular, muted), new LinearLayout.LayoutParams(MATCH, WRAP));
            Drawable ring = stroked(0, r, 4, accent);
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setOnFocusChangeListener((v, f) -> {
                thumb.setForeground(f ? ring : null);
                thumb.animate().translationZ(f ? px(16) : 0).setDuration(ANIM_MS).setInterpolator(EASE).start();
                if (wide > 0) {
                    // card de mídia: capa vertical parado; focado alarga e a imagem horizontal surge por cima
                    img.animate().alpha(f ? 1 : .6f).setDuration(ANIM_MS).setInterpolator(EASE).start();
                    wideImg.animate().alpha(f ? 1 : 0).setDuration(ANIM_MS).setInterpolator(EASE).start();
                    resizeTo(f ? wide : narrow);
                } else {
                    v.animate().scaleX(f ? 1.1f : 1).scaleY(f ? 1.1f : 1).translationY(f ? -px(4) : 0)
                            .setDuration(ANIM_MS).setInterpolator(EASE).start();
                }
            });
        }

        /** Estilo Apple TV: parado tem a largura narrow; focado se alarga até wide. */
        void expand(int narrowW, int wideW) {
            narrow = px(narrowW);
            wide = px(wideW);
            root.getLayoutParams().width = narrow;
            img.setAlpha(.6f);
            wideImg = new ImageView(root.getContext());
            wideImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            wideImg.setAlpha(0f);
            thumb.addView(wideImg, 1, new FrameLayout.LayoutParams(MATCH, MATCH));
        }

        private void resizeTo(int w) {
            if (resize != null) resize.cancel();
            resize = ValueAnimator.ofInt(root.getLayoutParams().width, w);
            resize.setDuration(ANIM_MS + 60);
            resize.setInterpolator(EASE);
            resize.addUpdateListener(a -> {
                root.getLayoutParams().width = (int) a.getAnimatedValue();
                root.requestLayout();
            });
            resize.start();
        }

        void star() {
            TextView s = text(root.getContext(), "★", 18, regular, 0xffffd54a);
            s.setShadowLayer(px(4), 0, px(1), 0xff000000);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
            lp.topMargin = px(8);
            lp.rightMargin = px(10);
            thumb.addView(s, lp);
        }

        void progress(float pct) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH, px(5), Gravity.BOTTOM);
            lp.leftMargin = lp.rightMargin = px(16);
            lp.bottomMargin = px(12);
            // proporção por peso: acompanha o card quando ele alarga
            LinearLayout bar = new LinearLayout(root.getContext());
            bar.setBackground(round(0x59ffffff, 3));
            bar.setWeightSum(100);
            View fill = new View(root.getContext());
            fill.setBackground(round(0xffffffff, 3));
            bar.addView(fill, new LinearLayout.LayoutParams(0, MATCH, Math.max(0, Math.min(100, pct))));
            thumb.addView(bar, lp);
        }
    }

    /**
     * Prateleira horizontal de borda a borda: vai até a beira da tela (margem negativa = margem da página),
     * então o card que rola para fora some na borda, e não cortado no meio da tela.
     */
    static LinearLayout shelf(Context c, LinearLayout parent) {
        HScroll hs = new HScroll(c);
        hs.setPadding(px(PAGE), px(16), px(PAGE), px(24));
        LinearLayout row = row(c);
        gap(row, 24);
        hs.addView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.leftMargin = lp.rightMargin = -px(PAGE);
        parent.addView(hs, lp);
        return row;
    }

    /** Rolagem com a curva EASE (não o "pulo" do Android); usada pela página e pelas prateleiras. */
    private static ValueAnimator glide(ValueAnimator old, int from, int to, ValueAnimator.AnimatorUpdateListener step) {
        if (old != null) old.cancel();
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(ANIM_MS + 80);
        a.setInterpolator(EASE);
        a.addUpdateListener(step);
        a.start();
        return a;
    }

    /** Rolagem sem o "pulo" automático do Android: quem decide quanto rolar é Nav.reveal. */
    static final class VScroll extends ScrollView {
        private ValueAnimator anim;

        VScroll(Context c) {
            super(c);
            // a rolagem nunca recebe foco (o ScrollView recebe por padrão e "engolia" o seletor)
            setFocusable(false);
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipToPadding(false);
        }

        void glideTo(int y) {
            anim = glide(anim, getScrollY(), y, a -> scrollTo(0, (int) a.getAnimatedValue()));
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }
    }

    static final class HScroll extends HorizontalScrollView {
        /** > 0: prateleira estilo Apple TV, o card focado fica sempre na margem (passo = card estreito + espaço). */
        int pinnedStep;
        private ValueAnimator anim;
        private Paint fade;

        HScroll(Context c) {
            super(c);
            setFocusable(false);
            setHorizontalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
            setClipToPadding(false);
            setClipChildren(false);
        }

        void glideTo(int x) {
            anim = glide(anim, getScrollX(), x, a -> scrollTo((int) a.getAnimatedValue(), 0));
        }

        /** Os cards que já passaram somem em degradê antes da margem, em vez de aparecerem cortados na borda. */
        @Override
        protected void dispatchDraw(Canvas c) {
            super.dispatchDraw(c);
            if (getScrollX() <= 0) return;
            int w = px(PAGE - 8);
            if (fade == null) {
                fade = new Paint();
                fade.setShader(new android.graphics.LinearGradient(0, 0, w, 0, bg, bg & 0xffffff, android.graphics.Shader.TileMode.CLAMP));
            }
            c.save();
            c.translate(getScrollX(), 0);
            c.drawRect(0, 0, w, getHeight(), fade);
            c.restore();
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
            return 0;
        }
    }
}
