package com.projetoros.launcher;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.util.ArrayList;
import java.util.List;

/**
 * Navegação pelo controle em linhas: cada ViewGroup marcado com ROW é uma linha, e os itens são os focáveis
 * dentro dela. ← → andam na linha, ↑ ↓ trocam de linha mantendo a coluna, e tudo é cíclico.
 */
final class Nav {
    static final Object ROW = new Object();
    private static final int[] XY = new int[2];

    static List<ViewGroup> rows(ViewGroup scope) {
        List<ViewGroup> out = new ArrayList<>();
        collect(scope, out);
        return out;
    }

    private static void collect(View v, List<ViewGroup> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v.getTag() == ROW) {
            if (!items((ViewGroup) v).isEmpty()) out.add((ViewGroup) v);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    static List<View> items(ViewGroup row) {
        List<View> out = new ArrayList<>();
        addItems(row, out);
        return out;
    }

    private static void addItems(ViewGroup g, List<View> out) {
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != View.VISIBLE) continue;
            if (v.isFocusable()) out.add(v);
            else if (v instanceof ViewGroup) addItems((ViewGroup) v, out);
        }
    }

    static boolean inside(View v, View ancestor) {
        for (ViewParent p = v == null ? null : v.getParent(); p != null; p = p.getParent()) if (p == ancestor) return true;
        return v == ancestor;
    }

    static int centerX(View v) {
        v.getLocationOnScreen(XY);
        return XY[0] + v.getWidth() / 2;
    }

    /** Item da linha mais perto, na horizontal, de quem estava focado. */
    static View nearest(ViewGroup row, View from) {
        View best = null;
        int x = from == null ? 0 : centerX(from), bestD = Integer.MAX_VALUE;
        for (View v : items(row)) {
            int d = Math.abs(centerX(v) - x);
            if (d < bestD) {
                bestD = d;
                best = v;
            }
        }
        return best;
    }

    /** Rola o que for preciso (prateleira e página) para o item focado ficar inteiro na tela. */
    static void reveal(View v) {
        for (ViewParent p = v.getParent(); p instanceof View; p = p.getParent()) {
            if (p instanceof Ui.HScroll) {
                Ui.HScroll s = (Ui.HScroll) p;
                ViewGroup row = (ViewGroup) s.getChildAt(0);
                if (s.pinnedStep > 0) {
                    // estilo Apple TV: o focado para na margem; os anteriores já terão encolhido, então a conta é exata
                    View item = v;
                    while (item.getParent() != row) item = (View) item.getParent();
                    s.glideTo(row.indexOfChild(item) * s.pinnedStep);
                    continue;
                }
                Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
                s.offsetDescendantRectToMyCoords(v, r);
                // o card focado nunca passa da margem da página (o padding da prateleira)
                int pad = s.getPaddingLeft(), x = s.getScrollX();
                if (r.left - pad < x) s.glideTo(r.left - pad);
                else if (r.right + pad > x + s.getWidth()) s.glideTo(r.right + pad - s.getWidth());
            } else if (p instanceof Ui.VScroll) {
                Ui.VScroll s = (Ui.VScroll) p;
                Rect r = new Rect(0, 0, v.getWidth(), v.getHeight());
                s.offsetDescendantRectToMyCoords(v, r);
                // em cima fica a barra das abas (92 px) e o título da linha (~50 px): os dois continuam visíveis
                int top = Ui.px(160), bottom = Ui.px(40), y = s.getScrollY();
                if (r.top - top < y) s.glideTo(Math.max(0, r.top - top));
                else if (r.bottom + bottom > y + s.getHeight()) s.glideTo(r.bottom + bottom - s.getHeight());
            }
        }
    }
}
