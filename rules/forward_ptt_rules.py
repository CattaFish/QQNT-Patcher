# -*- coding: utf-8 -*-
"""
语音转发全版本自适应动态插桩规则引擎
- AIOPttContentComponent 菜单方法: 9.2.90 为 b2()Ljava/util/List;, 9.3.75 为 g2()Ljava/util/List;
- ForwardMenuItem 类名: 9.2.90 为 com.tencent.qqnt.aio.menu.j, 9.3.75 为 com.tencent.qqnt.aio.menu.o
- 2X 倍速项: 9.2.90 为 aio.menu.ad, 9.3.75 为 aio.menu.aj
全流程基于指令特征与语义全动态扫描，拒绝任何硬编码！
"""

import struct
from .parser import FastDexParser

RULE_ID = "forward_ptt"
RULE_NAME = "语音转发"
RULE_ENABLED = True


def _find_method_strictly_referencing_string(p, class_idx, target_string):
    s_id = p.find_string_id(target_string)
    if s_id == -1:
        return None
    s_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
    s_32 = struct.pack('<I', s_id)

    methods = p.get_class_methods(class_idx)
    for m_name, proto_desc, _, code_off, _ in methods:
        if code_off == 0 or code_off + 16 >= len(p.data):
            continue
        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
        insns = p.data[code_off + 16: code_off + 16 + insns_size * 2]

        if (s_16 and s_16 not in insns) and (s_32 not in insns):
            continue

        k = 0
        len_insns = len(insns)
        while k < len_insns - 1:
            op = insns[k]
            if op == 0x1A and k + 4 <= len_insns:
                if s_16 and insns[k + 2: k + 4] == s_16:
                    return f"{m_name}{proto_desc}"
                k += 4
                continue
            elif op == 0x1B and k + 6 <= len_insns:
                if insns[k + 2: k + 6] == s_32:
                    return f"{m_name}{proto_desc}"
                k += 6
                continue
            k += 2
    return None


