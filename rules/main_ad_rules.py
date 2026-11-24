# -*- coding: utf-8 -*-
"""
主页横幅广告与天书推广动态拦截规则
采用双重阻断架构：
1. UI 渲染层（兜底）：BannerManager 更新状态时强行改写状态机为 0 (HIDE)
2. 网络请求层（源头）：TianShuManager 发起拉取判定时直接掐断，不向服务端发包
"""

from .parser import FastDexParser

RULE_ID = "main_ad"
RULE_NAME = "隐藏主页横幅广告"
RULE_DESC = "隐藏消息列表顶部的VIP续费、活动与游戏推广横幅"
RULE_ALIASES = ["main_ad", "main_banner", "主页横幅", "主页广告", "顶部横幅", "banner_ad"]
RULE_ENABLED = True


def build_main_ad_rules(dex_data_dict):
    rules = []
    banner_manager_cls = "Lcom/tencent/mobileqq/banner/BannerManager;"
    update_state_method = None
    tianshu_manager_cls = "Lcooperation/vip/tianshu/TianShuManager;"
    is_request_enable_method = None

    # =========================================================================
    # 防线一：BannerManager 横幅渲染总线拦截
    # 动态定位 BannerManager 中包含 "updateBannerState|[" 的核心状态更新方法 (混淆为 D(II)V)
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"updateBannerState|[" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            c_idx = p.find_class_index(banner_manager_cls)
            if c_idx != -1:
                target_m = p.find_method_referencing_string(c_idx, "updateBannerState|[")
                if target_m and target_m.endswith(")V"):
                    update_state_method = target_m
                    break
        if update_state_method:
            break

    # 嗅探失败时的兜底签名
    if not update_state_method:
        update_state_method = "D(II)V"

    # 注意：updateBannerState 是非静态虚方法：
    # p0 = this (BannerManager 实例)
    # p1 = bannerId (int，横幅业务ID)
    # p2 = state (int，横幅状态，>=2 为尝试展示)
    rules.append({
        "name": f"主页横幅状态机总线拦截 (BannerManager->{update_state_method.split('(')[0]})",
        "target_class": banner_manager_cls,
        "target_method": update_state_method,
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    move/16 v1, p1
    move/16 v2, p2
    invoke-static {v0, v1, v2}, Lcom/tencent/qqnt/patch/modules/purify/MainPageAdBlockModule;->filterBannerState(Ljava/lang/Object;II)I
    move-result p2
"""
    })

    # =========================================================================
    # 防线二：TianShuManager 天书广告请求源头致盲
    # 动态定位通过 MMKV 检查冷却时间的方法 (基于特征字符串 "next_request_time_")
    # =========================================================================
    for _, dex_bytes in dex_data_dict.items():
        if b"next_request_time_" in dex_bytes:
            p = FastDexParser(dex_bytes)
            if not p.valid:
                continue

            c_idx = p.find_class_index(tianshu_manager_cls)
            if c_idx != -1:
                target_m = p.find_method_referencing_string(c_idx, "next_request_time_")
                if target_m and target_m.endswith(")Z"):
                    is_request_enable_method = target_m
                    break
        if is_request_enable_method:
            break

    if not is_request_enable_method:
        is_request_enable_method = "isRequestEnable(I)Z"

    # TianShuManager.isRequestEnable(int) 在现代 QQNT 中是【public static 静态方法】
    # 因为是 static，所以没有隐式 this 指针，形参从 p0 开始：
    # p0 = posId (int，广告位 ID，如 715 / 0x2cb)
    # p1 不存在 不要改成 p1
    rules.append({
        "name": f"天书广告网络请求致盲 (TianShuManager->{is_request_enable_method.split('(')[0]})",
        "target_class": tianshu_manager_cls,
        "target_method": is_request_enable_method,
        "type": "INSERT_BEFORE",
        "smali": """
    move/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/purify/MainPageAdBlockModule;->shouldBlockTianShuPos(I)Z
    move-result v0
    if-eqz v0, :cond_pass_tianshu_ad
    const/4 v0, 0x0
    return v0
    :cond_pass_tianshu_ad
"""
    })

    return rules


def resolve_rules(dex_data_dict, meta=None):
    return build_main_ad_rules(dex_data_dict)