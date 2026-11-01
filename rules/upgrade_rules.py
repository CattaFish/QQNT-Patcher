# -*- coding: utf-8 -*-
"""禁用检查更新与去除提示规则引擎 (版本分水岭定界: 9.2.15)"""

import re
import struct
from .parser import FastDexParser

RULE_ID = "disable_upgrade"
RULE_NAME = "禁用检查更新与去除提示"
RULE_DESC = "禁用检查更新与去除提示"
RULE_ALIASES = ["upgrade", "update", "更新", "去更新", "禁用更新", "升级", "强更"]
RULE_ENABLED = True

def _find_method_strictly_referencing_string(p, class_idx, target_string):
    s_id = p.find_string_id(target_string)
    if s_id == -1: return None
    s_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
    s_32 = struct.pack('<I', s_id)

    methods = p.get_class_methods(class_idx)
    for m_name, proto_desc, _, code_off, _ in methods:
        if code_off == 0 or code_off + 16 >= len(p.data): continue
        insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
        insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]

        if (s_16 and s_16 not in insns) and (s_32 not in insns): continue

        k = 0
        len_insns = len(insns)
        while k < len_insns - 1:
            op = insns[k]
            if op == 0x1A and k + 4 <= len_insns:
                if s_16 and insns[k + 2 : k + 4] == s_16: return f"{m_name}{proto_desc}"
                k += 4; continue
            elif op == 0x1B and k + 6 <= len_insns:
                if insns[k + 2 : k + 6] == s_32: return f"{m_name}{proto_desc}"
                k += 6; continue
            k += 2
    return None

def parse_semver(ver_str):
    if not ver_str: return None
    m = re.search(r"(\d+)\.(\d+)\.(\d+)", ver_str)
    if m:
        return (int(m.group(1)), int(m.group(2)), int(m.group(3)))
    return None


