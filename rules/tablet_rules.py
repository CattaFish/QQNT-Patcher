# -*- coding: utf-8 -*-
"""平板模式动态穿透规则引擎"""

from .parser import FastDexParser

def build_tablet_rule(dex_data_dict):
    """全动态嗅探 PadUtil 设备类型判断类与方法"""
    for _, dex_bytes in dex_data_dict.items():
        if b"initDeviceType type = " in dex_bytes or b"key_common_split_switch" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            candidate_classes = p.find_classes_referencing_string_strictly("initDeviceType type = ")
            if not candidate_classes:
                candidate_classes = p.find_classes_referencing_string_strictly("key_common_split_switch")

            for cls_name, cls_idx in candidate_classes:
                methods = p.get_class_methods(cls_idx)
                for m_name, proto_desc, is_virt, code_off, access_flags in methods:
                    is_static = bool(access_flags & 0x0008)
                    if is_static and proto_desc.startswith("(Landroid/content/Context;)L"):
                        return_type = proto_desc.split(')')[1]
                        target_method = f"{m_name}{proto_desc}"

                        return {
                            "name": f"强制平板模式动态穿透 ({cls_name}->{m_name})",
                            "target_class": cls_name,
                            "target_method": target_method,
                            "type": "INSERT_BEFORE",
                            "smali": f"""
    invoke-static {{}}, Lcom/tencent/qqnt/patch/PatchBridge;->isTabletModeEnabled()Z
    move-result v0
    if-eqz v0, :cond_tablet_pass
    sget-object v0, {return_type}->TABLET:{return_type}
    return-object v0
    :cond_tablet_pass
"""
                        }
    return None
# === 规则插件契约 ===
RULE_ID = "tablet"
RULE_NAME = "平板模式动态穿透"
RULE_ENABLED = True

def resolve_rules(dex_data_dict, meta=None):
    r = build_tablet_rule(dex_data_dict)
    return [r] if r else []
