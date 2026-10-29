# -*- coding: utf-8 -*-
"""伪装非多窗口模式 (解除分屏限制) 动态规则引擎"""

import struct
from .parser import FastDexParser

RULE_ID = "multi_window"
RULE_NAME = "伪装非多窗口模式 (解除分屏限制)"
RULE_DESC = "伪装非多窗口模式 (解除分屏限制)"
RULE_ALIASES = ["split", "分屏", "多窗口"]
RULE_ENABLED = True

def build_multi_window_rules(dex_data_dict):
    rules_list = []
    target_method_name = "isInMultiWindow"

    for dex_name, dex_bytes in dex_data_dict.items():
        if b"isInMultiWindow" not in dex_bytes: continue
        p = FastDexParser(dex_bytes)
        if not p.valid: continue

        s_id = p.find_string_id(target_method_name)
        if s_id == -1: continue

        for c_idx in range(p.class_defs_size):
            class_idx = struct.unpack_from('<I', p.data, p.class_defs_off + c_idx * 32)[0]
            cls_name = p.get_type_str(class_idx)

            if not cls_name.startswith("Lcom/tencent/"): continue

            methods = p.get_class_methods(c_idx)
            for m_name, proto_desc, _, code_off, access_flags in methods:
                if code_off == 0: continue

                if m_name == target_method_name and proto_desc == "()Z":
                    rules_list.append({
                        "name": f"动态解除分屏限制源头 ({cls_name}->isInMultiWindow)",
                        "target_class": cls_name,
                        "target_method": "isInMultiWindow()Z",
                        "type": "INSERT_BEFORE",
                        "smali": """
    const-string v0, "multi_window"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_multi_pass
    const/4 v0, 0x0
    return v0
    :cond_multi_pass
"""
                    })

    return rules_list

def resolve_rules(dex_data_dict, meta=None):
    return build_multi_window_rules(dex_data_dict)
