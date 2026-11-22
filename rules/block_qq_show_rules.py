# -*- coding: utf-8 -*-
"""
屏蔽超级 QQ 秀与新版 QQ 秀动态规则插件
采用五层精准控制：
1. 新版 QQ 秀总控 (AIAvatarSwitchApiImpl)：全局锁死 isAllEnable 等方法，防止全 App 任何入口露出
2. ZPlan 底层数据鉴权 (AccessibleDetail + ZPlanAccessibleHelperImpl)：覆盖 QRoute 与内部直连调用，禁用 Filament 3D 渲染
3. 资料卡封面与红点兜底 (ProfileCardApiImpl)：防止白名单分支绕过及旧版红点回弹
4. 抽屉大卡片保赞与去入口 (DrawerApiImpl + SelfMiniProfileCardDataServiceImpl + parts/aiavatar/section + QQSettingMeQRcodePartV3)：
   定向豁免抽屉大卡片与点赞数据服务，并借官方 GONE 与布局重排分支隐藏卡片内创建按钮、动画与顶栏灰圈图标
5. 消息页标题栏动态按钮精准过滤 (ConversationTitleBtnConfig)：按 businessId 仅过滤 ZPlan / QQShow 图标
"""

from .parser import FastDexParser

RULE_ID = "block_qq_show"
RULE_NAME = "屏蔽超级 QQ 秀"
RULE_DESC = "屏蔽新版 QQ 秀头像、资料卡超级 QQ 秀形象及主页入口"
RULE_ALIASES = [
    "block_qq_show",
    "qq_show",
    "qqshow",
    "super_qq_show",
    "zplan",
    "ai_avatar",
    "屏蔽QQ秀",
    "屏蔽超级QQ秀",
    "超级QQ秀",
    "去QQ秀",
]
RULE_ENABLED = True

_RETURN_FALSE_SMALI = """
    const-string v0, "block_qq_show"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_block_qq_show
    const/4 v0, 0x0
    return v0
    :cond_pass_block_qq_show
"""

_RETURN_TRUE_SMALI = """
    const-string v0, "block_qq_show"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass_keep_drawer_card
    const/4 v0, 0x1
    return v0
    :cond_pass_keep_drawer_card
"""


def _collect_existing_methods(dex_data_dict, class_method_map):
    """按字节预检 + FastDexParser 验证目标类与方法签名是否存在，保证跨版本安全"""
    matched = {}
    pending = {cls: set(methods) for cls, methods in class_method_map.items()}

    for _, dex_bytes in dex_data_dict.items():
        if not pending:
            break

        hit_classes = []
        for cls_desc in list(pending.keys()):
            needle = cls_desc[1:-1].encode("utf-8")
            if needle in dex_bytes:
                hit_classes.append(cls_desc)

        if not hit_classes:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        for cls_desc in hit_classes:
            c_idx = p.find_class_index(cls_desc)
            if c_idx == -1:
                continue

            want_sigs = pending[cls_desc]
            found_sigs = []
            for m_name, proto_desc, _, code_off, _ in p.get_class_methods(c_idx):
                if code_off == 0:
                    continue
                sig = f"{m_name}{proto_desc}"
                if sig in want_sigs:
                    found_sigs.append(sig)

            if found_sigs:
                matched[cls_desc] = found_sigs
            del pending[cls_desc]

    return matched


def _find_title_btn_config_method(dex_data_dict):
    """基于特征字符串动态定位 ConversationTitleBtnConfig 及其无参布尔校验方法"""
    marker = "ConversationTitleBtnConfig(businessId="
    marker_bytes = marker.encode("utf-8")

    for _, dex_bytes in dex_data_dict.items():
        if marker_bytes not in dex_bytes:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        candidates = p.find_classes_referencing_string_strictly(marker)
        for cls_name, cls_idx in candidates:
            if "conversationtitlebutton" not in cls_name or "$" in cls_name:
                continue
            for m_name, proto_desc, _, code_off, access_flags in p.get_class_methods(cls_idx):
                if code_off != 0 and proto_desc == "()Z" and (access_flags & 0x0008) == 0:
                    return cls_name, f"{m_name}{proto_desc}"

    return None, None


