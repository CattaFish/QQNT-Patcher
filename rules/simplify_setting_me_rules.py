# -*- coding: utf-8 -*-
"""
侧滑栏精简动态规则插件
1. 入口过滤：插桩 QQSettingMeUtils.needHideForMenu(QQSettingMeBizBean)Z
2. 红点屏蔽：阻断 QQSettingMeListSection(section/c) 的天书红点绑定及 PanelPartV3 的红点推送
3. 分割线清理：在 PanelPartV3 的 setItems 调用前清理首尾及连续重复分割线
4. 即时刷新：在 onPartResume 检测配置变更并重刷列表与 ViewHolder，免重启生效
5. 越界保护：保护小金库异步回插列表下标
"""

import struct
from .parser import FastDexParser

RULE_ID = "simplify_setting_me"
RULE_NAME = "侧滑栏精简"
RULE_DESC = "隐藏侧滑栏中的指定功能入口与红点推广"
RULE_ALIASES = ["simplify_setting_me", "setting_me", "drawer", "侧滑栏", "侧滑栏精简", "精简侧滑栏", "侧边栏精简", "抽屉精简"]
RULE_ENABLED = True

BIZ_BEAN_CLS = "Lcom/tencent/mobileqq/activity/qqsettingme/config/QQSettingMeBizBean;"
PANEL_PART_CLS = "Lcom/tencent/mobileqq/parts/QQSettingMeMenuPanelPartV3;"
MENU_CONFIG_CLS = "Lcom/tencent/mobileqq/activity/qqsettingme/config/QQSettingMeMenuConfigBeanV3;"
APP_INFO_CLS = "Lcom/tencent/mobileqq/tianshu/pb/BusinessInfoCheckUpdate$AppInfo;"
MODULE_CLS = "Lcom/tencent/qqnt/patch/modules/purify/SimplifySettingMeModule;"


def _find_method_strictly_referencing_string(p, class_idx, target_string):
    """扫描类中显式引用特征字符串的方法，返回 (m_name, proto_desc, code_off, access_flags)"""
    s_id = p.find_string_id(target_string)
    if s_id == -1:
        return None
    s_16 = struct.pack('<H', s_id) if s_id <= 65535 else None
    s_32 = struct.pack('<I', s_id)

    for m_name, proto_desc, _, code_off, access_flags in p.get_class_methods(class_idx):
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
            if op == 0x1A and k + 4 <= len_insns:  # const-string
                if s_16 and insns[k + 2 : k + 4] == s_16:
                    return (m_name, proto_desc, code_off, access_flags)
                k += 4
                continue
            elif op == 0x1B and k + 6 <= len_insns:  # const-string/jumbo
                if insns[k + 2 : k + 6] == s_32:
                    return (m_name, proto_desc, code_off, access_flags)
                k += 6
                continue
            k += 2
    return None


def _find_invoked_string_getter_in_code(p, code_off, owner_type_str):
    """在指定方法字节码中提取对 owner_type_str 的 ()Ljava/lang/String; 虚调用方法名"""
    if code_off == 0 or code_off + 16 >= len(p.data):
        return None
    insns_size = struct.unpack_from('<I', p.data, code_off + 12)[0]
    insns = p.data[code_off + 16 : code_off + 16 + insns_size * 2]

    k = 0
    len_insns = len(insns)
    while k + 6 <= len_insns:
        op = insns[k]
        if op in (0x6E, 0x74):  # invoke-virtual / invoke-virtual/range
            m_idx = struct.unpack_from('<H', insns, k + 2)[0]
            if m_idx < p.method_ids_size:
                c_idx, proto_idx, name_idx = struct.unpack_from('<HHI', p.data, p.method_ids_off + m_idx * 8)
                if p.get_type_str(c_idx) == owner_type_str and p.get_proto_desc(proto_idx) == "()Ljava/lang/String;":
                    return p.get_string(name_idx)
        k += 2
    return None


