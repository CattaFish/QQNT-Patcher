package com.tencent.qqnt.patch.plugin;

import android.content.Intent;

import com.tencent.qqnt.patch.config.FeatureConfig;
import com.tencent.qqnt.patch.util.PLog;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class AioSessionTracker {

    private static final String TAG = "AioSession";

    private static WeakReference<Object> sCurrentAIODelegate = null;

    private static volatile int sActiveChatType = 0;
    private static volatile String sActivePeerUid = "";
    private static volatile String sActivePeerUin = "";
    private static volatile String sActivePeerName = "";
    private static volatile boolean sInAIO = false;

    public static void onAIOShow(Object delegate) {
        if (delegate == null) return;
        sCurrentAIODelegate = new WeakReference<>(delegate);
        sInAIO = true;

        refreshContactFromDelegate(delegate);

        if (FeatureConfig.has("script") && PluginManager.hasActivePlugins()) {
            PluginManager.dispatchChatInterface(sActiveChatType, sActivePeerUin, sActivePeerName);
        }
    }

    public static void onAIOHide() {
        sInAIO = false;
        sActiveChatType = 0;
        sActivePeerUid = "";
        sActivePeerUin = "";
        sActivePeerName = "";
        sCurrentAIODelegate = null;
    }

    public static void refreshFromCurrentDelegate() {
        WeakReference<Object> ref = sCurrentAIODelegate;
        Object delegate = (ref != null) ? ref.get() : null;
        if (delegate != null) {
            refreshContactFromDelegate(delegate);
        }
    }

    public static boolean isInAIO() { return sInAIO; }
    public static int getActiveChatType() { return sActiveChatType; }
    public static String getActivePeerUid() { return sActivePeerUid; }
    public static String getActivePeerUin() { return sActivePeerUin; }
    public static String getActivePeerName() { return sActivePeerName; }

    public static void refreshContactFromDelegate(Object delegate) {
        if (delegate == null) return;
        try {
            Class<?> clz = delegate.getClass();
            int chatType = 0;
            long peerUin = 0L;
            String peerId = "";
            String chatName = "";

            try {
                Field fD = clz.getDeclaredField("D");
                fD.setAccessible(true);
                peerUin = fD.getLong(delegate);

                Field fF = clz.getDeclaredField("F");
                fF.setAccessible(true);
                chatType = fF.getInt(delegate);

                Field fC = clz.getDeclaredField("C");
                fC.setAccessible(true);
                Object cVal = fC.get(delegate);
                if (cVal != null) peerId = cVal.toString();

                Field fE = clz.getDeclaredField("E");
                fE.setAccessible(true);
                Object eVal = fE.get(delegate);
                if (eVal != null) chatName = eVal.toString();
            } catch (Throwable ignored) {}

            if (peerUin == 0L || chatType == 0) {
                try {
                    Field fContainer = clz.getDeclaredField("d");
                    fContainer.setAccessible(true);
                    Object container = fContainer.get(delegate);
                    if (container != null) {
                        Method mGetIntent = container.getClass().getMethod("getIntent");
                        Intent intent = (Intent) mGetIntent.invoke(container);
                        if (intent != null) {
                            long iUin = intent.getLongExtra("key_peerUin", 0L);
                            int iType = intent.getIntExtra("key_chat_type", 0);
                            String iId = intent.getStringExtra("key_peerId");
                            String iName = intent.getStringExtra("key_chat_name");

                            if (iUin > 0L) peerUin = iUin;
                            if (iType != 0) chatType = iType;
                            if (iId != null && !iId.isEmpty()) peerId = iId;
                            if (iName != null && !iName.isEmpty()) chatName = iName;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            if (chatType != 0) {
                sActiveChatType = chatType;
            } else {
                sActiveChatType = (peerUin > 0L && String.valueOf(peerUin).length() >= 6) ? 2 : 1;
            }

            sActivePeerUid = peerId;
            sActivePeerName = chatName;

            if (peerUin > 0L) {
                sActivePeerUin = String.valueOf(peerUin);
            } else if (!peerId.isEmpty()) {
                if (sActiveChatType == 1 && peerId.startsWith("u_")) {
                    sActivePeerUin = MsgSender.getUinFromUid(peerId);
                } else {
                    sActivePeerUin = peerId;
                }
            } else {
                sActivePeerUin = "";
            }
        } catch (Throwable t) {
            PLog.e(TAG, "解析当前界面会话参数异常", t);
        }
    }
}