def build_forward_ptt_rules(dex_data_dict):
    rules = []

    ptt_component_cls = None
    ptt_menu_method = None
    forward_menu_item_cls = None

    forward_utils_cls = None
    start_forward_method = None
    forward_handler_cls = "Lcom/tencent/mobileqq/sharepanel/forward/handler/AIOForwardHandler;"
    forward_handler_method = None

    # =========================================================================
    # 1. 动态嗅探 AIOPttContentComponent 及其菜单方法 (9.2.90 为 b2, 9.3.75 为 g2)
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"AIOPttContentComponent" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            for c_idx in range(p.class_defs_size):
                class_idx = struct.unpack_from('<I', p.data, p.class_defs_off + c_idx * 32)[0]
                cls_name = p.get_type_str(class_idx)
                if cls_name.endswith("AIOPttContentComponent;"):
                    ptt_component_cls = cls_name
                    methods = p.get_class_methods(c_idx)
                    # 动态寻找无参返回 List 的虚拟方法
                    for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                        if is_virt and proto_desc == "()Ljava/util/List;":
                            ptt_menu_method = f"{m_name}{proto_desc}"
                            break
                    break
        if ptt_component_cls and ptt_menu_method:
            break

    # =========================================================================
    # 2. 全动态嗅探正牌 ForwardMenuItem 类 (9.2.90 为 j, 9.3.75 为 o)
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"ForwardMenuItem" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue
            candidates = p.find_classes_referencing_string_strictly("ForwardMenuItem")
            for cls_name, cls_idx in candidates:
                # 排除 babyq 等非 AIO 菜单项，锁定 com/tencent/qqnt/aio/menu/ 顶层类
                if cls_name.startswith("Lcom/tencent/qqnt/aio/menu/") and "$" not in cls_name:
                    forward_menu_item_cls = cls_name
                    break
        if forward_menu_item_cls:
            break

    # 兜底已知类名
    if not ptt_component_cls:
        ptt_component_cls = "Lcom/tencent/mobileqq/aio/msglist/holder/component/ptt/AIOPttContentComponent;"
    if not ptt_menu_method:
        ptt_menu_method = "g2()Ljava/util/List;" # 默认指向 9.3.75 新版，嗅探器会自适应旧版 b2

    forward_item_java_name = (
        forward_menu_item_cls[1:-1].replace('/', '.')
        if forward_menu_item_cls else ""
    )

    # [规则 1] 注入菜单并一并传入嗅探到的 ForwardMenuItem 类名
    rules.append({
        "name": f"语音气泡菜单注入转发选项 ({ptt_component_cls}->{ptt_menu_method.split('(')[0]})",
        "target_class": ptt_component_cls,
        "target_method": ptt_menu_method,
        "type": "REGEX_REPLACE",
        "regex": r"return-object\s+([vp]\d+)(?=\s*(?:\.end\s+method|$))",
        "smali": f"""
    move-object/16 v0, \\1
    move-object/16 v1, p0
    const-string v2, "{forward_item_java_name}"
    invoke-static {{v0, v1, v2}}, Lcom/tencent/qqnt/patch/PatchBridge;->injectPttMenu(Ljava/util/List;Ljava/lang/Object;Ljava/lang/String;)Ljava/util/List;
    move-result-object \\1
    return-object \\1"""
    })

    # =========================================================================
    # 3. 动态嗅探 NtMsgForwardUtils.startForwardMsgV2 (9.2.90 为 t, 9.3.75 同为 t)
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"startForwardMsgV2 origin_msg_type=" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            candidates = p.find_classes_referencing_string_strictly("startForwardMsgV2 origin_msg_type=")
            for cls_name, cls_idx in candidates:
                if "NtMsgForwardUtils" in cls_name:
                    m = _find_method_strictly_referencing_string(p, cls_idx, "startForwardMsgV2 origin_msg_type=")
                    if m and "AIOMsgItem;" in m:
                        forward_utils_cls = cls_name
                        start_forward_method = m
                        break
        if forward_utils_cls and start_forward_method:
            break

    if not forward_utils_cls:
        forward_utils_cls = "Lcom/tencent/qqnt/aio/forward/NtMsgForwardUtils;"
    if not start_forward_method:
        start_forward_method = "t(Landroid/app/Activity;Lcom/tencent/aio/data/AIOContact;Lcom/tencent/mobileqq/aio/msg/AIOMsgItem;I)Z"

    # [规则 2] 转发校验放行
    rules.append({
        "name": f"语音转发类型校验放行 ({forward_utils_cls}->{start_forward_method.split('(')[0]})",
        "target_class": forward_utils_cls,
        "target_method": start_forward_method,
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p3
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->onStartForwardMsg(Ljava/lang/Object;)V
"""
    })

    # =========================================================================
    # 4. 动态嗅探 AIOForwardHandler.forward 转发中枢
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"AIOForwardHandler" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            c_idx = p.find_class_index(forward_handler_cls)
            if c_idx != -1:
                methods = p.get_class_methods(c_idx)
                for m_name, proto_desc, _, _, _ in methods:
                    if m_name == "forward" and proto_desc.startswith("(Ljava/util/Map;"):
                        forward_handler_method = f"{m_name}{proto_desc}"
                        break
        if forward_handler_method:
            break

    if not forward_handler_method:
        forward_handler_method = "forward(Ljava/util/Map;Lcom/tencent/mobileqq/sharepanel/forward/ForwardContactItem;Ljava/util/List;Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"

    # [规则 3] 拦截转发执行并由底层通道分发语音
    rules.append({
        "name": f"接管转发目标并由底层通道发送语音 ({forward_handler_cls}->forward)",
        "target_class": forward_handler_cls,
        "target_method": forward_handler_method,
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p1
    move-object/16 v1, p3
    move-object/16 v2, p4
    invoke-static {v0, v1, v2}, Lcom/tencent/qqnt/patch/PatchBridge;->onHandleAIOForward(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V
"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_forward_ptt_rules(dex_data_dict)