def _find_drawer_qqshow_sections(dex_data_dict):
    """动态定位抽屉大卡片内的创建 QQ 秀按钮与 APNG 动画 Section"""
    markers = (
        "em_avatar_create_qq_show_button",
        "em_avatar_qq_show_entry_animation",
    )
    target_method = "onBindData(Ljava/lang/Object;ILjava/util/List;)V"
    found = set()

    for _, dex_bytes in dex_data_dict.items():
        active_markers = [m for m in markers if m.encode("utf-8") in dex_bytes]
        if not active_markers:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        for marker in active_markers:
            for cls_name, cls_idx in p.find_classes_referencing_string_strictly(marker):
                if "parts/aiavatar/section/" not in cls_name or "$" in cls_name or cls_name in found:
                    continue
                for m_name, proto_desc, _, code_off, _ in p.get_class_methods(cls_idx):
                    if code_off != 0 and f"{m_name}{proto_desc}" == target_method:
                        found.add(cls_name)
                        break

    return sorted(found), target_method


def _find_drawer_top_qqshow_part(dex_data_dict):
    """动态定位 QQSettingMeQRcodePartV3 (负责抽屉右上角 QQ 秀圆圈容器显隐与状态栏对齐)"""
    marker = "em_drawer_superqqshow"
    marker_bytes = marker.encode("utf-8")
    target_method = "onPartResume(Landroid/app/Activity;)V"

    for _, dex_bytes in dex_data_dict.items():
        if marker_bytes not in dex_bytes:
            continue

        p = FastDexParser(dex_bytes)
        if not p.valid:
            continue

        for cls_name, cls_idx in p.find_classes_referencing_string_strictly(marker):
            if "mobileqq/parts/" not in cls_name or "$" in cls_name:
                continue
            for m_name, proto_desc, _, code_off, _ in p.get_class_methods(cls_idx):
                if code_off != 0 and f"{m_name}{proto_desc}" == target_method:
                    return cls_name, target_method

    return None, None


