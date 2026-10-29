# QQNT-Patcher 开发与代码贡献指南

本文档规定了在 QQNT-Patcher 中新增功能时的标准开发流程与注意事项。请在提交新功能前务必阅读并遵守以下规范。

## 核心开发原则

1. **默认关闭原则**：所有新增的功能模块，其默认状态必须为关闭（`defaultEnabled() { return false; }`），由用户在设置中主动开启。
2. **客观描述原则**：UI 界面中的标题与副标题（`getName()` 和 `getSubName()`）必须客观、简洁，直接说明功能作用，禁止使用夸张、极端的修饰词汇。
3. **动态嗅探原则**：Python 规则（`rules/`）必须使用 `FastDexParser` 基于特征字符串进行动态定位，避免硬编码类名或方法名，以适应 QQ 版本的变化。
4. **全自动装配体系**：架构已实现全自动流水线自描述与模块装配，**无需修改任何构建总线、无需修改 ModuleManager 注册表、无需修改 PatchBridge**！

---

## 极简开发流程：两步极速落地

现在添加一个全新功能，**只需要编写 2 个文件**：

### 第一步：编写自描述 Python 规则插件
在 `rules/` 目录下新建 `xxx_rules.py`，利用 `FastDexParser` 寻找特征字符串并生成 Smali 注入规则：
```python
# -*- coding: utf-8 -*-
from .parser import FastDexParser

RULE_ID = "your_feature_id"           # 核心功能 ID (必须唯一)
RULE_NAME = "客观的功能名称"
RULE_DESC = "简要功能描述"
RULE_ALIASES = ["别名1", "别名2"]     # 供命令行 --only 和 --skip 快速测试使用
RULE_ENABLED = True

def build_xxx_rules(dex_data_dict):
    # 如果仅需开关判断放行，直接调用 PatchBridge.isEnabled("your_feature_id")，无需给 PatchBridge 添加专有方法！
    return [{
        "name": "某方法拦截",
        "target_class": "...",
        "target_method": "...",
        "type": "INSERT_BEFORE",
        "smali": """
    const-string v0, "your_feature_id"
    invoke-static {v0}, Lcom/tencent/qqnt/patch/PatchBridge;->isEnabled(Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :cond_pass
    return-void
    :cond_pass
"""
    }]

def resolve_rules(dex_data_dict, meta=None):
    return build_xxx_rules(dex_data_dict)
```

### 第二步：编写 Java 业务模块
在 `src/com/tencent/qqnt/patch/modules/` 下的对应目录（如 `chat/` 或 `purify/`）新建 `XxxModule.java`，实现 `IPatchModule` 接口：
```java
package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class XxxModule implements IPatchModule {
    @Override 
    public String getId() { return "your_feature_id"; } // 必须与 rules 中 RULE_ID 保持一致

    @Override 
    public String getName() { return "客观的功能名称"; }

    @Override 
    public String getSubName() { return "简单的功能描述"; }

    @Override 
    public String getCategory() { return CATEGORY_PURIFY; } // 或 CATEGORY_CHAT

    @Override 
    public boolean defaultEnabled() { return false; } // 必须默认为 false
}
```

**大功告成！** 
流水线在编译打包时会自动扫描到该 Module 并将其写入自动装配清单；运行期 `ModuleManager` 会自动加载、自动在“Zzz 设置”中挂载开关、自动接入全局搜索索引。若模块需要配置弹窗，直接调用 `SettingDialogBuilder` 即可快速以链式 DSL 完成高质感原生表单。

---

## 发版 / 提交前必查清单 (Checklist)

- [ ] **默认状态检查**：`XxxModule.java` 中的 `defaultEnabled()` 是否返回了 `false`？
- [ ] **ID 一致性检查**：`rules/xxx_rules.py` 的 `RULE_ID` 与 `XxxModule` 的 `getId()` 是否完全一致？
- [ ] **自描述别名检查**：是否在 `rules/xxx_rules.py` 的 `RULE_ALIASES` 中添加了相应的中文/拼音别名？
- [ ] **开关安全检查**：Smali 插入的代码是否通过 `PatchBridge.isEnabled("your_feature_id")` 判定放行？
- [ ] **文本规范检查**：功能描述是否客观简单，无多余夸张词汇？