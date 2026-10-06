# -*- coding: utf-8 -*-
"""内置浏览器网页拦截解除动态规则引擎 (自适应 WebSecurityPluginV2 内部类混淆)"""

import struct
from .parser import FastDexParser

def build_browser_rules(dex_data_dict):
    rules = []
    target_string = "jumpResult"

    for _, dex_bytes in dex_data_dict.items():
        if b"jumpResult" not in dex_bytes:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        candidates = p.find_classes_referencing_string_strictly(target_string)
        for cls_name, cls_idx in candidates:
            # 聚焦在 webview 安全检测回调类上 (如 WebSecurityPluginV2$a 或 WebSecurityPluginV2$1)
            if "WebSecurity" in cls_name or "webview" in cls_name:
                methods = p.get_class_methods(cls_idx)
                for m_name, proto_desc, _, code_off, _ in methods:
                    if code_off == 0 or code_off + 16 >= len(p.data):
                        continue

                    # 寻找参数为 Bundle 且返回 void 的回调方法 (callback(Landroid/os/Bundle;)V)
                    if proto_desc == "(Landroid/os/Bundle;)V":
                        target_method = f"{m_name}{proto_desc}"
                        rules.append({
                            "name": f"内置浏览器拦截放行 ({cls_name}->{m_name})",
                            "target_class": cls_name,
                            "target_method": target_method,
                            "type": "INSERT_BEFORE",
                            "smali": """
    move-object/16 v0, p1
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->handleWebSecurityCallback(Ljava/lang/Object;)V
"""
                        })
                        return rules
    return rules

# === 规则插件契约 ===
RULE_ID = "browser_mitigation"
RULE_NAME = "禁用内置浏览器网页拦截"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    return build_browser_rules(dex_data_dict)