def build_simplify_setting_me_rules(dex_data_dict):
    rules = []

    hide_utils_cls = None
    hide_utils_method = None
    get_plugin_id_method = None

    panel_live_update_method = None
    panel_refresh_method = None
    panel_refresh_is_private = True
    panel_red_dot_method = None

    list_section_cls = None
    financial_runnable_cls = None

    # 1. 嗅探 QQSettingMeUtils.needHideForMenu(QQSettingMeBizBean)Z
    for _, dex_bytes in dex_data_dict.items():
        if b"QQSettingMeUtils" not in dex_bytes and b"isZplanBubbleBeanValid" not in dex_bytes:
            continue
        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        candidates = p.find_classes_referencing_string_strictly("QQSettingMeUtils")
        for cls_name, cls_idx in candidates:
            if "qqsettingme" not in cls_name:
                continue
            for m_name, proto_desc, _, _, access_flags in p.get_class_methods(cls_idx):
                if proto_desc == f"({BIZ_BEAN_CLS})Z" and (access_flags & 0x0008):
                    hide_utils_cls = cls_name
                    hide_utils_method = f"{m_name}{proto_desc}"
                    break
            if hide_utils_cls:
                break
        if hide_utils_cls:
            break

    # 2. 嗅探 QQSettingMeMenuPanelPartV3 相关方法
    for _, dex_bytes in dex_data_dict.items():
        if b"QQSettingMeMenuPanelPartV3" not in dex_bytes:
            continue
        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        c_idx = p.find_class_index(PANEL_PART_CLS)
        if c_idx == -1:
            continue

        # 2a. 提取 QQSettingMeBizBean.getPluginID() 混淆名
        plugin_id_caller = _find_method_strictly_referencing_string(p, c_idx, "getPluginID(...)")
        if plugin_id_caller:
            found_getter = _find_invoked_string_getter_in_code(p, plugin_id_caller[2], BIZ_BEAN_CLS)
            if found_getter:
                get_plugin_id_method = found_getter

        # 2b. 定位模式/主题切换数据重刷方法 (原 z9()V)
        refresh_info = _find_method_strictly_referencing_string(
            p, c_idx, "qq_setting_me_enable_mode_change_update_list_9175"
        )
        if not refresh_info:
            refresh_info = _find_method_strictly_referencing_string(p, c_idx, "[updateData] remove financial.")
        if refresh_info and refresh_info[1] == "()V" and (refresh_info[3] & 0x0008) == 0:
            panel_refresh_method = f"{refresh_info[0]}()V"
            panel_refresh_is_private = (refresh_info[3] & 0x0002) != 0

        # 2c. 定位 LiveData 异步更新列表方法 (原 o9) 及红点更新方法 (原 A9)
        expected_live_proto = f"({PANEL_PART_CLS}Ljava/util/List;)V"
        expected_red_dot_proto = f"(Ljava/lang/String;{APP_INFO_CLS})V"
        for m_name, proto_desc, _, _, access_flags in p.get_class_methods(c_idx):
            if proto_desc == expected_live_proto and (access_flags & 0x0008):
                panel_live_update_method = f"{m_name}{proto_desc}"
            elif proto_desc == expected_red_dot_proto and (access_flags & 0x0008) == 0:
                panel_red_dot_method = f"{m_name}{proto_desc}"
        break

    # 2d. 降级从 QQSettingMeMenuConfigBeanV3.<init>(Z)V 提取 getPluginID()
    if not get_plugin_id_method:
        for _, dex_bytes in dex_data_dict.items():
            if b"QQSettingMeMenuConfigBeanV3" not in dex_bytes:
                continue
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue
            c_idx = p.find_class_index(MENU_CONFIG_CLS)
            if c_idx == -1:
                continue
            for m_name, proto_desc, _, code_off, _ in p.get_class_methods(c_idx):
                if m_name == "<init>" and proto_desc == "(Z)V":
                    found_getter = _find_invoked_string_getter_in_code(p, code_off, BIZ_BEAN_CLS)
                    if found_getter:
                        get_plugin_id_method = found_getter
                    break
            if get_plugin_id_method:
                break

    # 3. 嗅探侧滑栏菜单项渲染 Section (原 com/tencent/mobileqq/section/c)
    for _, dex_bytes in dex_data_dict.items():
        if b"getRedDotId(...)" not in dex_bytes:
            continue
        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue
        candidates = p.find_classes_referencing_string_strictly("getRedDotId(...)")
        for cls_name, cls_idx in candidates:
            if "mobileqq/section/" not in cls_name or "$" in cls_name:
                continue
            for m_name, proto_desc, _, _, _ in p.get_class_methods(cls_idx):
                if m_name == "onBindData" and proto_desc == "(Ljava/lang/Object;ILjava/util/List;)V":
                    list_section_cls = cls_name
                    break
            if list_section_cls:
                break
        if list_section_cls:
            break

    # 4. 嗅探小金库异步回插 Runnable
    for _, dex_bytes in dex_data_dict.items():
        if b"checkoutFinancialEntrance, needHideForMenu" not in dex_bytes:
            continue
        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue
        candidates = p.find_classes_referencing_string_strictly("checkoutFinancialEntrance, needHideForMenu")
        for cls_name, cls_idx in candidates:
            if "mobileqq/parts/" not in cls_name:
                continue
            for m_name, proto_desc, _, _, _ in p.get_class_methods(cls_idx):
                if m_name == "run" and proto_desc == "()V":
                    financial_runnable_cls = cls_name
                    break
            if financial_runnable_cls:
                break
        if financial_runnable_cls:
            break

    # 兜底签名
    if not hide_utils_cls:
        hide_utils_cls = "Lcom/tencent/mobileqq/activity/qqsettingme/utils/a;"
    if not hide_utils_method:
        hide_utils_method = f"e({BIZ_BEAN_CLS})Z"
    if not get_plugin_id_method:
        get_plugin_id_method = "n"
    if not panel_live_update_method:
        panel_live_update_method = f"o9({PANEL_PART_CLS}Ljava/util/List;)V"
    if not panel_refresh_method:
        panel_refresh_method = "z9()V"
        panel_refresh_is_private = True
    if not panel_red_dot_method:
        panel_red_dot_method = f"A9(Ljava/lang/String;{APP_INFO_CLS})V"
    if not list_section_cls:
        list_section_cls = "Lcom/tencent/mobileqq/section/c;"

    # =========================================================================
    # 规则 1：源头拦截 needHideForMenu(QQSettingMeBizBean)Z
    # =========================================================================
    rules.append({
        "name": f"侧滑栏入口过滤 ({hide_utils_cls}->{hide_utils_method.split('(')[0]})",
        "target_class": hide_utils_cls,
        "target_method": hide_utils_method,
        "type": "INSERT_BEFORE",
        "smali": f"""
    move-object/16 v0, p0
    if-eqz v0, :cond_pass_setting_me_hide
    invoke-virtual {{v0}}, {BIZ_BEAN_CLS}->{get_plugin_id_method}()Ljava/lang/String;
    move-result-object v0
    invoke-static {{v0}}, {MODULE_CLS}->shouldHideItem(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_setting_me_hide
    const/4 v0, 0x1
    return v0
    :cond_pass_setting_me_hide
"""
    })

    # =========================================================================
    # 规则 2：屏蔽侧滑栏菜单项渲染时的天书红点、右侧推广文案与小图标
    # =========================================================================
    rules.append({
        "name": f"侧滑栏入口红点渲染拦截 ({list_section_cls}->onBindData)",
        "target_class": list_section_cls,
        "target_method": "onBindData(Ljava/lang/Object;ILjava/util/List;)V",
        "type": "REGEX_REPLACE",
        "regex": (
            r"(invoke-virtual\s+\{[vp]\d+\},\s*"
            r"Lcom/tencent/mobileqq/tianshu/ui/TianshuRedTouch\$a;->[a-zA-Z0-9_]+\(\)Z\s+"
            r"move-result\s+([vp]\d+))"
        ),
        "smali": (
            r"    \1" + "\n"
            f"    invoke-static {{p0, \\2}}, {MODULE_CLS}->filterSectionRedDot(Ljava/lang/Object;Z)Z\n"
            r"    move-result \2"
        ),
    })

    # =========================================================================
    # 规则 3：阻断 PanelPartV3 异步红点推送更新 (涵盖新版 Tianshu 与旧版 RedTouch)
    # =========================================================================
    rules.append({
        "name": f"侧滑栏红点推送拦截 ({PANEL_PART_CLS}->{panel_red_dot_method.split('(')[0]})",
        "target_class": PANEL_PART_CLS,
        "target_method": panel_red_dot_method,
        "type": "INSERT_BEFORE",
        "smali": f"""
    invoke-static {{}}, {MODULE_CLS}->shouldHideRedDot()Z
    move-result v0
    if-eqz v0, :cond_pass_setting_me_red_dot
    return-void
    :cond_pass_setting_me_red_dot
"""
    })

    # =========================================================================
    # 规则 4：清理首尾与连续重复分割线
    # =========================================================================
    set_items_regex = (
        r"(invoke-virtual\s+\{[vp]\d+,\s*([vp]\d+)\},\s*"
        r"Lcom/tencent/biz/richframework/part/adapter/AsyncListDifferDelegationAdapter;->setItems\(Ljava/util/List;\)V)"
    )
    set_items_replace = (
        f"    invoke-static {{\\2}}, {MODULE_CLS}->sanitizeDividers(Ljava/util/List;)V\n"
        r"    \1"
    )

    for label, method_sig in [
        ("初始化", "onInitView(Landroid/view/View;)V"),
        ("云控刷新", panel_live_update_method),
        ("模式重刷", panel_refresh_method),
    ]:
        rules.append({
            "name": f"侧滑栏多余分割线清理 ({label}: {method_sig.split('(')[0]})",
            "target_class": PANEL_PART_CLS,
            "target_method": method_sig,
            "type": "REGEX_REPLACE",
            "regex": set_items_regex,
            "smali": set_items_replace,
        })

    # =========================================================================
    # 规则 5：配置变更后返回侧滑栏自动触发重刷 (列表 + 红点状态均免重启生效)
    # =========================================================================
    refresh_invoke_op = "invoke-direct" if panel_refresh_is_private else "invoke-virtual"
    rules.append({
        "name": f"侧滑栏配置变更即时刷新 (onPartResume->{panel_refresh_method.split('(')[0]})",
        "target_class": PANEL_PART_CLS,
        "target_method": "onPartResume(Landroid/app/Activity;)V",
        "type": "INSERT_BEFORE",
        "smali": f"""
    move-object/16 v0, p0
    invoke-static {{v0}}, {MODULE_CLS}->consumePanelDirty(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_pass_setting_me_resume
    move-object/16 v0, p0
    {refresh_invoke_op} {{v0}}, {PANEL_PART_CLS}->{panel_refresh_method}
    move-object/16 v0, p0
    invoke-static {{v0}}, {MODULE_CLS}->notifyPanelAdapterChanged(Ljava/lang/Object;)V
    :cond_pass_setting_me_resume
"""
    })

    # =========================================================================
    # 规则 6：小金库异步回插列表下标越界保护
    # =========================================================================
    if financial_runnable_cls:
        rules.append({
            "name": f"侧滑栏小金库异步回插越界保护 ({financial_runnable_cls}->run)",
            "target_class": financial_runnable_cls,
            "target_method": "run()V",
            "type": "REGEX_REPLACE",
            "regex": r"invoke-virtual\s+\{([vp]\d+),\s*([vp]\d+),\s*([vp]\d+)\},\s*Ljava/util/ArrayList;->add\(ILjava/lang/Object;\)V",
            "smali": f"    invoke-static {{\\1, \\2, \\3}}, {MODULE_CLS}->safeInsertItem(Ljava/util/ArrayList;ILjava/lang/Object;)V",
        })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_simplify_setting_me_rules(dex_data_dict)