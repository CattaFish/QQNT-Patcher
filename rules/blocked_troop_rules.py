# -*- coding: utf-8 -*-
"""解除被封禁群聊进入提示与消息长按限制动态规则引擎"""

import struct
from .parser import FastDexParser

RULE_ID = "blocked_troop"
RULE_NAME = "查看被封禁群聊"
RULE_DESC = "解除被封禁群聊无法进入查看资料的限制"
RULE_ALIASES = ["blocked_troop", "封禁群", "查看封禁群", "解封群", "被封群"]
RULE_ENABLED = True


def build_blocked_troop_rules(dex_data_dict):
    rules = []

    # =========================================================================
    # 规则 1：解除进入被封禁群聊时的弹窗与不可读拦截 (TroopInfo->isUnreadableBlock)
    # =========================================================================
    target_class = None
    target_method = None
    expected_class = "Lcom/tencent/mobileqq/data/troop/TroopInfo;"
    method_name = "isUnreadableBlock"

    for _, dex_bytes in dex_data_dict.items():
        if b"isUnreadableBlock" not in dex_bytes:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        c_idx = p.find_class_index(expected_class)
        if c_idx != -1:
            for m_name, proto_desc, _, _, _ in p.get_class_methods(c_idx):
                if m_name == method_name and proto_desc == "()Z":
                    target_class = expected_class
                    target_method = f"{m_name}{proto_desc}"
                    break
        if target_class:
            break

        s_id = p.find_string_id(method_name)
        if s_id != -1:
            for idx in range(p.class_defs_size):
                for m_name, proto_desc, _, _, _ in p.get_class_methods(idx):
                    if m_name == method_name and proto_desc == "()Z":
                        class_idx = struct.unpack_from('<I', p.data, p.class_defs_off + idx * 32)[0]
                        cls_name = p.get_type_str(class_idx)
                        if "Troop" in cls_name:
                            target_class = cls_name
                            target_method = f"{m_name}{proto_desc}"
                            break
                if target_class:
                    break
        if target_class:
            break

    if not target_class:
        target_class = expected_class
    if not target_method:
        target_method = "isUnreadableBlock()Z"

    rules.append({
        "name": f"解除被封禁群聊查看限制 ({target_class}->{target_method.split('(')[0]})",
        "target_class": target_class,
        "target_method": target_method,
        "type": "INSERT_BEFORE",
        "smali": f"""
    const-string v0, "{RULE_ID}"
    invoke-static {{v0}}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_blocked_troop_pass
    const/4 v0, 0x0
    return v0
    :cond_blocked_troop_pass
"""
    })

    # =========================================================================
    # 规则 2：解除被封禁群聊中 AIO 消息长按菜单禁用 (AIOMsgItemBaseVM)
    # 精准拦截 GetTroopBlockState 判定结果 e$a->b()Z，零额外寄存器占用、零外溢影响
    # =========================================================================
    aio_vm_class = None
    aio_vm_method = None
    holder_proto = "(Lcom/tencent/qqnt/aio/holder/a;)V"

    for _, dex_bytes in dex_data_dict.items():
        if b"[isNeedSkipLongClick] isExit:" in dex_bytes or b"AIOMsgItemBaseVM" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            # 优先通过长按检测日志特征精确匹配
            candidates = p.find_classes_referencing_string_strictly("[isNeedSkipLongClick] isExit:")
            for cls_name, cls_idx in candidates:
                if "aio/msglist/holder/component/" in cls_name and "$" not in cls_name:
                    m = p.find_method_referencing_string(
                        cls_idx, "[isNeedSkipLongClick] isExit:", required_proto=holder_proto
                    ) or p.find_method_referencing_string(
                        cls_idx, "[isNeedSkipLongClick] isExit:"
                    )
                    if m:
                        aio_vm_class = cls_name
                        aio_vm_method = m
                        break

            # 次选：若调试日志被裁剪，则通过主日志 Tag + 参数签名兜底
            if not aio_vm_class:
                candidates = p.find_classes_referencing_string_strictly("AIOMsgItemBaseVM")
                for cls_name, cls_idx in candidates:
                    if "aio/msglist/holder/component/" in cls_name and "$" not in cls_name:
                        for m_name, proto_desc, _, _, _ in p.get_class_methods(cls_idx):
                            if proto_desc == holder_proto:
                                aio_vm_class = cls_name
                                aio_vm_method = f"{m_name}{proto_desc}"
                                break
                    if aio_vm_class:
                        break
        if aio_vm_class and aio_vm_method:
            break

    if not aio_vm_class:
        aio_vm_class = "Lcom/tencent/mobileqq/aio/msglist/holder/component/b;"
    if not aio_vm_method:
        aio_vm_method = f"K{holder_proto}"

    rules.append({
        "name": f"解除被封禁群聊消息长按限制 ({aio_vm_class}->{aio_vm_method.split('(')[0]})",
        "target_class": aio_vm_class,
        "target_method": aio_vm_method,
        "type": "REGEX_REPLACE",
        "regex": (
            r"(invoke-virtual(?:/range)?\s+\{[vp]\d+(?:\s*\.\.\s*[vp]\d+)?\},\s+"
            r"Lcom/tencent/mobileqq/aio/input/blockstate/[^;\s]+;->[^(\s]+\(\)Z"
            r"\s+move-result\s+([vp]\d+))"
        ),
        "smali": r"""\1
    invoke-static/range {\2 .. \2}, Lcom/tencent/qqnt/patch/modules/purify/BlockedTroopModule;->filterLongClickBlock(Z)Z
    move-result \2"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_blocked_troop_rules(dex_data_dict)