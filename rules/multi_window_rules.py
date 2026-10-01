# -*- coding: utf-8 -*-
"""伪装非多窗口模式 (解除分屏限制) 动态规则引擎"""

import struct
from .parser import FastDexParser

def build_multi_window_rules(dex_data_dict):
    rules_list = []
    target_method_name = "isInMultiWindow"

    for dex_name, dex_bytes in dex_data_dict.items():
        if b"isInMultiWindow" not in dex_bytes:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        s_id = p.find_string_id(target_method_name)
        if s_id == -1:
            continue

        for c_idx in range(p.class_defs_size):
            # ★ 修复：必须以 4 字节 uint 完整解包 class_idx
            class_idx = struct.unpack_from('<I', p.data, p.class_defs_off + c_idx * 32)[0]
            cls_name = p.get_type_str(class_idx)

            # 只针对手 Q 官方代码域，跳过外部第三方库提升速度
            if not cls_name.startswith("Lcom/tencent/"):
                continue

            methods = p.get_class_methods(c_idx)
            for m_name, proto_desc, _, code_off, access_flags in methods:
                # 必须是有具体方法体的实现 (过滤抽象方法和接口)
                if code_off == 0:
                    continue

                if m_name == target_method_name and proto_desc == "()Z":
                    rules_list.append({
                        "name": f"动态解除分屏限制源头 ({cls_name}->isInMultiWindow)",
                        "target_class": cls_name,
                        "target_method": "isInMultiWindow()Z",
                        "type": "INSERT_BEFORE",
                        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isDisableSplitScreenLimit()Z
    move-result v0
    if-eqz v0, :cond_multi_pass
    const/4 v0, 0x0
    return v0
    :cond_multi_pass
"""
                    })

    return rules_list