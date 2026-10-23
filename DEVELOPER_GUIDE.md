# QQNT-Patcher 开发与代码贡献指南

本文档规定了在 QQNT-Patcher 中新增功能时的标准开发流程与注意事项。请在提交新功能前务必阅读并遵守以下规范。

## 核心开发原则

1. **默认关闭原则**：所有新增的功能模块，其默认状态必须为关闭（`defaultEnabled() { return false; }`），由用户在设置中主动开启。
2. **客观描述原则**：UI 界面中的标题与副标题（`getName()` 和 `getSubName()`）必须客观、简洁，直接说明功能作用，禁止使用夸张、极端的修饰词汇。
3. **动态嗅探原则**：Python 规则（`rules/`）必须使用 `FastDexParser` 基于特征字符串进行动态定位，避免硬编码类名或方法名，以适应 QQ 版本的变化。

---

## 新增功能标准流程 (5 步走)

要完整添加一个新功能（包含插桩、构建总线注册、Java 层逻辑、UI 设置开关），请按以下步骤进行：

### 第一步：编写 Python 规则插件
在 `rules/` 目录下新建 `xxx_rules.py`，利用 `FastDexParser` 寻找特征字符串并生成 Smali 注入规则。
* 必须定义常量 `RULE_ID`、`RULE_NAME`、`RULE_ENABLED = True`。
* 必须实现 `resolve_rules(dex_data_dict, meta=None)` 方法返回规则列表。

### 第二步：注册构建流水线特征
1. 打开 `pipeline/features.py`：
   * 在 `ALL_FEATURES` 字典中添加您的功能 ID 及简短说明。
   * 在 `FEATURE_ALIASES` 字典中为您的功能添加常见别名（用于命令行 `--only` 和 `--skip` 调试）。
2. 打开 `pipeline/stage2_rules.py`：
   * 在 `is_rule_active` 函数的 `id_map` 字典中，添加您的 `RULE_ID` 映射。

### 第三步：编写 Java 层业务模块
在 `src/com/tencent/qqnt/patch/modules/` 下对应的分类目录（如 `chat/` 或 `purify/`）新建 `XxxModule.java`，实现 `IPatchModule` 接口：
```java
package com.tencent.qqnt.patch.modules.purify;

import com.tencent.qqnt.patch.IPatchModule;

public class XxxModule implements IPatchModule {
    @Override 
    public String getId() { return "your_feature_id"; } // 必须与 features.py 中一致

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

### 第四步：注册 Java 层模块
打开 `src/com/tencent/qqnt/patch/ModuleManager.java`：
1. 在 `MOD_TO_FEATURE` 映射表中添加绑定关系：
   `MOD_TO_FEATURE.put("your_feature_id", "your_feature_id");`
2. 在静态初始化块中注册模块实例：
   `register(new XxxModule());`

### 第五步：添加 Smali 与 Java 的通信桥梁（可选）
如果你的 Smali 注入代码需要调用 Java 方法（或查询功能是否开启）：
打开 `src/com/tencent/qqnt/patch/PatchBridge.java`，添加静态方法：
```java
public static boolean isXxxEnabled() {
    return ConfigManager.isModuleEnabled("your_feature_id", false); // 这里的默认值也要是 false
}
```

---

## 发版 / 提交前必查清单 (Checklist)

每次添加新功能后，请对照此清单检查，切勿遗漏：

- [ ] **默认状态检查**：`XxxModule.java` 中的 `defaultEnabled()` 是否返回了 `false`？
- [ ] **ID 一致性检查**：`rules/xxx_rules.py` 的 `RULE_ID`、`features.py` 的键名、`XxxModule` 的 `getId()` 三者是否完全一致？
- [ ] **别名检查**：是否在 `features.py` 的 `FEATURE_ALIASES` 中添加了相应的中文/拼音别名以方便命令行调试？
- [ ] **模块注册检查**：是否在 `ModuleManager.java` 中调用了 `register(new XxxModule())` 且添加了 `MOD_TO_FEATURE` 映射？
- [ ] **Smali 安全检查**：Smali 插入的代码是否通过 `PatchBridge` 判断了功能开关？（只有开关开启时才执行自定义逻辑，关闭时正常放行原有逻辑）。
- [ ] **文本规范检查**：功能描述是否客观简单，无多余的夸张词汇？

