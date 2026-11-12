package com.tencent.mobileqq.setting.search.node;

import android.content.Context;
import java.util.ArrayList;

public abstract class c {
    private final ArrayList<c> a = new ArrayList<>();

    public c() {
        ArrayList<c> initial = c();
        if (initial != null) {
            a.addAll(initial);
        }
    }

    public final void a(c searchNode) {
        a.add(searchNode);
    }

    public abstract ArrayList<c> c();

    public final ArrayList<c> d() {
        return a;
    }

    public abstract String e();

    public abstract void f(String title, Context context, String search);
}