# -*- coding: utf-8 -*-
"""登录页底部操作栏 Zzz 设置入口动态规则"""

import struct
from .parser import FastDexParser

RULE_ID = "login_setting"
RULE_NAME = "登录页设置入口"
RULE_DESC = "在登录页底部功能栏添加 Zzz 设置按钮"
RULE_ALIASES = ["login_setting", "login_zzz", "登录页设置", "登录设置"]
RULE_ENABLED = True


def _find_method_strictly_referencing_string(p, class_idx, target_string, required_proto=None):
    s_id = p.find_string_id(target_string)
    if s_id == -1:
        return None
    s_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
    s_32 = struct.pack('<I', s_id)

    for m_name, proto_desc, _, code_off, access_flags in p.get_class_methods(class_idx):
        if access_flags & 0x0008:  # 跳过 static 方法
            continue
        if required_proto and proto_desc != required_proto:
            continue
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
            if op == 0x1A and k + 4 <= len_insns:  # const-string
                if s_16 and insns[k + 2: k + 4] == s_16:
                    return f"{m_name}{proto_desc}"
                k += 4
                continue
            elif op == 0x1B and k + 6 <= len_insns:  # const-string/jumbo
                if insns[k + 2: k + 6] == s_32:
                    return f"{m_name}{proto_desc}"
                k += 6
                continue
            k += 2
    return None


def build_login_setting_rules(dex_data_dict):
    target_class = "Lcom/tencent/mobileqq/login/widget/LoginPageBottomMoreActionsView;"
    target_method = None

    for _, dex_bytes in dex_data_dict.items():
        if b"LoginPageBottomMoreActionsView" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            c_idx = p.find_class_index(target_class)
            if c_idx != -1:
                target_method = _find_method_strictly_referencing_string(
                    p, c_idx, "mCountValidButtons > 4, some buttons hide", required_proto="(I)V"
                )
                if not target_method:
                    # 兜底：匹配该类中唯一的非静态 (I)V 排版方法
                    for m_name, proto_desc, _, _, access_flags in p.get_class_methods(c_idx):
                        if not (access_flags & 0x0008) and proto_desc == "(I)V":
                            target_method = f"{m_name}{proto_desc}"
                            break
                break

    if not target_method:
        target_method = "l(I)V"

    return [{
        "name": f"登录页底部栏注入 Zzz 入口 ({target_class}->{target_method.split('(')[0]})",
        "target_class": target_class,
        "target_method": target_method,
        "type": "REGEX_REPLACE",
        "regex": r"return-void(?=\s*(?:\.end\s+method|$))",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/misc/LoginSettingModule;->onLayoutBottomActions(Ljava/lang/Object;)V
    return-void"""
    }]


def resolve_rules(dex_data_dict, meta=None):
    return build_login_setting_rules(dex_data_dict)