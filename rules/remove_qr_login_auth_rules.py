# -*- coding: utf-8 -*-
"""去除相册扫码登录检验动态规则引擎"""

from .parser import FastDexParser

RULE_ID = "remove_qr_login_auth"
RULE_NAME = "去除相册扫码登录检验"
RULE_DESC = "允许使用相册图片或长按识别二维码进行登录授权"
RULE_ALIASES = ["remove_qr_login_auth", "qr_login", "相册扫码", "扫码登录", "相册扫码登录"]
RULE_ENABLED = True


def _resolve_first_boolean_param_reg(proto_desc, is_static):
    """根据方法签名与是否静态，精确计算首个 boolean 形参所在的 pN 寄存器"""
    start = proto_desc.find('(')
    end = proto_desc.find(')')
    if start < 0 or end <= start:
        return "p1"

    params = proto_desc[start + 1 : end]
    reg = 0 if is_static else 1
    i = 0
    n = len(params)
    while i < n:
        c = params[i]
        if c == 'Z':
            return f"p{reg}"
        elif c == '[':
            while i < n and params[i] == '[':
                i += 1
            if i < n and params[i] == 'L':
                while i < n and params[i] != ';':
                    i += 1
            reg += 1
        elif c == 'L':
            while i < n and params[i] != ';':
                i += 1
            reg += 1
        elif c in ('J', 'D'):
            reg += 2
        else:
            reg += 1
        i += 1
    return "p1"


def build_remove_qr_login_auth_rules(dex_data_dict):
    rules = []

    candidate_classes = [
        "Lcom/tencent/open/agent/QrAgentLoginManager;",
        "Lcom/tencent/open/agent/QrAgentLoginManager$a;",
        "Lcom/tencent/open/agent/QrAgentLoginManager$2;",
    ]

    auth_target_cls = None
    auth_target_method = None
    auth_bool_reg = "p1"

    open_shield_cls = None
    open_shield_method = None

    # 1. 定位核心跳转控制方法 (引用 "KEY_QR_IS_FORBID_LOCAL_PIC")
    for _, dex_bytes in dex_data_dict.items():
        if b"KEY_QR_IS_FORBID_LOCAL_PIC" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            for cls_name in candidate_classes:
                c_idx = p.find_class_index(cls_name)
                if c_idx == -1:
                    continue

                info = p.find_method_info_referencing_string(c_idx, "KEY_QR_IS_FORBID_LOCAL_PIC")
                if info:
                    m_sig = f"{info[0]}{info[1]}"
                    acc = info[3]
                    if m_sig.endswith(")V") and "Z" in m_sig:
                        auth_target_cls = cls_name
                        auth_target_method = m_sig
                        is_static = bool(acc & 0x0008)
                        auth_bool_reg = _resolve_first_boolean_param_reg(m_sig, is_static)
                        break
        if auth_target_cls and auth_target_method:
            break

    # 兜底签名（9.2.30+）
    if not auth_target_cls:
        auth_target_cls = "Lcom/tencent/open/agent/QrAgentLoginManager;"
    if not auth_target_method:
        auth_target_method = (
            "m(Lcom/tencent/open/agent/QrAgentLoginManager;ZLjava/lang/String;Landroid/os/Bundle;)V"
        )
        auth_bool_reg = "p1"

    rules.append({
        "name": f"去除相册扫码登录限制 ({auth_target_cls}->{auth_target_method.split('(')[0]})",
        "target_class": auth_target_cls,
        "target_method": auth_target_method,
        "type": "INSERT_BEFORE",
        "smali": f"""
    const-string v0, "{RULE_ID}"
    invoke-static {{v0}}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_qr_auth
    const/4 v0, 0x0
    move/16 {auth_bool_reg}, v0
    :cond_pass_qr_auth
""",
    })

    # 2. 边界兜底：旧版 Wtlogin 通道第三方 OpenSDK 相册扫码屏蔽 (引用 "shield_open_login")
    for _, dex_bytes in dex_data_dict.items():
        if b"shield_open_login" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            c_idx = p.find_class_index("Lcom/tencent/open/agent/QrAgentLoginManager;")
            if c_idx != -1:
                m_sig = p.find_method_referencing_string(c_idx, "shield_open_login")
                if m_sig and m_sig.endswith(")Z"):
                    open_shield_cls = "Lcom/tencent/open/agent/QrAgentLoginManager;"
                    open_shield_method = m_sig
                    break
        if open_shield_cls and open_shield_method:
            break

    if open_shield_cls and open_shield_method:
        rules.append({
            "name": f"放行旧版第三方相册扫码限制 ({open_shield_cls}->{open_shield_method.split('(')[0]})",
            "target_class": open_shield_cls,
            "target_method": open_shield_method,
            "type": "INSERT_BEFORE",
            "smali": f"""
    const-string v0, "{RULE_ID}"
    invoke-static {{v0}}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_open_login
    const/4 v0, 0x0
    return v0
    :cond_pass_open_login
""",
        })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_remove_qr_login_auth_rules(dex_data_dict)