def build_block_qq_show_rules(dex_data_dict):
    rules = []

    # 1. 全局锁死：新版 QQ 秀、ZPlan 底层鉴权与资料卡封面
    target_false_specs = {
        "Lcom/tencent/mobileqq/ai/avatar/api/impl/AIAvatarSwitchApiImpl;": [
            "isAllEnable()Z",
            "isQQShowSceneAllEnable(JLjava/lang/String;)Z",
            "isQQShowEnableForScene(JLjava/lang/String;)Z",
        ],
        "Lcom/tencent/mobileqq/zplan/model/AccessibleDetail;": [
            "isAccessible()Z",
            "isPassWithoutSetting()Z",
            "isDevicePass()Z",
        ],
        "Lcom/tencent/mobileqq/zplan/utils/api/impl/ZPlanAccessibleHelperImpl;": [
            "enableZPlanNativeAppFilament(I)Z",
        ],
        "Lcom/tencent/mobileqq/profilecard/api/impl/ProfileCardApiImpl;": [
            "isProfileCardShowZplanCover(Lcom/tencent/mobileqq/profilecard/data/ProfileCardInfo;)Z",
            "getIsShowZplanSettingRedDotOrBubble(Ljava/lang/String;Ljava/lang/String;)Z",
        ],
    }

    for cls_desc, sigs in _collect_existing_methods(dex_data_dict, target_false_specs).items():
        simple_cls = cls_desc.rsplit("/", 1)[-1].rstrip(";")
        for sig in sigs:
            m_name = sig.split("(", 1)[0]
            rules.append({
                "name": f"屏蔽超级 QQ 秀 ({simple_cls}->{m_name})",
                "target_class": cls_desc,
                "target_method": sig,
                "type": "INSERT_BEFORE",
                "smali": _RETURN_FALSE_SMALI,
            })

    # 2. 定向豁免抽屉大卡片布局与点赞/互动数据服务，防止屏蔽后点赞消失
    target_true_specs = {
        "Lcom/tencent/mobileqq/api/impl/DrawerApiImpl;": [
            "needUsedAIAvatar()Z",
        ],
        "Lcom/tencent/mobileqq/profilecard/api/impl/SelfMiniProfileCardDataServiceImpl;": [
            "mainAvatarSwitchOn()Z",
            "needUseProfileCardData()Z",
        ],
    }

    for cls_desc, sigs in _collect_existing_methods(dex_data_dict, target_true_specs).items():
        simple_cls = cls_desc.rsplit("/", 1)[-1].rstrip(";")
        for sig in sigs:
            m_name = sig.split("(", 1)[0]
            rules.append({
                "name": f"保留抽屉大卡片点赞功能 ({simple_cls}->{m_name})",
                "target_class": cls_desc,
                "target_method": sig,
                "type": "INSERT_BEFORE",
                "smali": _RETURN_TRUE_SMALI,
            })

    # 3. 隐藏抽屉大卡片内的创建 QQ 秀按钮与引导动画 Section (借官方 GONE 分支)
    section_classes, bind_method = _find_drawer_qqshow_sections(dex_data_dict)
    for sec_cls in section_classes:
        simple_cls = sec_cls.rsplit("/", 1)[-1].rstrip(";")
        rules.append({
            "name": f"隐藏抽屉卡片 QQ 秀入口 ({simple_cls}->onBindData)",
            "target_class": sec_cls,
            "target_method": bind_method,
            "type": "REGEX_REPLACE",
            "regex": r"(invoke-virtual\s+\{[vp]\d+\},\s*Lcom/tencent/mobileqq/parts/aiavatar/[^;]+;->\w+\(\)Z\s+move-result\s+([vp]\d+))",
            "smali": r"""\1
    invoke-static/range {\2 .. \2}, Lcom/tencent/qqnt/patch/modules/purify/BlockQQShowModule;->overrideHideDrawerQQShow(Z)Z
    move-result \2""",
        })

    # 4. 隐藏抽屉顶栏右上角超级 QQ 秀圆圈容器并触发官方重排分支
    top_part_cls, top_part_method = _find_drawer_top_qqshow_part(dex_data_dict)
    if top_part_cls and top_part_method:
        simple_cls = top_part_cls.rsplit("/", 1)[-1].rstrip(";")
        rules.append({
            "name": f"隐藏抽屉顶栏 QQ 秀图标与占位 ({simple_cls}->onPartResume)",
            "target_class": top_part_cls,
            "target_method": top_part_method,
            "type": "REGEX_REPLACE",
            "regex": r"(invoke-interface\s+\{[vp]\d+\},\s*Lcom/tencent/mobileqq/studymode/api/IStudyModeManager;->getStudyModeSwitch\(\)Z\s+move-result\s+([vp]\d+))",
            "smali": r"""\1
    invoke-static/range {\2 .. \2}, Lcom/tencent/qqnt/patch/modules/purify/BlockQQShowModule;->overrideHideDrawerQQShow(Z)Z
    move-result \2""",
        })

    # 5. 消息页标题栏动态按钮配置按 businessId 精准过滤
    btn_cls, btn_method = _find_title_btn_config_method(dex_data_dict)
    if btn_cls and btn_method:
        rules.append({
            "name": f"屏蔽消息页标题栏超级 QQ 秀按钮 ({btn_cls}->{btn_method.split('(', 1)[0]})",
            "target_class": btn_cls,
            "target_method": btn_method,
            "type": "INSERT_BEFORE",
            "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/purify/BlockQQShowModule;->shouldBlockTitleButton(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_pass_qqshow_title_btn
    const/4 v0, 0x0
    return v0
    :cond_pass_qqshow_title_btn
""",
        })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_block_qq_show_rules(dex_data_dict)