# =========================================================================
# 分支 A：9.2.15 以前老版本逻辑 (适用于 9.1.70 ~ 9.2.10)
# 全动态语义检索，自适应 Proguard 字母偏移，不硬编码 k 与 n 类名
# =========================================================================
def build_legacy_upgrade_rules(dex_data_dict):
    rules = []
    controller_cls = None
    manager_cls = None

    method_handle = None
    method_show_tip = None
    method_show_dialog = None

    # 1. 动态定位老版主控制器 (9.1.70 为 upgrade.k，其他老版本自动定位其对应字母)
    for _, dex_bytes in dex_data_dict.items():
        if b"UpgradeController" in dex_bytes or b"handleUpgradeInfo" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            candidates = p.find_classes_referencing_string_strictly("handleUpgradeInfo")
            if not candidates:
                candidates = p.find_classes_referencing_string_strictly("UpgradeController")

            for cls_name, cls_idx in candidates:
                if "mobileqq/upgrade/" in cls_name and "$" not in cls_name and "/control/" not in cls_name:
                    methods = p.get_class_methods(cls_idx)
                    for m_name, proto_desc, _, _, _ in methods:
                        if "(Lprotocol/KQQConfig/UpgradeInfo;" in proto_desc and proto_desc.endswith(")V"):
                            method_handle = f"{m_name}{proto_desc}"
                            break

                    method_show_tip = _find_method_strictly_referencing_string(p, cls_idx, "showUpgradeTip")
                    method_show_dialog = _find_method_strictly_referencing_string(p, cls_idx, "showUpgradeDialog needDesc=")

                    if method_handle or method_show_tip:
                        controller_cls = cls_name
                        break
        if controller_cls: break

    # 兜底回退
    if not controller_cls: controller_cls = "Lcom/tencent/mobileqq/upgrade/k;"
    if not method_handle: method_handle = "o(Lprotocol/KQQConfig/UpgradeInfo;Lcom/tencent/mobileqq/upgrade/download/b$b;)V"
    if not method_show_tip: method_show_tip = "x(Z)V"
    if not method_show_dialog: method_show_dialog = "w(Z)V"

    rules.append({
        "name": f"[老版] 阻断升级配置接入与分发 ({controller_cls}->{method_handle.split('(')[0]})",
        "target_class": controller_cls,
        "target_method": method_handle,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_k_o_pass
    return-void
    :cond_upgrade_k_o_pass
""",
    })

    rules.append({
        "name": f"[老版] 禁用升级提示与横幅调度 ({controller_cls}->{method_show_tip.split('(')[0]})",
        "target_class": controller_cls,
        "target_method": method_show_tip,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_k_x_pass
    return-void
    :cond_upgrade_k_x_pass
""",
    })

    rules.append({
        "name": f"[老版] 阻断强更弹窗拉起 ({controller_cls}->{method_show_dialog.split('(')[0]})",
        "target_class": controller_cls,
        "target_method": method_show_dialog,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_k_w_pass
    return-void
    :cond_upgrade_k_w_pass
""",
    })

    # 2. 动态定位老版升级状态与红点管理器 (9.1.70 为 upgrade.n，其他老版本通过日志特征定位)
    for _, dex_bytes in dex_data_dict.items():
        if b"UpgradeController_Manager" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            candidates = p.find_classes_referencing_string_strictly("UpgradeController_Manager")
            for cls_name, cls_idx in candidates:
                if "mobileqq/upgrade/" in cls_name and "$" not in cls_name:
                    manager_cls = cls_name
                    break
        if manager_cls: break

    if not manager_cls:
        manager_cls = "Lcom/tencent/mobileqq/upgrade/n;"

    rules.append({
        "name": f"[老版] 致盲新版本可用状态 ({manager_cls}->j)",
        "target_class": manager_cls,
        "target_method": "j()Z",
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_mgr_j_pass
    const/4 v0, 0x0
    return v0
    :cond_upgrade_mgr_j_pass
""",
    })

    rules.append({
        "name": f"[老版] 致盲升级红点提示 ({manager_cls}->i)",
        "target_class": manager_cls,
        "target_method": "i()Z",
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_mgr_i_pass
    const/4 v0, 0x0
    return v0
    :cond_upgrade_mgr_i_pass
""",
    })

    return rules


# =========================================================================
# 分支 B：9.2.15 及以上新版本逻辑 (适用于 9.2.15 ~ 9.3.x)
# 100% 保持在 9.2.90 上验证通过的原版逻辑，不改动任何字符
# =========================================================================
def build_modern_upgrade_rules(dex_data_dict):
    rules = []
    ctl_d_class = None
    ctl_e_class = None
    util_b_class = None
    method_d_k = None
    method_d_l = None
    method_d_o = None
    method_e_i = None
    method_b_f = None

    # 1. 定位主控制器 d 类 (classes5.dex)
    for _, dex_bytes in dex_data_dict.items():
        if b"ready to upgrade" in dex_bytes or b"handleUpgradeInfo" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue

            candidates = p.find_classes_referencing_string_strictly("ready to upgrade")
            if not candidates:
                candidates = p.find_classes_referencing_string_strictly("handleUpgradeInfo")

            for cls_name, cls_idx in candidates:
                if "upgrade/control/" in cls_name and "$" not in cls_name:
                    m_k = _find_method_strictly_referencing_string(p, cls_idx, "ready to upgrade")
                    m_l = _find_method_strictly_referencing_string(p, cls_idx, "handleUpgradeInfo")
                    m_o = _find_method_strictly_referencing_string(p, cls_idx, "showUpgradeTip")

                    if not m_l:
                        for m_name, proto_desc, _, _, _ in p.get_class_methods(cls_idx):
                            if proto_desc == "(Lprotocol/KQQConfig/UpgradeInfo;)V":
                                m_l = f"{m_name}{proto_desc}"
                                break

                    if m_k or m_l:
                        ctl_d_class = cls_name
                        method_d_k = m_k
                        method_d_l = m_l
                        method_d_o = m_o
                        break
        if ctl_d_class: break

    if not ctl_d_class: ctl_d_class = "Lcom/tencent/mobileqq/upgrade/control/d;"
    if not method_d_k: method_d_k = "k(Z)V"
    if not method_d_l: method_d_l = "l(Lprotocol/KQQConfig/UpgradeInfo;)V"
    if not method_d_o: method_d_o = "o()V"

    rules.append({
        "name": f"阻断升级配置接入 ({ctl_d_class}->{method_d_k.split('(')[0]})",
        "target_class": ctl_d_class,
        "target_method": method_d_k,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_k_pass
    return-void
    :cond_upgrade_k_pass
""",
    })

    rules.append({
        "name": f"彻底拔除强制更新与升级分发 ({ctl_d_class}->{method_d_l.split('(')[0]})",
        "target_class": ctl_d_class,
        "target_method": method_d_l,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_l_pass
    return-void
    :cond_upgrade_l_pass
""",
    })

    rules.append({
        "name": f"禁用升级弹窗与横幅提示 ({ctl_d_class}->{method_d_o.split('(')[0]})",
        "target_class": ctl_d_class,
        "target_method": method_d_o,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_o_pass
    return-void
    :cond_upgrade_o_pass
""",
    })

    # 2. 定位辅助校验 e 类 (classes10.dex)
    for _, dex_bytes in dex_data_dict.items():
        if b"needShowUpdateRedDotIgnoreClick, " in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            candidates = p.find_classes_referencing_string_strictly("needShowUpdateRedDotIgnoreClick, ")
            for cls_name, cls_idx in candidates:
                if "upgrade/control/" in cls_name and "$" not in cls_name:
                    m_i = _find_method_strictly_referencing_string(p, cls_idx, "needShowUpdateRedDotIgnoreClick, ")
                    if m_i:
                        ctl_e_class = cls_name
                        method_e_i = m_i
                        break
        if ctl_e_class: break

    if not ctl_e_class: ctl_e_class = "Lcom/tencent/mobileqq/upgrade/control/e;"
    if not method_e_i: method_e_i = "i()Z"

    rules.append({
        "name": f"致盲灰度更新状态 ({ctl_e_class}->{method_e_i.split('(')[0]})",
        "target_class": ctl_e_class,
        "target_method": method_e_i,
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_i_pass
    const/4 v0, 0x0
    return v0
    :cond_upgrade_i_pass
""",
    })

    # 3. 定位关于页更新状态 b 类
    for _, dex_bytes in dex_data_dict.items():
        if b"hasGrayUpgrade:" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid: continue
            candidates = p.find_classes_referencing_string_strictly("hasGrayUpgrade:")
            for cls_name, cls_idx in candidates:
                if "upgrade/util/" in cls_name and "$" not in cls_name:
                    m_f = _find_method_strictly_referencing_string(p, cls_idx, "hasGrayUpgrade:")
                    if m_f:
                        util_b_class = cls_name
                        method_b_f = m_f
                        break
        if util_b_class: break

    if not util_b_class: util_b_class = "Lcom/tencent/mobileqq/upgrade/util/b;"
    if not method_b_f: method_b_f = "f()Lkotlin/Pair;"

    rules.append({
        "name": f"关于页状态恒定最新版 ({util_b_class}->{method_b_f.split('(')[0]})",
        "target_class": util_b_class,
        "target_method": method_b_f if method_b_f and "(" in method_b_f else "f()Lkotlin/Pair;",
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "disable_upgrade"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_upgrade_f_pass
    new-instance v0, Lkotlin/Pair;
    sget-object v1, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    sget-object v2, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
    invoke-direct {v0, v1, v2}, Lkotlin/Pair;-><init>(Ljava/lang/Object;Ljava/lang/Object;)V
    return-object v0
    :cond_upgrade_f_pass
""",
    })

    return rules


# =========================================================================
# 核心总线路由器：以 (9, 2, 15) 作为终极定界分水岭
# =========================================================================
def resolve_rules(dex_data_dict, meta=None):
    meta = meta or {}
    ver_str = meta.get("version_name", "")
    semver = parse_semver(ver_str)

    # 1. 优先采用精确版本号判定：
    # 9.2.10 及以下走老版逻辑，9.2.15 及以上走新版逻辑
    if semver is not None:
        if semver < (9, 2, 15):
            return build_legacy_upgrade_rules(dex_data_dict)
        else:
            return build_modern_upgrade_rules(dex_data_dict)

    # 2. 结构特征双保险兜底：9.2.15 起才开始出现 upgrade/control/
    has_control_pkg = any(b"upgrade/control/" in b for b in dex_data_dict.values())
    if not has_control_pkg:
        return build_legacy_upgrade_rules(dex_data_dict)
    else:
        return build_modern_upgrade_rules(dex_data_dict)