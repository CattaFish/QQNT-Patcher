package com.tencent.qqnt.patch.plugin;

import android.view.View;
import com.tencent.mobileqq.aio.msg.AIOMsgItem;
import com.tencent.qqnt.aio.menu.ui.f;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.patch.util.PLog;
import me.yxp.qfun.plugin.bean.MsgData;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

public class AioMenuInjector {

    private static final String TAG = "AioMenu";

    @SuppressWarnings("unchecked")
    public static void onSetMenu(Object layoutObj, Object customMenuObj, View sourceView) {
        if (customMenuObj == null) return;
        try {
            List<Object> itemsList = findItemList(customMenuObj);
            if (itemsList == null || itemsList.isEmpty()) return;

            Object firstItem = itemsList.get(0);
            if (!(firstItem instanceof f)) return;
            AIOMsgItem aioMsgItem = ((f) firstItem).d();
            if (aioMsgItem == null) return;

            MsgRecord record = aioMsgItem.getMsgRecord();
            if (record == null) return;
            int currentMsgType = record.msgType;

            Map<String, PluginCompiler.MsgMenuItemInfo> registered = PluginCompiler.getAllMsgMenuItems();
            if (registered.isEmpty()) return;

            for (PluginCompiler.MsgMenuItemInfo info : registered.values()) {
                if (info.msgTypes != null && info.msgTypes.length > 0) {
                    boolean match = false;
                    for (int t : info.msgTypes) {
                        if (t == currentMsgType) { match = true; break; }
                    }
                    if (!match) continue;
                }

                // 插入脚本自定义长按气泡选项
                ScriptMenuItem newItem = new ScriptMenuItem(aioMsgItem, info.name, () -> {
                    PluginManager.invokeMsgMenuItem(info.pluginId, info.callback, new MsgData(record));
                });
                itemsList.add(0, newItem);
                PLog.d(TAG, "气泡注入长按菜单项: " + info.name);
            }
        } catch (Throwable t) {
            PLog.e(TAG, "onSetMenu 注入异常", t);
        }
    }

    private static List<Object> findItemList(Object menuObj) {
        Class<?> cur = menuObj.getClass();
        while (cur != null && cur != Object.class) {
            for (Field field : cur.getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        return (List<Object>) field.get(menuObj);
                    } catch (Throwable ignored) {}
                }
            }
            cur = cur.getSuperclass();
        }
        return null;
    }

    public static class ScriptMenuItem extends f {
        private final String title;
        private final Runnable onClickAction;

        public ScriptMenuItem(AIOMsgItem item, String title, Runnable action) {
            super(item);
            this.title = title;
            this.onClickAction = action;
        }

        @Override public int b() { return 0x7f081f1e; }
        @Override public int c() { return View.generateViewId(); }
        @Override public String e() { return "ScriptMenuItem"; }
        @Override public String f() { return title; }
        @Override public void h() {
            if (onClickAction != null) onClickAction.run();
        }
    }
}
