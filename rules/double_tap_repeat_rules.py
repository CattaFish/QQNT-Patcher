# -*- coding: utf-8 -*-
"""
消息复读动态规则引擎
- 方案 1 (左滑复读): 拦截 AIOContentLeftSwipeHelper.j 与 AIOLeftSwipeAreaComponent.G1
- 方案 2 (+1 图标复读): 动态嗅探 AIOMsgItem.N0() 源头唤醒官方图标，接管 a.onClick 支持文本/图片/视频全能复读
"""

import struct
from .parser import FastDexParser

RULE_ID = "double_tap_repeat"
RULE_NAME = "消息复读"
RULE_ENABLED = True


def build_double_tap_repeat_rules(dex_data_dict):
    rules = []

    helper_cls = "Lcom/tencent/mobileqq/aio/msglist/holder/component/leftswipearea/AIOContentLeftSwipeHelper;"
    left_swipe_component_cls = "Lcom/tencent/mobileqq/aio/msglist/holder/component/leftswipearea/AIOLeftSwipeAreaComponent;"
    msg_follow_component_cls = "Lcom/tencent/mobileqq/aio/msglist/holder/component/msgfollow/AIOMsgFollowComponent;"
    msg_item_cls = "Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;"
    follow_click_listener_cls = "Lcom/tencent/mobileqq/aio/msglist/holder/component/msgfollow/a;"

    # ==================== 方案 1: 左滑复读 ====================
    rules.append({
        "name": "强制所有消息允许左滑 (AIOContentLeftSwipeHelper->j)",
        "target_class": helper_cls,
        "target_method": "j(Z)V",
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isSwipeRepeatEnabled()Z
    move-result v0
    if-eqz v0, :cond_pass_enable_swipe
    const/4 p1, 0x1
    :cond_pass_enable_swipe
"""
    })

    rules.append({
        "name": "左滑松手触发复读 (AIOLeftSwipeAreaComponent->G1)",
        "target_class": left_swipe_component_cls,
        "target_method": "G1(I)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    move/16 v1, p1
    invoke-static {v0, v1}, Lcom/tencent/qqnt/patch/PatchBridge;->onLeftSwipeTrigger(Ljava/lang/Object;I)Z
    move-result v0
    if-eqz v0, :cond_pass_swipe_trigger
    return-void
    :cond_pass_swipe_trigger
"""
    })

    # ==================== 方案 2: +1 图标复读 ====================
    n0_method_name = "N0"

    # 动态探测绑定方法中实际调用的 AIOMsgItem 的方法名
    for _, dex_bytes in dex_data_dict.items():
        if b"AIOMsgFollowComponent" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue
            c_idx = p.find_class_index(msg_follow_component_cls)
            if c_idx != -1:
                methods = p.get_class_methods(c_idx)
                for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                    if is_virt and proto_desc.startswith("(I") and proto_desc.endswith("Ljava/util/List;)V"):
                        param_str = proto_desc[1:proto_desc.rfind(')')]
                        if param_str.count(';') == 2 and param_str.startswith("IL"):
                            if code_off != 0 and code_off + 16 < len(p.data):
                                insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
                                insns = p.data[code_off + 16: code_off + 16 + insns_size * 2]
                                k = 0
                                len_insns = len(insns)
                                while k < len_insns - 3:
                                    op = insns[k]
                                    if op in (0x6E, 0x6F):
                                        m_ref = struct.unpack_from('<H', insns, k + 2)[0]
                                        if m_ref < p.method_ids_size:
                                            c_call_idx, proto_call_idx, name_call_idx = struct.unpack_from(
                                                '<HHI', p.data, p.method_ids_off + m_ref * 8
                                            )
                                            called_cls = p.get_type_str(c_call_idx)
                                            called_name = p.get_string(name_call_idx)
                                            called_proto = p.get_proto_desc(proto_call_idx)
                                            if called_cls == msg_item_cls and called_proto == "()Z":
                                                n0_method_name = called_name
                                                break
                                    k += 2
                            break
            if n0_method_name != "N0":
                break

    target_n0_method = f"{n0_method_name}()Z"

    # [规则 2.1 核心大闸门] 源头激活图标显示
    rules.append({
        "name": f"源头激活官方+1跟随资格 ({msg_item_cls}->{n0_method_name})",
        "target_class": msg_item_cls,
        "target_method": target_n0_method,
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isPlusOneModeActive()Z
    move-result v0
    if-eqz v0, :cond_pass_n0
    const/4 v0, 0x1
    return v0
    :cond_pass_n0
"""
    })

    # [规则 2.2 原生监听拦截] 接管点击，支持图片、视频、文本全类型复读发包
    rules.append({
        "name": f"接管官方+1图标原生点击 ({follow_click_listener_cls}->onClick)",
        "target_class": follow_click_listener_cls,
        "target_method": "onClick(Landroid/view/View;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->onFollowIconClick(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_pass_native_click
    return-void
    :cond_pass_native_click
"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_double_tap_repeat_rules(dex_data_dict)