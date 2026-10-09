package com.tencent.qqnt.patch.modules.chat;

import android.content.Context;
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord;
import com.tencent.qqnt.patch.IPatchModule;
import com.tencent.qqnt.patch.plugin.FloatingBallManager;

public class FloatingBallModule implements IPatchModule {
    @Override public String getId() { return "floating_ball"; }
    @Override public String getName() { return "脚本悬浮球快捷入口"; }

    @Override
    public void onInit(Context context) {
        FloatingBallManager.init(context);
    }

    @Override
    public void onAIOMsgItem(MsgRecord record) {
        FloatingBallManager.onAIOMsgItemBind(record);
    }

    @Override
    public void onAIOShow(Object delegate) {
        FloatingBallManager.onAIODelegateShow(delegate);
    }

    @Override
    public void onAIOHide() {
        FloatingBallManager.onAIODelegateHide();
    }

    @Override
    public void setEnabled(boolean enabled) {
        IPatchModule.super.setEnabled(enabled);
        FloatingBallManager.refreshVisibility();
    }
}
