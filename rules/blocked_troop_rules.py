# -*- coding: utf-8 -*-
"""解除被封禁群聊资料查看限制动态规则引擎"""

import struct
from .parser import FastDexParser

RULE_ID = "blocked_troop"
RULE_NAME = "查看被封禁群聊"
RULE_DESC = "解除被封禁群聊无法进入查看资料的限制"
RULE_ALIASES = ["blocked_troop", "封禁群", "查看封禁群", "解封群", "被封群"]
RULE_ENABLED = True

def build_blocked_troop_rules(dex_data_dict):
    rules = []
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

        # 1. 优先在标准 TroopInfo 类中精确定位方法
        c_idx = p.find_class_index(expected_class)
        if c_idx != -1:
            methods = p.get_class_methods(c_idx)
            for m_name, proto_desc, _, _, _ in methods:
                if m_name == method_name and proto_desc == "()Z":
                    target_class = expected_class
                    target_method = f"{m_name}{proto_desc}"
                    break
        if target_class:
            break

        # 2. 兜底策略：若类路径发生变动，动态检索包含该特征方法的类
        s_id = p.find_string_id(method_name)
        if s_id != -1:
            for idx in range(p.class_defs_size):
                methods = p.get_class_methods(idx)
                for m_name, proto_desc, _, _, _ in methods:
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

    # INSERT_BEFORE：开关开启时拦截并返回 false (0x0)，关闭时直接跳到原逻辑执行
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

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_blocked_troop_rules(dex_data_dict)