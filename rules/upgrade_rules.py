# -*- coding: utf-8 -*-
"""
禁用检查更新与去除提示动态规则引擎
- 动态嗅探并切断升级总中枢：UpgradeControlManager (control/d) 的配置接入点 k(Z) 与分发点 l(...)
- 彻底拔除强制更新 (iUpgradeType == 2 导致的 doLogout 登出)
- 掏空普通升级与顶部横幅弹窗：control/d->o()
- 状态判定致盲：control/e->i() 恒返回 0，util/b->f() 恒返回 Pair(false, false)
- 关于页原生自动命中“已是最新版本”，隐藏红点与箭头，移除点击监听
"""

import struct
from .parser import FastDexParser

RULE_ID = "disable_upgrade"
RULE_NAME = "禁用检查更新与去除提示"
RULE_ENABLED = True


def _find_method_strictly_referencing_string(p, class_idx, target_string):
    """从 DEX 机器指令层检查方法是否真正加载了指定特征字符串 (0x1A/0x1B)"""
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
        insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]

        if (s_16 and s_16 not in insns) and (s_32 not in insns):
            continue

        k = 0
        len_insns = len(insns)
        while k < len_insns - 1:
            op = insns[k]
            if op == 0x1A and k + 4 <= len_insns:
                if s_16 and insns[k + 2 : k + 4] == s_16:
                    return f"{m_name}{proto_desc}"
                k += 4
                continue
            elif op == 0x1B and k + 6 <= len_insns:
                if insns[k + 2 : k + 6] == s_32:
                    return f"{m_name}{proto_desc}"
                k += 6
                continue
            k += 2
    return None


def build_upgrade_rules(dex_data_dict):
    rules = []

    ctl_d_class = None
    ctl_e_class = None
    util_b_class = None

    method_d_k = None
    method_d_l = None
    method_d_o = None
    method_e_i = None
    method_b_f = None

    # =========================================================================
    # 1. 动态嗅探核心总中枢 control/d (特征: "ready to upgrade" 或 "UpgradeCtl_Manager")
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"ready to upgrade" in dex_bytes or b"UpgradeCtl_Manager" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            candidates = p.find_classes_referencing_string_strictly("ready to upgrade")
            if not candidates:
                candidates = p.find_classes_referencing_string_strictly("UpgradeCtl_Manager")

            for cls_name, cls_idx in candidates:
                if "upgrade/control/" in cls_name and not "$" in cls_name:
                    m_k = _find_method_strictly_referencing_string(p, cls_idx, "ready to upgrade")
                    m_l = _find_method_strictly_referencing_string(p, cls_idx, "handleUpgradeInfo")
                    m_o = _find_method_strictly_referencing_string(p, cls_idx, "showUpgradeTip")

                    ctl_d_class = cls_name
                    method_d_k = m_k
                    method_d_l = m_l
                    method_d_o = m_o
                    break
        if ctl_d_class:
            break

    # 兜底已确定的标准符号
    if not ctl_d_class:
        ctl_d_class = "Lcom/tencent/mobileqq/upgrade/control/d;"
    if not method_d_k:
        method_d_k = "k(Z)V"
    if not method_d_l:
        method_d_l = "l(Lprotocol/KQQConfig/UpgradeInfo;)V"
    if not method_d_o:
        method_d_o = "o()V"

    # [规则 1] 彻底断绝升级配置接入 (拦截来自 ConfigServlet / Freesia / Shiply 的数据)
    rules.append({
        "name": f"阻断升级配置接入 ({ctl_d_class}->{method_d_k})",
        "target_class": ctl_d_class,
        "target_method": method_d_k,
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isDisableUpgradeEnabled()Z
    move-result v0
    if-eqz v0, :cond_upgrade_k_pass
    return-void
    :cond_upgrade_k_pass
""",
    })

    # [规则 2] 绝杀强制更新与升级分发 (切断 iUpgradeType == 2 导致的 doLogout 登出)
    rules.append({
        "name": f"彻底拔除强制更新与升级分发 ({ctl_d_class}->{method_d_l})",
        "target_class": ctl_d_class,
        "target_method": method_d_l,
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isDisableUpgradeEnabled()Z
    move-result v0
    if-eqz v0, :cond_upgrade_l_pass
    return-void
    :cond_upgrade_l_pass
""",
    })

    # [规则 3] 彻底瘫痪弱更弹窗与顶部横幅提醒
    rules.append({
        "name": f"禁用升级弹窗与横幅提示 ({ctl_d_class}->{method_d_o})",
        "target_class": ctl_d_class,
        "target_method": method_d_o,
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isDisableUpgradeEnabled()Z
    move-result v0
    if-eqz v0, :cond_upgrade_o_pass
    return-void
    :cond_upgrade_o_pass
""",
    })

    # =========================================================================
    # 2. 动态嗅探状态控制器 control/e (特征: "needShowUpdateRedDotIgnoreClick, ")
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"needShowUpdateRedDotIgnoreClick, " in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            candidates = p.find_classes_referencing_string_strictly("needShowUpdateRedDotIgnoreClick, ")
            for cls_name, cls_idx in candidates:
                if "upgrade/control/" in cls_name and not "$" in cls_name:
                    m_i = _find_method_strictly_referencing_string(p, cls_idx, "needShowUpdateRedDotIgnoreClick, ")
                    ctl_e_class = cls_name
                    method_e_i = m_i
                    break
        if ctl_e_class:
            break

    if not ctl_e_class:
        ctl_e_class = "Lcom/tencent/mobileqq/upgrade/control/e;"
    if not method_e_i:
        method_e_i = "i()Z"

    # [规则 4] 灰度更新状态致盲 (控制红点与更新标记)
    rules.append({
        "name": f"致盲灰度更新状态 ({ctl_e_class}->{method_e_i})",
        "target_class": ctl_e_class,
        "target_method": method_e_i,
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isDisableUpgradeEnabled()Z
    move-result v0
    if-eqz v0, :cond_upgrade_i_pass
    const/4 v0, 0x0
    return v0
    :cond_upgrade_i_pass
""",
    })

    # =========================================================================
    # 3. 动态嗅探全局状态查询单例 util/b (特征: "hasGrayUpgrade:" 或 "UpgradeCtl_DtReport")
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"hasGrayUpgrade:" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            candidates = p.find_classes_referencing_string_strictly("hasGrayUpgrade:")
            for cls_name, cls_idx in candidates:
                if "upgrade/util/" in cls_name and not "$" in cls_name:
                    m_f = _find_method_strictly_referencing_string(p, cls_idx, "hasGrayUpgrade:")
                    util_b_class = cls_name
                    method_b_f = m_f
                    break
        if util_b_class:
            break

    if not util_b_class:
        util_b_class = "Lcom/tencent/mobileqq/upgrade/util/b;"
    if not method_b_f:
        method_b_f = "f()Lkotlin/Pair;"

    # [规则 5] 强制关于页走原生“已是最新版本”分支并清除红点
    rules.append({
        "name": f"关于页状态恒定最新版 ({util_b_class}->{method_b_f})",
        "target_class": util_b_class,
        "target_method": method_f_or_default(method_b_f),
        "type": "INSERT_BEFORE",
        "smali": """
    invoke-static {}, Lcom/tencent/qqnt/patch/PatchBridge;->isDisableUpgradeEnabled()Z
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


def method_f_or_default(m):
    return m if m and "(" in m else "f()Lkotlin/Pair;"


def resolve_rules(dex_data_dict, meta=None):
    return build_upgrade_rules(dex_data_dict)