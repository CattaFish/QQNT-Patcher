# -*- coding: utf-8 -*-
"""
静默 @全体成员 实时通知与会话通知动态规则引擎
基于 FastDexParser 语义特征全动态嗅探，自动适应混淆类名与方法名变动。
"""

from .parser import FastDexParser

RULE_ID = "block_at_all"
RULE_NAME = "静默 @全体成员 通知"
RULE_ENABLED = True


def build_block_at_all_rules(dex_data_dict):
    rules = []
    target_class = "Lcom/tencent/qqnt/notification/NotificationFacade;"

    for _, dex_bytes in dex_data_dict.items():
        if b"NotificationFacade" not in dex_bytes:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        c_idx = p.find_class_index(target_class)
        if c_idx == -1:
            continue

        methods = p.get_class_methods(c_idx)
        found_notify = False
        found_contact = False

        for m_name, proto_desc, is_virt, code_off, access_flags in methods:
            # 必须是静态方法 (static)
            is_static = bool(access_flags & 0x0008)
            if not is_static:
                continue

            # 动态获取返回值类型 (如 Lcom/tencent/qqnt/notification/NotificationFacade$a$a;)
            ret_type = proto_desc.split(')')[1] if ')' in proto_desc else "Lcom/tencent/qqnt/notification/NotificationFacade$a$a;"

            # 1. 嗅探实时通知方法（老版 x / 新版 w）：参数特征为 MsgNotifyItem
            if not found_notify and "Lcom/tencent/qqnt/kernel/nativeinterface/MsgNotifyItem;" in proto_desc:
                full_method = f"{m_name}{proto_desc}"
                rules.append({
                    "name": f"静默 @全体成员 实时通知 (NotificationFacade->{m_name})",
                    "feature": "block_at_all",
                    "target_class": target_class,
                    "target_method": full_method,
                    "type": "INSERT_BEFORE",
                    "smali": f"""
    move-object/16 v0, p2
    invoke-static {{v0}}, Lcom/tencent/qqnt/patch/PatchBridge;->shouldDropMsgNotify(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_pass_notify
    new-instance v0, {ret_type}
    const/4 v1, 0x0
    const/4 v2, 0x0
    invoke-direct {{v0, v1, v2}}, {ret_type}-><init>(Lcom/tencent/qqnt/notification/struct/d;Z)V
    return-object v0
    :cond_pass_notify
"""
                })
                found_notify = True

            # 2. 嗅探会话通知方法（老版 y / 新版 x）：参数特征为 RecentContactInfo
            if not found_contact and "Lcom/tencent/qqnt/kernel/nativeinterface/RecentContactInfo;" in proto_desc:
                full_method = f"{m_name}{proto_desc}"
                rules.append({
                    "name": f"静默 @全体成员 会话通知 (NotificationFacade->{m_name})",
                    "feature": "block_at_all",
                    "target_class": target_class,
                    "target_method": full_method,
                    "type": "INSERT_BEFORE",
                    "smali": f"""
    move-object/16 v0, p2
    invoke-static {{v0}}, Lcom/tencent/qqnt/patch/PatchBridge;->shouldDropRecentContact(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_pass_contact
    new-instance v0, {ret_type}
    const/4 v1, 0x0
    const/4 v2, 0x0
    invoke-direct {{v0, v1, v2}}, {ret_type}-><init>(Lcom/tencent/qqnt/notification/struct/d;Z)V
    return-object v0
    :cond_pass_contact
"""
                })
                found_contact = True

            if found_notify and found_contact:
                break

        if found_notify or found_contact:
            break

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_block_at_all_rules(dex_data_dict)