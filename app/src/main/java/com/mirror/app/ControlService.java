package com.mirror.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

public class ControlService extends AccessibilityService {
    static volatile ControlService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    static boolean handle(JSONObject o) {
        ControlService s = instance;
        if (s == null) return false;
        s.run(o);
        return true;
    }

    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        DisplayManager dmgr = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display d = dmgr.getDisplay(Display.DEFAULT_DISPLAY);
        d.getRealMetrics(dm);
        return dm;
    }

    private float clamp(double v, float max) {
        return (float) Math.max(0, Math.min(max - 1, v));
    }

    private void run(JSONObject o) {
        String t = o.optString("t");
        DisplayMetrics dm = realMetrics();
        float w = dm.widthPixels;
        float h = dm.heightPixels;
        switch (t) {
            case "tap": {
                float x = clamp(o.optDouble("x") * w, w);
                float y = clamp(o.optDouble("y") * h, h);
                gesture(x, y, x, y, 60);
                break;
            }
            case "long": {
                float x = clamp(o.optDouble("x") * w, w);
                float y = clamp(o.optDouble("y") * h, h);
                gesture(x, y, x, y, 700);
                break;
            }
            case "swipe": {
                float x1 = clamp(o.optDouble("x1") * w, w);
                float y1 = clamp(o.optDouble("y1") * h, h);
                float x2 = clamp(o.optDouble("x2") * w, w);
                float y2 = clamp(o.optDouble("y2") * h, h);
                gesture(x1, y1, x2, y2, o.optLong("d", 300));
                break;
            }
            case "key":
                key(o.optString("k"));
                break;
            case "text":
                editText(o.optString("s"), false);
                break;
            case "del":
                editText("", true);
                break;
            default:
                break;
        }
    }

    private void gesture(float x1, float y1, float x2, float y2, long dur) {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription.StrokeDescription sd =
                new GestureDescription.StrokeDescription(p, 0, Math.max(1, dur));
        dispatchGesture(new GestureDescription.Builder().addStroke(sd).build(), null, null);
    }

    private void key(String k) {
        switch (k) {
            case "back":
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recents":
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case "notifs":
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case "lock":
                if (Build.VERSION.SDK_INT >= 28) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
                break;
            default:
                break;
        }
    }

    private void editText(String add, boolean deleteLast) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) return;
        CharSequence cur = f.getText();
        String s = (cur == null || f.isShowingHintText()) ? "" : cur.toString();
        String next;
        if (deleteLast) {
            next = s.isEmpty() ? "" : s.substring(0, s.length() - 1);
        } else {
            next = s + add;
        }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
    }
}
