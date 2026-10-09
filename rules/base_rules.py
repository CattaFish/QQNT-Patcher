# -*- coding: utf-8 -*-
"""核心扩展与分流规则 (已打上 Feature/Bus 标签)"""

BASE_RULES = [
    {
        "name": "MSF 底层协议总线",
        "bus": "bus_msf",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;",
        "target_method": "onMsfPush(Ljava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V",
        "type": "REPLACE",
        "smali": """
.method public onMsfPush(Ljava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V
    .registers 10
    invoke-static {p0, p1, p2}, Lcom/tencent/qqnt/patch/PatchBridge;->handleMsfPush(Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession;Ljava/lang/String;[B)[B
    move-result-object p2
    if-nez p2, :cond_pass
    return-void
    :cond_pass
    iget-wide v1, p0, Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;->nativeRef:J
    move-object v0, p0
    move-object v3, p1
    move-object v4, p2
    move-object v5, p3
    invoke-direct/range {v0 .. v5}, Lcom/tencent/qqnt/kernel/nativeinterface/IQQNTWrapperSession$CppProxy;->native_onMsfPush(JLjava/lang/String;[BLcom/tencent/qqnt/kernel/nativeinterface/PushExtraInfo;)V
    return-void
.end method
"""
    },
    {
        "name": "QQ 原生二级设置页面挂载",
        "bus": "bus_setting",
        "target_class": "Lcom/tencent/mobileqq/setting/generalSetting/GeneralSettingFragment;",
        "target_method": "onViewCreated(Landroid/view/View;Landroid/os/Bundle;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    move-object/16 v1, p1
    move-object/16 v2, p2
    invoke-static {v0, v1, v2}, Lcom/tencent/qqnt/patch/ui/ZzzSettingFragment;->onHijackViewCreated(Ljava/lang/Object;Landroid/view/View;Landroid/os/Bundle;)Z
    move-result v0
    if-eqz v0, :cond_orig_general
    return-void
    :cond_orig_general
"""
    },
    {
        "name": "发送消息统一总线 (sendMsg)",
        "bus": "bus_send_msg",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgService$CppProxy;",
        "target_method": "sendMsg(JLcom/tencent/qqnt/kernelpublic/nativeinterface/Contact;Ljava/util/ArrayList;Ljava/util/HashMap;Lcom/tencent/qqnt/kernel/nativeinterface/IOperateCallback;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p4
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleSendMsg(Ljava/util/ArrayList;)V
"""
    },
    {
        "name": "AIO 气泡数据统一总线 (AIOMsgItem)",
        "bus": "bus_aio_msg",
        "target_class": "Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;",
        "target_method": "<init>(Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {p1}, Lcom/tencent/qqnt/patch/PatchBridge;->handleAIOMsgItem(Lcom/tencent/qqnt/kernel/nativeinterface/MsgRecord;)V
"""
    },
    {
        "name": "拉取消息列表统一总线 (n.a)",
        "bus": "bus_recv_msg",
        "target_class": "Lcom/tencent/qqnt/msg/n;",
        "target_method": "a(Ljava/util/ArrayList;)Ljava/util/ArrayList;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleRecvMsgList(Ljava/util/List;)V
"""
    },
    {
        "name": "闪照破解 (AIO 画廊大图放行 a.b)",
        "feature": "flash_pic",
        "target_class": "Lcom/tencent/qqnt/aio/gallery/fetch/a;",
        "target_method": "b(Ljava/util/List;)Ljava/util/List;",
        "type": "REGEX_REPLACE",
        "regex": r"sget-object\s+\w+,\s+Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\s+invoke-static\s+\{[^}]+\},\s+Lkotlin/jvm/internal/Intrinsics;->areEqual\(Ljava/lang/Object;Ljava/lang/Object;\)Z\s+move-result\s+([vp]\d+)",
        "smali": "\n    const/4 \\1, 0x0"
    },
    {
        "name": "闪照破解 (AIO 画廊大图放行 b.b)",
        "feature": "flash_pic",
        "target_class": "Lcom/tencent/qqnt/aio/gallery/fetch/b;",
        "target_method": "b(Ljava/util/List;)Ljava/util/List;",
        "type": "REGEX_REPLACE",
        "regex": r"sget-object\s+\w+,\s+Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;\s+invoke-static\s+\{[^}]+\},\s+Lkotlin/jvm/internal/Intrinsics;->areEqual\(Ljava/lang/Object;Ljava/lang/Object;\)Z\s+move-result\s+([vp]\d+)",
        "smali": "\n    const/4 \\1, 0x0"
    },
    {
        "name": "推送监听统一代理 (addKernelMsgListener)",
        "bus": "bus_recv_msg",
        "target_class": "Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgService$CppProxy;",
        "target_method": "addKernelMsgListener(Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;)J",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {p1}, Lcom/tencent/qqnt/patch/PatchBridge;->wrapKernelMsgListener(Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;)Lcom/tencent/qqnt/kernel/nativeinterface/IKernelMsgListener;
    move-result-object p1
"""
    },
    {
        "name": "AIO 会话开启总线 (AIODelegate.show)",
        "bus": "bus_aio_lifecycle",
        "target_class": "Lcom/tencent/qqnt/aio/activity/AIODelegate;",
        "target_method": "show()Landroid/view/View;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleAIOShow(Ljava/lang/Object;)V
"""
    },
    {
        "name": "AIO 会话关闭总线 (AIODelegate.hide)",
        "bus": "bus_aio_lifecycle",
        "target_class": "Lcom/tencent/qqnt/aio/activity/AIODelegate;",
        "target_method": "hide()V",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->handleAIOHide()V
"""
    },
    {
        "name": "AIO 气泡长按菜单挂载",
        "bus": "bus_aio_menu",
        "target_class": "Lcom/tencent/qqnt/aio/menu/ui/QQCustomMenuExpandableLayout;",
        "target_method": "setMenu(Lcom/tencent/qqnt/aio/menu/ui/c;Landroid/view/View;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    move-object/16 v1, p1
    move-object/16 v2, p2
    invoke-static {v0, v1, v2}, Lcom/tencent/qqnt/patch/plugin/AioMenuInjector;->onSetMenu(Ljava/lang/Object;Ljava/lang/Object;Landroid/view/View;)V
"""
    },
    {
        "name": "底层 MSF 响应消息统一分发总线 (dispatchRespMsg)",
        "bus": "bus_msf",
        "target_class": "Lmqq/app/msghandle/MsgRespHandler;",
        "target_method": "dispatchRespMsg(Lmqq/app/MobileQQ;Lcom/tencent/mobileqq/msf/sdk/MsfMessagePair;Lcom/tencent/mobileqq/msf/sdk/MsfRespHandleUtil;Lcom/tencent/mobileqq/msf/sdk/MsfServiceSdk;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p2
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleDispatchRespMsg(Ljava/lang/Object;)V
"""
    },
    {
        "name": "群成员退群监听",
        "bus": "bus_troop_member",
        "target_class": "Lcom/tencent/mobileqq/troop/api/impl/TroopMemberInfoServiceImpl;",
        "target_method": "deleteTroopMember(Ljava/lang/String;Ljava/lang/String;Z)Z",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p1
    move-object/16 v1, p2
    invoke-static {v0, v1}, Lcom/tencent/qqnt/patch/plugin/PluginManager;->dispatchTroopQuit(Ljava/lang/String;Ljava/lang/String;)V
"""
    },
    {
        "name": "群成员进群监听",
        "bus": "bus_troop_member",
        "target_class": "Lcom/tencent/qqnt/push/processor/TroopMemberAddPushProcessor;",
        "target_method": "a(Ljava/util/ArrayList;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p1
    invoke-static {v0}, Lcom/tencent/qqnt/patch/plugin/TroopMemberJoinHandler;->onPushReceive(Ljava/util/ArrayList;)V
"""
    },
    {
        "name": "静默 @全体成员 实时通知 (NotificationFacade.x)",
        "feature": "block_at_all",
        "target_class": "Lcom/tencent/qqnt/notification/NotificationFacade;",
        "target_method": "x(Lcom/tencent/qqnt/notification/NotificationFacade;Lmqq/app/AppRuntime;Lcom/tencent/qqnt/kernel/nativeinterface/MsgNotifyItem;ZLcom/tencent/qqnt/notification/trace/INotifyTracker;Lcom/tencent/qqnt/global/settings/notification/a;)Lcom/tencent/qqnt/notification/NotificationFacade$a$a;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p2
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->shouldDropMsgNotify(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_pass_x
    new-instance v0, Lcom/tencent/qqnt/notification/NotificationFacade$a$a;
    const/4 v1, 0x0
    const/4 v2, 0x0
    invoke-direct {v0, v1, v2}, Lcom/tencent/qqnt/notification/NotificationFacade$a$a;-><init>(Lcom/tencent/qqnt/notification/struct/d;Z)V
    return-object v0
    :cond_pass_x
"""
    },
    {
        "name": "静默 @全体成员 会话通知 (NotificationFacade.y)",
        "feature": "block_at_all",
        "target_class": "Lcom/tencent/qqnt/notification/NotificationFacade;",
        "target_method": "y(Lcom/tencent/qqnt/notification/NotificationFacade;Lmqq/app/AppRuntime;Lcom/tencent/qqnt/kernel/nativeinterface/RecentContactInfo;Lcom/tencent/qqnt/kernel/nativeinterface/NotificationCommonInfo;ZLcom/tencent/qqnt/notification/trace/INotifyTracker;Lcom/tencent/qqnt/global/settings/notification/a;)Lcom/tencent/qqnt/notification/NotificationFacade$a$a;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p2
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->shouldDropRecentContact(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_pass_y
    new-instance v0, Lcom/tencent/qqnt/notification/NotificationFacade$a$a;
    const/4 v1, 0x0
    const/4 v2, 0x0
    invoke-direct {v0, v1, v2}, Lcom/tencent/qqnt/notification/NotificationFacade$a$a;-><init>(Lcom/tencent/qqnt/notification/struct/d;Z)V
    return-object v0
    :cond_pass_y
"""
    },
    {
        "name": "经典群文件列表显示下载次数 (TroopFileShowAdapter.getView)",
        "feature": "group_file",
        "target_class": "Lcom/tencent/mobileqq/troop/file/data/TroopFileShowAdapter;",
        "target_method": "getView(ILandroid/view/View;Landroid/view/ViewGroup;)Landroid/view/View;",
        "type": "REGEX_REPLACE",
        "regex": r"return-object\s+([vp]\d+)(?=\s*(?:\.end\s+method|$))",
        "smali": """
    move-object/16 v0, \\1
    move-object/16 v1, p0
    move/16 v2, p1
    invoke-static {v0, v1, v2}, Lcom/tencent/qqnt/patch/PatchBridge;->handleTroopFileGetView(Landroid/view/View;Ljava/lang/Object;I)V
    return-object v0"""
    }
]

RULE_ID = "base"
RULE_NAME = "核心基础总线规则"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    return list(BASE_RULES)
