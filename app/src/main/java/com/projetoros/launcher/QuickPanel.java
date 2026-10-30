package com.projetoros.launcher;

import android.content.Context;
import android.widget.Toast;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Painel rápido: gaveta translúcida à direita (sem blur, pesado na GPU do projetor). */
final class QuickPanel extends LinearLayout {
    static final int WIDTH = 400;
    private final Context act;
    /** true na camada por cima de outro app (KeyService); false dentro do launcher. */
    private final boolean overlay;
    private final Sys sys;
    private final Runnable onClose;
    private final TextView status;
    private final LinearLayout remote, out, night;
    /** Itens ajustados com ◀ ▶: v (trapézio vertical), h (horizontal), z (zoom). */
    private final Map<View, Character> adjust = new HashMap<>();

    QuickPanel(Context act, Runnable onClose, boolean overlay) {
        super(act);
        this.act = act;
        this.onClose = onClose;
        this.overlay = overlay;
        sys = Sys.get(act);
        setOrientation(VERTICAL);
        setBackgroundColor(0xe6080c12);
        setPadding(Ui.px(24), Ui.px(26), Ui.px(24), 0);
        TextView title = Ui.text(act, "Ajustes rápidos", 22, Ui.semi, Ui.text);
        title.setPadding(Ui.px(18), 0, 0, 0);
        addView(title);
        status = Ui.text(act, "", 15, Ui.regular, Ui.muted);
        status.setPadding(Ui.px(18), Ui.px(6), 0, Ui.px(4));
        addView(status);

        label("IMAGEM");
        adjustable("Trapézio vertical", 'v');
        adjustable("Trapézio horizontal", 'h');
        adjustable("Zoom", 'z');
        add(Ui.item(act, "Restaurar imagem", null), v -> sys.sh("keystone", "reset", null));
        label("CELULAR");
        remote = add(Ui.item(act, "Reiniciar rede", ""), v -> sys.sh("wifi", "", (ok, o) -> {
            if (ok) toast("Reiniciando o Wi-Fi... o celular reconecta em alguns segundos");
        }));
        label("ÁUDIO");
        out = add(Ui.item(act, "Saída", ""), v -> {
            sys.toggleOut();
            postDelayed(this::refresh, 3000);
        });
        night = add(Ui.item(act, "Modo noturno", ""), v -> {
            sys.night(!sys.status().night);
            refresh();
        });
        label("DESEMPENHO");
        // por cima de outro app o painel fecha antes da limpeza; o resultado vem num aviso do Android
        add(Ui.item(act, "Liberar memória", null), v -> {
            toast("Liberando memória...");
            sys.sh("clean", "", null);
            if (overlay) onClose.run();
        });
    }

    private void toast(String msg) {
        if (act instanceof TvActivity) ((TvActivity) act).toast(msg);
        else Toast.makeText(act, msg, Toast.LENGTH_SHORT).show();
    }

    private void label(String s) {
        TextView t = Ui.text(getContext(), s, 13, Ui.semi, Ui.muted);
        t.setLetterSpacing(.1f);
        t.setPadding(Ui.px(18), Ui.px(14), 0, Ui.px(4));
        addView(t);
    }

    private LinearLayout add(LinearLayout item, OnClickListener click) {
        item.setOnClickListener(click);
        addView(Ui.rowOf(getContext(), item, 48), new LayoutParams(Ui.MATCH, Ui.WRAP));
        return item;
    }

    private void adjustable(String label, char kind) {
        adjust.put(add(Ui.item(act, label, "◀   ▶"), null), kind);
    }

    /** ← → num item de imagem ajustam o keystone em vez de navegar. */
    boolean arrow(View focused, int dx) {
        Character k = adjust.get(focused);
        if (k == null || dx == 0) return false;
        sys.sh("keystone", k + (dx > 0 ? "+" : "-"), null);
        return true;
    }

    /** Primeiro item (Trapézio vertical): título, status e rótulo vêm antes. */
    View first() {
        return ((LinearLayout) getChildAt(3)).getChildAt(0);
    }

    void refresh() {
        Sys.Status s = sys.status();
        status.setText(Sys.statusLine(s));
        Ui.detail(out).setText(s.out.isEmpty() ? "Alto-falante" : s.out);
        Ui.detail(night).setText(s.night ? "Ligado" : "Desligado");
        sys.sh("remote", "", (ok, n) -> Ui.detail(remote).setText(!ok ? "" : n.equals("0") ? "sem celular" : "celular conectado"));
    }
}
