# -*- coding: utf-8 -*-
"""Telegram 外部贴纸包静态插桩规则 (自适应 9.2.90)"""

RULE_ID = "tg_stickers"
RULE_NAME = "Telegram 表情包集"
RULE_ENABLED = True

def build_tg_sticker_rules(dex_data_dict):
    rules = []

    controller_cls = "Lcom/tencent/mobileqq/emoticonview/EmoticonPanelController;"
    adapter_cls = "Lcom/tencent/mobileqq/emoticonview/EmotionPanelViewPagerAdapter;"
    tab_adapter_cls = "Lcom/tencent/mobileqq/emoticonview/EmoticonTabAdapter;"
    fav_info_cls = "Lcom/tencent/mobileqq/emoticonview/FavoriteEmoticonInfo;"

    # 1. 拦截 EmoticonPanelController.getPanelDataList 注入贴纸 Tab 面板
    rules.append({
        "name": "挂载 TG 贴纸 Tab (EmoticonPanelController->getPanelDataList)",
        "target_class": controller_cls,
        "target_method": "getPanelDataList()Ljava/util/List;",
        "type": "REGEX_REPLACE",
        "regex": r"return-object\s+([vp]\d+)(?=\s*(?:\.end\s+method|$))",
        "smali": r"""
    move-object/16 v0, \1
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/chat/TgStickerModule;->modifyPanelDataList(Ljava/util/List;)Ljava/util/List;
    move-result-object v0
    return-object v0"""
    })

    # 2. 拦截 EmotionPanelViewPagerAdapter.getEmotionPanelData 提供贴纸网格数据
    rules.append({
        "name": "提供 TG 贴纸网格数据 (EmotionPanelViewPagerAdapter->getEmotionPanelData)",
        "target_class": adapter_cls,
        "target_method": "getEmotionPanelData(ILcom/tencent/mobileqq/emoticonview/BaseEmotionAdapter;Lcom/tencent/mobileqq/emoticonview/EmotionPanelInfo;)Ljava/util/List;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p3
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/chat/TgStickerModule;->getEmoticonData(Ljava/lang/Object;)Ljava/util/List;
    move-result-object v0
    if-eqz v0, :cond_tg_panel_data_pass
    return-object v0
    :cond_tg_panel_data_pass
"""
    })

    # 3. 拦截 EmotionPanelViewPagerAdapter.handleIPSite 防止 parseInt 崩溃
    rules.append({
        "name": "防 handleIPSite 数字转换崩溃 (EmotionPanelViewPagerAdapter->handleIPSite)",
        "target_class": adapter_cls,
        "target_method": "handleIPSite(Lcom/tencent/mobileqq/data/EmoticonPackage;Lcom/tencent/mobileqq/emoticonview/BaseEmotionAdapter;Ljava/util/List;)V",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p1
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/chat/TgStickerModule;->isTgEmoticonPackage(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_tg_ipsite_pass
    return-void
    :cond_tg_ipsite_pass
"""
    })

    # 4. 拦截 EmoticonTabAdapter.generateTabUrl 加载本地封面图标
    rules.append({
        "name": "Tab 栏显示本地封面 (EmoticonTabAdapter->generateTabUrl)",
        "target_class": tab_adapter_cls,
        "target_method": "generateTabUrl(Ljava/lang/String;Z)Ljava/net/URL;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/chat/TgStickerModule;->getTabUrl(Ljava/lang/String;)Ljava/net/URL;
    move-result-object v0
    if-eqz v0, :cond_tg_tab_url_pass
    return-object v0
    :cond_tg_tab_url_pass
"""
    })

    # 5. 拦截 FavoriteEmoticonInfo.getDrawable 转调 getZoomDrawable 防 OOM
    rules.append({
        "name": "贴纸缩略图极速加载防 OOM (FavoriteEmoticonInfo->getDrawable)",
        "target_class": fav_info_cls,
        "target_method": "getDrawable(Landroid/content/Context;F)Landroid/graphics/drawable/Drawable;",
        "type": "INSERT_BEFORE",
        "smali": """
    move-object/16 v0, p0
    invoke-static {v0}, Lcom/tencent/qqnt/patch/modules/chat/TgStickerModule;->isTgFavoriteEmoticon(Ljava/lang/Object;)Z
    move-result v0
    if-eqz v0, :cond_tg_fav_drawable_pass
    const/16 v0, 0x12c
    const/16 v1, 0x12c
    invoke-virtual {p0, p1, p2, v0, v1}, Lcom/tencent/mobileqq/emoticonview/FavoriteEmoticonInfo;->getZoomDrawable(Landroid/content/Context;FII)Landroid/graphics/drawable/Drawable;
    move-result-object v0
    return-object v0
    :cond_tg_fav_drawable_pass
"""
    })

    return rules

def resolve_rules(dex_data_dict, meta=None):
    return build_tg_sticker_rules(dex_data_dict)