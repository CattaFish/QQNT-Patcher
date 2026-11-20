# -*- coding: utf-8 -*-
"""
QQNT-Patcher 上下文智能扫描与裁剪工具
支持:
  1. 写新功能模式 (默认精简上下文: 规范 + 骨架 + 桩类 + 黄金范例)
  2. 修 Bug / 改功能模式 (-t 关键词自动召回对应规则、模块与依赖类)
  3. 按子系统切片 (-s build / rules / core / plugin / killer)
  4. Git 增量改动模式 (--git)
  5. 发版全量审查模式 (--full)
"""

import argparse
import ast
import os
import re
import subprocess
import sys

# =============================================================================
# 基础配置
# =============================================================================

TEXT_EXTENSIONS = {
    ".js", ".kt", ".java", ".xml", ".gradle", ".md", ".pro",
    ".cpp", ".c", ".h", ".hpp", ".proto", ".properties",
    ".yml", ".yaml", ".py", ".tpl", ".sh",
}
SPECIAL_TEXT_FILENAMES = {"CMakeLists.txt"}
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".webp", ".ico"}

IGNORE_DIRS = {
    ".git", ".gradle", ".idea", "__pycache__", "node_modules",
    "build", "build_cache", "build_native", "bin", "gen", "out", "gradle",
}
IGNORE_REL_PATHS = {"src/bsh"}
IGNORE_FILENAMES = {"merged_qqntpatch.txt", ".DS_Store", "Thumbs.db", ".nomedia"}

# 第三方 xHook 原生库文件（日常与常规全量审查默认屏蔽，传 --include-vendor 可包含）
VENDOR_NATIVE_PREFIXES = ("killer_native/xh_",)
VENDOR_NATIVE_FILES = {
    "killer_native/queue.h",
    "killer_native/tree.h",
    "killer_native/xhook.h",
    "killer_native/xhook.c",
}

# 写新功能模式的固定核心文件
CORE_FULL_FILES = [
    "DEVELOPER_GUIDE.md",
    "pipeline/features.py",
    "src/com/tencent/qqnt/patch/IPatchModule.java",
    "src/com/tencent/qqnt/patch/PatchBridge.java",
]

# 精选黄金范例 (覆盖: 极简开关 / 动态指令嗅探+原生回调 / 正则替换+SettingDialogBuilder弹窗)
GOLDEN_SAMPLE_FILES = [
    "rules/block_at_all_rules.py",
    "src/com/tencent/qqnt/patch/modules/purify/AtAllNotifyBlockModule.java",
    "rules/main_ad_rules.py",
    "src/com/tencent/qqnt/patch/modules/purify/MainPageAdBlockModule.java",
    "rules/remove_reply_at_rules.py",
    "src/com/tencent/qqnt/patch/modules/chat/ModifyPicSummaryModule.java",
]

# 基础设施类：在精简模式下提取 API 骨架（仅保留签名、字段与文档注释）
SKELETON_INFRA_FILES = [
    "rules/parser.py",
    "rules/stubs.py",
    "rules/engine.py",
    "src/com/tencent/qqnt/patch/AppContext.java",
    "src/com/tencent/qqnt/patch/ModuleManager.java",
    "src/com/tencent/qqnt/patch/config/ConfigManager.java",
    "src/com/tencent/qqnt/patch/config/FeatureConfig.java",
    "src/com/tencent/qqnt/patch/ui/SettingDialogBuilder.java",
    "src/com/tencent/qqnt/patch/ui/NativeSettingHelper.java",
    "src/com/tencent/qqnt/patch/ui/SettingMenuRegistry.java",
    "src/com/tencent/qqnt/patch/util/PLog.java",
    "src/com/tencent/qqnt/patch/util/ToastHelper.java",
    "src/com/tencent/qqnt/patch/util/PatchAssetHelper.java",
    "src/com/tencent/qqnt/patch/plugin/MsgSender.java",
]

# 编译期 QQNT 桩类路径前缀（体积小且决定字段名正确性，完整保留）
STUB_PATH_PREFIXES = (
    "src/com/tencent/qqnt/kernel/",
    "src/com/tencent/qqnt/kernelpublic/",
    "src/com/tencent/qqnt/aio/",
    "src/com/tencent/qqnt/ntrelation/",
    "src/com/tencent/mobileqq/",
    "src/com/tencent/relation/",
)

# 心脏图标白名单（发版 Emoji 检测时保留该处不动）
EMOJI_WHITELIST = [
    ("src/com/tencent/qqnt/patch/ui/ZzzSettingFragment.java", "Created by Zcraft with"),
]

# 允许默认开启的入口类模块白名单（发版静态体检放行）
DEFAULT_ENABLED_WHITELIST = {
    "login_setting",
}

# 匹配常见 Emoji（排除项目进度条和树形字符如 ★ ✔ ➔ 等）
EMOJI_PATTERN = re.compile(
    "["
    "\U0001F000-\U0001FAFF"
    "\u231A-\u23FA"
    "\u2600-\u2604\u2606-\u26FF"
    "\u2700-\u2713\u2715-\u2793\u2795-\u27BF"
    "]"
)


# =============================================================================
# 文件收集与元数据索引
# =============================================================================

def norm_rel(path, root):
    return os.path.relpath(path, root).replace("\\", "/")


def is_vendor_file(rel_path):
    if rel_path in VENDOR_NATIVE_FILES:
        return True
    return any(rel_path.startswith(p) for p in VENDOR_NATIVE_PREFIXES)


def is_text_file(rel_path):
    name = os.path.basename(rel_path)
    if name in IGNORE_FILENAMES:
        return False
    if name in SPECIAL_TEXT_FILENAMES:
        return True
    ext = os.path.splitext(name)[1].lower()
    return ext in TEXT_EXTENSIONS


def collect_project_files(base_dir, include_vendor=False):
    text_files = {}   # rel_path -> abs_path
    image_files = []  # rel_path

    for root, dirs, files in os.walk(base_dir):
        filtered_dirs = []
        for d in sorted(dirs):
            if d in IGNORE_DIRS:
                continue
            rel_sub = norm_rel(os.path.join(root, d), base_dir)
            if any(rel_sub == p or rel_sub.startswith(p + "/") for p in IGNORE_REL_PATHS):
                continue
            filtered_dirs.append(d)
        dirs[:] = filtered_dirs

        for fname in sorted(files):
            abs_p = os.path.join(root, fname)
            rel_p = norm_rel(abs_p, base_dir)
            if fname in IGNORE_FILENAMES:
                continue
            if not include_vendor and is_vendor_file(rel_p):
                continue

            ext = os.path.splitext(fname)[1].lower()
            if ext in IMAGE_EXTENSIONS:
                image_files.append(rel_p)
            elif is_text_file(rel_p):
                text_files[rel_p] = abs_p

    return text_files, image_files


def read_text_safe(abs_path):
    try:
        with open(abs_path, "r", encoding="utf-8", errors="ignore") as f:
            return f.read()
    except Exception as e:
        return f"[读取失败: {e}]"


def parse_rule_metadata(text_files):
    """静态解析 rules/*.py 的 RULE_ID / RULE_NAME / RULE_DESC / RULE_ALIASES"""
    rules_meta = []
    for rel_p, abs_p in sorted(text_files.items()):
        if not rel_p.startswith("rules/") or not rel_p.endswith(".py"):
            continue
        base_name = os.path.basename(rel_p)
        if base_name in ("__init__.py", "engine.py", "parser.py", "stubs.py"):
            continue

        code = read_text_safe(abs_p)
        info = {
            "file": rel_p,
            "id": os.path.splitext(base_name)[0],
            "name": "",
            "desc": "",
            "aliases": [],
            "has_resolve": False,
        }
        try:
            tree = ast.parse(code)
            for node in tree.body:
                if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                    key = node.targets[0].id
                    if key in ("RULE_ID", "RULE_NAME", "RULE_DESC", "RULE_ALIASES", "ALIASES"):
                        try:
                            val = ast.literal_eval(node.value)
                            if key == "RULE_ID" and isinstance(val, str):
                                info["id"] = val
                            elif key == "RULE_NAME" and isinstance(val, str):
                                info["name"] = val
                            elif key == "RULE_DESC" and isinstance(val, str):
                                info["desc"] = val
                            elif key in ("RULE_ALIASES", "ALIASES") and isinstance(val, (list, tuple)):
                                info["aliases"] = [str(x) for x in val]
                        except Exception:
                            pass
                elif isinstance(node, ast.FunctionDef) and node.name == "resolve_rules":
                    info["has_resolve"] = True
        except Exception:
            pass
        rules_meta.append(info)
    return rules_meta


def parse_java_modules_metadata(text_files):
    """静态解析 src/com/tencent/qqnt/patch/modules/**/*Module.java 的关键元信息"""
    modules_meta = []
    prefix = "src/com/tencent/qqnt/patch/modules/"
    for rel_p, abs_p in sorted(text_files.items()):
        if not rel_p.startswith(prefix) or not rel_p.endswith("Module.java"):
            continue

        code = read_text_safe(abs_p)
        m_id = re.search(r'getId\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"', code)
        if not m_id:
            m_const = re.search(r'FEATURE_ID\s*=\s*"([^"]+)"', code)
            mod_id = m_const.group(1) if m_const else ""
        else:
            mod_id = m_id.group(1)

        m_name = re.search(r'getName\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"', code)
        m_cat = re.search(r'getCategory\s*\(\s*\)\s*\{\s*return\s+([A-Z0-9_]+)', code)
        m_def = re.search(r'defaultEnabled\s*\(\s*\)\s*\{\s*return\s+(true|false)\s*;', code)

        modules_meta.append({
            "file": rel_p,
            "class_name": os.path.splitext(os.path.basename(rel_p))[0],
            "id": mod_id,
            "name": m_name.group(1) if m_name else "",
            "category": m_cat.group(1) if m_cat else "CATEGORY_CHAT",
            "default_enabled": (m_def.group(1) == "true") if m_def else None,
        })
    return modules_meta


def parse_features_py(text_files):
    """静态读取 pipeline/features.py 中的 BUILTIN_FEATURES 与 BUS_DEPENDENCIES"""
    builtin_ids = {}
    bus_deps = {}
    abs_p = text_files.get("pipeline/features.py")
    if not abs_p:
        return builtin_ids, bus_deps

    code = read_text_safe(abs_p)
    try:
        tree = ast.parse(code)
        for node in tree.body:
            if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
                name = node.targets[0].id
                if name == "BUILTIN_FEATURES":
                    val = ast.literal_eval(node.value)
                    if isinstance(val, dict):
                        builtin_ids = val
                elif name == "BUS_DEPENDENCIES":
                    val = ast.literal_eval(node.value)
                    if isinstance(val, dict):
                        bus_deps = val
    except Exception:
        pass
    return builtin_ids, bus_deps


# =============================================================================
# 代码骨架提取器 (Python AST & Java 状态机)
# =============================================================================

def _collect_self_attrs(target):
    attrs = []
    if isinstance(target, ast.Attribute) and isinstance(target.value, ast.Name) and target.value.id == "self":
        attrs.append(target.attr)
    elif isinstance(target, (ast.Tuple, ast.List)):
        for elt in target.elts:
            attrs.extend(_collect_self_attrs(elt))
    return attrs


def _extract_py_init_attrs(init_node, lines):
    """提取 __init__ 中非私有 (不以 _ 开头) 的 self.xxx 实例属性赋值"""
    out = []
    for stmt in init_node.body:
        if isinstance(stmt, ast.Assign):
            attrs = []
            for t in stmt.targets:
                attrs.extend(_collect_self_attrs(t))
        elif isinstance(stmt, ast.AnnAssign):
            attrs = _collect_self_attrs(stmt.target)
        else:
            continue

        if attrs and any(not a.startswith("_") for a in attrs):
            end_ln = getattr(stmt, "end_lineno", stmt.lineno)
            if end_ln - stmt.lineno <= 2:
                for idx in range(stmt.lineno - 1, end_ln):
                    out.append(lines[idx])
            else:
                out.append(lines[stmt.lineno - 1].rstrip() + " ...")
    return out


def _extract_py_stmt_skeleton(node, lines, indent=""):
    out = []
    if isinstance(node, ast.ClassDef):
        start_ln = min([d.lineno for d in node.decorator_list] + [node.lineno])
        body_start_ln = node.body[0].lineno if node.body else (node.end_lineno + 1)
        for idx in range(start_ln - 1, body_start_ln - 1):
            out.append(lines[idx])

        body_items = list(node.body)
        if body_items and isinstance(body_items[0], ast.Expr) and isinstance(getattr(body_items[0], "value", None), ast.Constant) and isinstance(body_items[0].value.value, str):
            doc_node = body_items.pop(0)
            for idx in range(doc_node.lineno - 1, doc_node.end_lineno):
                out.append(lines[idx])

        if not body_items:
            out.append(indent + "    ...")
        else:
            for sub in body_items:
                out.extend(_extract_py_stmt_skeleton(sub, lines, indent + "    "))
        return out

    if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
        start_ln = min([d.lineno for d in node.decorator_list] + [node.lineno])
        first_stmt = node.body[0] if node.body else None
        if first_stmt and first_stmt.lineno == node.lineno:
            for idx in range(start_ln - 1, node.lineno - 1):
                out.append(lines[idx])
            def_line = lines[node.lineno - 1]
            sig_part = def_line[:first_stmt.col_offset].rstrip()
            out.append(sig_part + " ...")
            return out

        sig_end_ln = first_stmt.lineno if first_stmt else (node.end_lineno + 1)
        for idx in range(start_ln - 1, sig_end_ln - 1):
            out.append(lines[idx])

        if first_stmt and isinstance(first_stmt, ast.Expr) and isinstance(getattr(first_stmt, "value", None), ast.Constant) and isinstance(first_stmt.value.value, str):
            for idx in range(first_stmt.lineno - 1, first_stmt.end_lineno):
                out.append(lines[idx])

        if node.name == "__init__":
            out.extend(_extract_py_init_attrs(node, lines))

        out.append(indent + "    ...")
        return out

    if isinstance(node, (ast.Assign, ast.AnnAssign)):
        end_ln = getattr(node, "end_lineno", node.lineno)
        if end_ln - node.lineno <= 2:
            for idx in range(node.lineno - 1, end_ln):
                out.append(lines[idx])
        else:
            first_line = lines[node.lineno - 1].rstrip()
            if first_line.endswith("["):
                out.append(first_line + " ... ]")
            elif first_line.endswith("{"):
                out.append(first_line + " ... }")
            else:
                out.append(first_line + " ...")
        return out

    return out


def extract_python_skeleton(code):
    try:
        tree = ast.parse(code)
    except Exception:
        return code

    lines = code.splitlines()
    out = []
    body = list(tree.body)
    if body and isinstance(body[0], ast.Expr) and isinstance(getattr(body[0], "value", None), ast.Constant) and isinstance(body[0].value.value, str):
        doc = body.pop(0)
        for idx in range(doc.lineno - 1, doc.end_lineno):
            out.append(lines[idx])
        out.append("")

    for node in body:
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            continue
        snippet = _extract_py_stmt_skeleton(node, lines, "")
        if snippet:
            out.extend(snippet)
            if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
                out.append("")

    result = "\n".join(out).strip()
    return result if result else code


def _strip_java_annotations(header):
    """剥离前导注解 @Xxx(...)，用于判断修饰符是否为 private"""
    s = header.lstrip()
    while s.startswith("@"):
        if s.startswith("@interface"):
            break
        m = re.match(r"^@[A-Za-z0-9_$.]+", s)
        if not m:
            break
        pos = m.end()
        while pos < len(s) and s[pos].isspace():
            pos += 1
        if pos < len(s) and s[pos] == "(":
            depth = 0
            in_str = False
            esc = False
            while pos < len(s):
                ch = s[pos]
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    in_str = not in_str
                elif not in_str:
                    if ch == "(":
                        depth += 1
                    elif ch == ")":
                        depth -= 1
                        if depth == 0:
                            pos += 1
                            break
                pos += 1
        s = s[pos:].lstrip()
    return s


def _is_private_decl(header):
    core = _strip_java_annotations(header)
    return bool(re.match(r"^private\b", core))


def _is_type_decl(header):
    core = _strip_java_annotations(header)
    if "=" in core:
        return False
    return bool(re.search(r"(?:^|\s)(?:class|interface|enum|@interface)\s+[A-Za-z0-9_$]+", core))


def extract_java_skeleton(code):
    """基于状态机的大括号层级扫描：保留包声明、非私有类型/接口/字段与公开方法签名"""
    n = len(code)
    i = 0
    scope_stack = []  # "TYPE" | "ENUM"
    skip_depth = 0
    out_parts = []
    decl_buf = []
    pending_javadoc = ""

    while i < n:
        ch = code[i]
        nxt = code[i + 1] if i + 1 < n else ""

        # 1. 行注释 //
        if ch == "/" and nxt == "/":
            i += 2
            while i < n and code[i] != "\n":
                i += 1
            continue

        # 2. 块注释 /* ... */ 或 /** ... */
        if ch == "/" and nxt == "*":
            end = code.find("*/", i + 2)
            end = (end + 2) if end != -1 else n
            comment_text = code[i:end]
            if skip_depth == 0 and comment_text.startswith("/**"):
                pending_javadoc = comment_text
            i = end
            continue

        # 3. 字符串与字符字面量
        if ch == '"' or ch == "'":
            quote = ch
            start = i
            i += 1
            while i < n:
                if code[i] == "\\":
                    i += 2
                    continue
                if code[i] == quote:
                    i += 1
                    break
                i += 1
            if skip_depth == 0:
                decl_buf.append(code[start:i])
            continue

        # 4. 跳过已折叠的方法体 / 私有块 / 匿名内部类块
        if skip_depth > 0:
            if ch == "{":
                skip_depth += 1
            elif ch == "}":
                skip_depth -= 1
            i += 1
            continue

        # 5. 顶层或类型体内部处理
        indent = "    " * len(scope_stack)

        if ch == ";":
            stmt = re.sub(r"\s+", " ", "".join(decl_buf)).strip()
            decl_buf.clear()
            if stmt:
                if stmt.startswith("import "):
                    pass
                elif stmt.startswith("package ") or not _is_private_decl(stmt):
                    if pending_javadoc:
                        for jline in pending_javadoc.splitlines():
                            out_parts.append(indent + jline.strip())
                    out_parts.append(indent + stmt + ";")
            pending_javadoc = ""
            i += 1
            continue

        if ch == "{":
            header = re.sub(r"\s+", " ", "".join(decl_buf)).strip()
            core = _strip_java_annotations(header)

            if _is_type_decl(header):
                decl_buf.clear()
                if _is_private_decl(header):
                    pending_javadoc = ""
                    skip_depth = 1
                else:
                    if pending_javadoc:
                        for jline in pending_javadoc.splitlines():
                            out_parts.append(indent + jline.strip())
                        pending_javadoc = ""
                    out_parts.append(indent + header + " {")
                    kind = "ENUM" if re.search(r"\benum\b", core) else "TYPE"
                    scope_stack.append(kind)
            elif "=" in core:
                # 字段初始化表达式内部出现匿名类 / Lambda / 数组初始化块，保留 decl_buf 直到 ';' 统一收尾
                if not _is_private_decl(header):
                    decl_buf.append(" { /* ... */ }")
                skip_depth = 1
            else:
                decl_buf.clear()
                if not header or core == "static" or _is_private_decl(header):
                    pending_javadoc = ""
                    skip_depth = 1
                else:
                    if pending_javadoc:
                        for jline in pending_javadoc.splitlines():
                            out_parts.append(indent + jline.strip())
                        pending_javadoc = ""
                    out_parts.append(indent + header + " { /* ... */ }")
                    skip_depth = 1
            i += 1
            continue

        if ch == "}":
            rem = re.sub(r"\s+", " ", "".join(decl_buf)).strip()
            decl_buf.clear()
            pending_javadoc = ""
            if scope_stack and scope_stack[-1] == "ENUM" and rem:
                out_parts.append(indent + rem)
            if scope_stack:
                scope_stack.pop()
                out_parts.append(("    " * len(scope_stack)) + "}")
                if not scope_stack:
                    out_parts.append("")
            i += 1
            continue

        decl_buf.append(ch)
        i += 1

    res = "\n".join(out_parts).strip()
    return res if res else code


# =============================================================================
# 依赖召回与检查
# =============================================================================

def build_java_class_index(text_files):
    """构建 Java 类名 / 短名 到 相对路径 的映射表"""
    fqcn_map = {}
    short_map = {}
    for rel_p in text_files:
        if not rel_p.startswith("src/") or not rel_p.endswith(".java"):
            continue
        fqcn = rel_p[len("src/"):-len(".java")].replace("/", ".")
        short = fqcn.split(".")[-1]
        fqcn_map[fqcn] = rel_p
        short_map.setdefault(short, []).append(rel_p)
    return fqcn_map, short_map


def find_direct_dependencies(seed_files, text_files, bus_deps, rules_meta):
    """根据种子文件分析其直接引用的项目内 Java 类和总线规则"""
    fqcn_map, short_map = build_java_class_index(text_files)
    extra_full = set()

    skeleton_set = set(SKELETON_INFRA_FILES)
    rule_id_set = set()
    for rm in rules_meta:
        if rm["file"] in seed_files:
            rule_id_set.add(rm["id"])

    for rel_p in seed_files:
        if rel_p.endswith("Module.java"):
            code = read_text_safe(text_files[rel_p])
            m = re.search(r'getId\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"', code)
            if m:
                rule_id_set.add(m.group(1))
            m2 = re.search(r'FEATURE_ID\s*=\s*"([^"]+)"', code)
            if m2:
                rule_id_set.add(m2.group(1))

    for _, deps in bus_deps.items():
        if isinstance(deps, (set, list, tuple)) and (set(deps) & rule_id_set):
            if "rules/base_rules.py" in text_files:
                extra_full.add("rules/base_rules.py")

    for rel_p in sorted(seed_files):
        if rel_p not in text_files:
            continue
        content = read_text_safe(text_files[rel_p])

        if rel_p.endswith(".java"):
            for m in re.finditer(r"\b(com\.tencent\.[A-Za-z0-9_.]+|me\.yxp\.qfun\.[A-Za-z0-9_.]+|de\.robv\.android\.xposed\.[A-Za-z0-9_.]+)", content):
                ref = m.group(1)
                if ref in fqcn_map:
                    cand = fqcn_map[ref]
                    if cand not in skeleton_set:
                        extra_full.add(cand)

            pkg_dir = os.path.dirname(rel_p)
            for short, paths in short_map.items():
                if re.search(r"\b" + re.escape(short) + r"\b", content):
                    for cand in paths:
                        if cand not in skeleton_set and (os.path.dirname(cand) == pkg_dir or cand.startswith(STUB_PATH_PREFIXES)):
                            extra_full.add(cand)

        elif rel_p.endswith(".py"):
            for m in re.finditer(r"L(com/tencent/qqnt/patch/[A-Za-z0-9_/$]+);", content):
                cls_path = "src/" + m.group(1).split("$")[0] + ".java"
                if cls_path in text_files and cls_path not in skeleton_set:
                    extra_full.add(cls_path)
            for m in re.finditer(r"\b(com\.tencent\.qqnt\.patch\.[A-Za-z0-9_.]+)", content):
                ref = m.group(1)
                if ref in fqcn_map and fqcn_map[ref] not in skeleton_set:
                    extra_full.add(fqcn_map[ref])

    return extra_full


def find_emoji_issues(text_files):
    issues = []
    for rel_p, abs_p in sorted(text_files.items()):
        if rel_p == "scan_files.py" or rel_p.endswith(".md"):
            continue
        content = read_text_safe(abs_p)
        for lineno, line in enumerate(content.splitlines(), 1):
            if EMOJI_PATTERN.search(line):
                whitelisted = any(rel_p == wp and wtext in line for wp, wtext in EMOJI_WHITELIST)
                if not whitelisted:
                    issues.append((rel_p, lineno, line.strip()[:100]))
    return issues


def run_release_checks(text_files, rules_meta, modules_meta, builtin_ids):
    """全量发版前的静态体检报告"""
    lines = []
    rule_ids = {r["id"]: r for r in rules_meta}
    all_known_ids = set(rule_ids.keys()) | set(builtin_ids.keys())

    id_warnings = []
    default_on_warnings = []
    for mm in modules_meta:
        mid = mm["id"]
        if not mid:
            id_warnings.append(f"  - 未解析到 getId(): {mm['file']}")
        elif mid not in all_known_ids:
            id_warnings.append(f"  - 模块 ID [{mid}] 未在 rules/ 或 features.py 中注册: {mm['file']}")
        if mm["default_enabled"] is True and mid not in DEFAULT_ENABLED_WHITELIST:
            default_on_warnings.append(f"  - 默认开启违规 (应为 false): {mm['file']} ({mid})")

    rule_warnings = []
    for rm in rules_meta:
        if not rm["has_resolve"]:
            rule_warnings.append(f"  - 规则缺少 resolve_rules(dex_data_dict, meta): {rm['file']}")
        if rm["id"] not in ("base", "tinker") and not rm["aliases"]:
            rule_warnings.append(f"  - 规则缺少 RULE_ALIASES: {rm['file']}")

    emoji_issues = find_emoji_issues(text_files)

    lines.append("【Release 发版静态体检】")
    lines.append(f"- 扫描有效源码文件: {len(text_files)} 个 | 规则插件: {len(rules_meta)} 个 | Java模块: {len(modules_meta)} 个")
    if not id_warnings and not default_on_warnings and not rule_warnings and not emoji_issues:
        lines.append("- 检查结果: 全部通过 (ID对齐 / 默认关闭原则 / Emoji检查 均无异常)")
    else:
        for title, items in [
            ("ID 对齐异常", id_warnings),
            ("默认关闭原则异常", default_on_warnings),
            ("规则结构提醒", rule_warnings),
        ]:
            if items:
                lines.append(f"- {title} ({len(items)}):")
                lines.extend(items)
        if emoji_issues:
            lines.append(f"- 发现非白名单 Emoji ({len(emoji_issues)} 处):")
            for fp, ln, snippet in emoji_issues[:20]:
                lines.append(f"  - {fp}:{ln} -> {snippet}")
    return "\n".join(lines)


# =============================================================================
# 头部导航生成 & 估算 Token
# =============================================================================

def estimate_tokens(text):
    non_ascii = sum(1 for c in text if ord(c) > 127)
    ascii_cnt = len(text) - non_ascii
    return int(ascii_cnt * 0.28 + non_ascii * 1.1)


def build_feature_registry_table(rules_meta, modules_meta, builtin_ids):
    mod_by_id = {m["id"]: m for m in modules_meta if m["id"]}
    rule_by_id = {r["id"]: r for r in rules_meta}

    all_ids = sorted(set(rule_by_id.keys()) | set(mod_by_id.keys()) | set(builtin_ids.keys()))
    lines = ["【已有功能与 ID 索引表 (禁止重复 ID)】"]
    for fid in all_ids:
        r = rule_by_id.get(fid)
        m = mod_by_id.get(fid)
        b = builtin_ids.get(fid)
        name = (m["name"] if m and m["name"] else "") or (r["name"] if r and r["name"] else "") or (b.get("desc", "") if isinstance(b, dict) else "")
        parts = []
        if r:
            parts.append(r["file"])
        if m:
            parts.append(m["file"])
        loc = " + ".join(parts) if parts else "内置特性(features.py)"
        lines.append(f"- {fid:<20} | {name:<20} | {loc}")
    return "\n".join(lines)


def build_dev_cheatsheet():
    return "\n".join([
        "【AI 开发红线与 DexPatcher 速查 (必读)】",
        "1. 严禁在代码、注释、PLog 日志、UI 提示文案(Toast/Dialog/设置标题)中使用任何 Emoji 表情符号；界面描述保持客观简洁。",
        "2. 新增开关型功能通常仅需 2 个文件: rules/<id>_rules.py 与 src/com/tencent/qqnt/patch/modules/<chat|purify|misc>/<Name>Module.java。",
        "3. Java 模块类名与文件名必须以 Module.java 结尾 (stage4_pack.py 依赖该后缀自动生成 zzz_modules.list)，且 defaultEnabled() 必须返回 false。",
        "4. RULE_ID 必须与 Module.getId() 完全一致；rules/*.py 需定义 RULE_ID/RULE_NAME/RULE_DESC/RULE_ALIASES/RULE_ENABLED 与 resolve_rules(dex_data_dict, meta=None)。",
        "5. 仅需判断开关时在 Smali 中直接调用 PatchBridge.isEnabled(\"<id>\")，无需修改 PatchBridge、ModuleManager 或 features.py (除非挂载新总线)。",
        "6. 模块配置弹窗优先使用 SettingDialogBuilder 链式 DSL，避免手写原始 Dialog；日志统一使用 PLog，提示统一使用 ToastHelper。",
        "7. DexPatcher 规则支持 INSERT_BEFORE / REPLACE / REGEX_REPLACE / SUPER_REPLACE；反汇编强制使用 .locals 模式。",
        "8. INSERT_BEFORE 仅扫描片段中 vN 寄存器并按需扩容 .locals (不扫描 pN)；访问高编号形参前建议先 move-object/16 v0, pX 或 move/16 v0, pX。",
        "9. REGEX_REPLACE 的 smali 字段中捕获组用 \\1 / \\2 (引擎会自动转为 Java $1 / $2)；对静态方法插桩时无隐式 this，形参从 p0 开始。",
    ])


# =============================================================================
# 各种模式的文件选择器
# =============================================================================

def select_for_new_feature(text_files, image_files, rules_meta, modules_meta, builtin_ids):
    full_set = set()
    skeleton_set = set()

    for p in CORE_FULL_FILES + GOLDEN_SAMPLE_FILES:
        if p in text_files:
            full_set.add(p)

    for rel_p in text_files:
        if rel_p.startswith(STUB_PATH_PREFIXES):
            full_set.add(rel_p)

    for p in SKELETON_INFRA_FILES:
        if p in text_files and p not in full_set:
            skeleton_set.add(p)

    header_parts = [
        "模式: 写新功能精简上下文 (规范 + 核心接口 + 桩类 + 基础设施骨架 + 黄金范例 + 全项目索引)",
        "",
        build_dev_cheatsheet(),
        "",
        build_feature_registry_table(rules_meta, modules_meta, builtin_ids),
    ]
    if image_files:
        header_parts.append("\n【静态图片资产路径】\n" + "\n".join(f"- {p}" for p in image_files))

    return sorted(full_set), sorted(skeleton_set), "\n".join(header_parts)


def select_for_target(keywords, text_files, rules_meta, modules_meta, builtin_ids, bus_deps):
    kws = [k.strip().lower() for k in keywords if k.strip()]
    if not kws:
        return [], [], "未提供有效关键词"

    matched_files = set()

    matched_rule_ids = set()
    for rm in rules_meta:
        haystack = " ".join([rm["id"], rm["name"], rm["desc"], rm["file"]] + rm["aliases"]).lower()
        if any(k in haystack for k in kws):
            matched_files.add(rm["file"])
            matched_rule_ids.add(rm["id"])

    for mm in modules_meta:
        haystack = " ".join([mm["id"], mm["name"], mm["class_name"], mm["file"]]).lower()
        if any(k in haystack for k in kws) or (mm["id"] and mm["id"] in matched_rule_ids):
            matched_files.add(mm["file"])
            if mm["id"]:
                matched_rule_ids.add(mm["id"])

    for rm in rules_meta:
        if rm["id"] in matched_rule_ids:
            matched_files.add(rm["file"])

    for fid, meta in builtin_ids.items():
        if isinstance(meta, dict):
            hay = " ".join([fid, meta.get("desc", "")] + meta.get("aliases", [])).lower()
            if any(k in hay for k in kws):
                matched_rule_ids.add(fid)

    if not matched_files:
        for rel_p in text_files:
            if any(k in rel_p.lower() for k in kws):
                matched_files.add(rel_p)
    if not matched_files:
        for rel_p, abs_p in text_files.items():
            if rel_p.startswith(STUB_PATH_PREFIXES):
                continue
            content = read_text_safe(abs_p).lower()
            if any(k in content for k in kws):
                matched_files.add(rel_p)

    full_set = set(matched_files)
    if matched_files:
        deps = find_direct_dependencies(matched_files, text_files, bus_deps, rules_meta)
        full_set.update(deps)

    for p in ["pipeline/features.py", "src/com/tencent/qqnt/patch/IPatchModule.java", "src/com/tencent/qqnt/patch/PatchBridge.java"]:
        if p in text_files:
            full_set.add(p)

    skeleton_set = set()
    for p in SKELETON_INFRA_FILES:
        if p in text_files and p not in full_set:
            skeleton_set.add(p)

    header = "\n".join([
        f"模式: 定向修 Bug / 修改功能 (关键词: {', '.join(keywords)})",
        f"直接命中文件 ({len(matched_files)}): {', '.join(sorted(matched_files)) if matched_files else '无'}",
        "",
        build_dev_cheatsheet(),
    ])
    return sorted(full_set), sorted(skeleton_set), header


def select_for_scope(scope_name, text_files, rules_meta, modules_meta, builtin_ids):
    scope = scope_name.lower().strip()
    full_set = set()
    skeleton_set = set()

    if scope == "build":
        for rel_p in text_files:
            if rel_p in ("patcher.py", "DexPatcher.java", "scan_files.py", "DEVELOPER_GUIDE.md", "README.md"):
                full_set.add(rel_p)
            elif rel_p.startswith(("pipeline/", "tools/", ".github/")):
                full_set.add(rel_p)
    elif scope == "rules":
        for rel_p in text_files:
            if rel_p == "DexPatcher.java" or rel_p.startswith("rules/") or rel_p == "pipeline/features.py":
                full_set.add(rel_p)
            elif rel_p in ("src/com/tencent/qqnt/patch/PatchBridge.java", "src/com/tencent/qqnt/patch/IPatchModule.java"):
                full_set.add(rel_p)
    elif scope == "core":
        for rel_p in text_files:
            if rel_p in ("rules/setting_rules.py", "rules/base_rules.py", "pipeline/features.py"):
                full_set.add(rel_p)
            elif rel_p.startswith("src/com/tencent/qqnt/patch/") and not rel_p.startswith("src/com/tencent/qqnt/patch/modules/") and not rel_p.startswith("src/com/tencent/qqnt/patch/plugin/"):
                full_set.add(rel_p)
    elif scope == "plugin":
        for rel_p in text_files:
            if rel_p in ("rules/base_rules.py", "src/com/tencent/qqnt/patch/PatchBridge.java"):
                full_set.add(rel_p)
            elif rel_p.startswith(("src/com/tencent/qqnt/patch/plugin/", "src/me/yxp/qfun/", "src/de/robv/android/xposed/")):
                full_set.add(rel_p)
            elif rel_p.startswith(STUB_PATH_PREFIXES):
                full_set.add(rel_p)
    elif scope == "killer":
        for rel_p in text_files:
            if rel_p.startswith(("killer/", "killer_native/", "pipeline/providers/")) or rel_p in (
                "pipeline/killer_config.py", "pipeline/killer_payload.py", "rules/tinker_rules.py", "tools/NeoPacker.java"
            ):
                full_set.add(rel_p)
    else:
        return [], [], f"未知子系统域: {scope_name}"

    for p in SKELETON_INFRA_FILES:
        if p in text_files and p not in full_set:
            skeleton_set.add(p)

    header = "\n".join([
        f"模式: 子系统领域切片 (scope={scope})",
        "",
        build_dev_cheatsheet(),
    ])
    return sorted(full_set), sorted(skeleton_set), header


def select_for_git(base_dir, text_files, rules_meta, modules_meta, builtin_ids, bus_deps):
    changed_rel = set()
    try:
        res = subprocess.run(
            ["git", "status", "--porcelain"],
            cwd=base_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        if res.returncode == 0:
            for line in res.stdout.splitlines():
                if not line.strip():
                    continue
                path_part = line[3:].strip()
                if " -> " in path_part:
                    path_part = path_part.split(" -> ", 1)[1].strip()
                path_part = path_part.strip('"')
                if path_part in text_files:
                    changed_rel.add(path_part)
                else:
                    for rel_p, abs_p in text_files.items():
                        if abs_p.replace("\\", "/").endswith("/" + path_part.replace("\\", "/")):
                            changed_rel.add(rel_p)
    except Exception:
        pass

    full_set = set(changed_rel)
    if changed_rel:
        full_set.update(find_direct_dependencies(changed_rel, text_files, bus_deps, rules_meta))

    for p in CORE_FULL_FILES:
        if p in text_files:
            full_set.add(p)

    skeleton_set = {p for p in SKELETON_INFRA_FILES if p in text_files and p not in full_set}

    header = "\n".join([
        f"模式: Git 工作区增量上下文 (改动文件 {len(changed_rel)} 个)",
        f"变动清单: {', '.join(sorted(changed_rel)) if changed_rel else '当前工作区无变动'}",
        "",
        build_dev_cheatsheet(),
    ])
    return sorted(full_set), sorted(skeleton_set), header


def select_for_full(text_files, image_files, rules_meta, modules_meta, builtin_ids):
    full_list = sorted(text_files.keys())
    audit_report = run_release_checks(text_files, rules_meta, modules_meta, builtin_ids)

    header_parts = [
        "模式: Release 全量代码审查 (已自动过滤缓存目录与第三方原生库)",
        "",
        audit_report,
        "",
        build_feature_registry_table(rules_meta, modules_meta, builtin_ids),
    ]
    if image_files:
        header_parts.append("\n【项目图片资源路径】\n" + "\n".join(f"- {p}" for p in image_files))

    return full_list, [], "\n".join(header_parts)


# =============================================================================
# 写入文件与终端报告
# =============================================================================

def write_merged_output(base_dir, output_file, header_text, full_files, skeleton_files, text_files):
    chunks = []
    chunks.append("=" * 60)
    chunks.append("QQNT-Patcher 上下文打包摘要")
    chunks.append("=" * 60)
    chunks.append(header_text.strip())
    chunks.append("")

    for rel_p in skeleton_files:
        abs_p = text_files.get(rel_p)
        if not abs_p:
            continue
        raw = read_text_safe(abs_p)
        if rel_p.endswith(".py"):
            skel = extract_python_skeleton(raw)
        elif rel_p.endswith(".java"):
            skel = extract_java_skeleton(raw)
        else:
            skel = raw

        display_path = norm_rel(abs_p, os.path.normpath(os.path.join(base_dir, "..", "..")))
        chunks.append("=" * 60)
        chunks.append(f"【API 骨架(已折叠方法体，仅供调用参考)】路径: {display_path}")
        chunks.append("=" * 60)
        chunks.append("")
        chunks.append(skel)
        chunks.append("")

    for rel_p in full_files:
        abs_p = text_files.get(rel_p)
        if not abs_p:
            continue
        raw = read_text_safe(abs_p)
        display_path = norm_rel(abs_p, os.path.normpath(os.path.join(base_dir, "..", "..")))
        chunks.append("=" * 60)
        chunks.append(f"【文本文件内容】路径: {display_path}")
        chunks.append("=" * 60)
        chunks.append("")
        chunks.append(raw.rstrip())
        chunks.append("")

    final_text = "\n".join(chunks) + "\n"
    with open(output_file, "w", encoding="utf-8") as f:
        f.write(final_text)

    total_lines = final_text.count("\n")
    total_chars = len(final_text)
    est_tokens = estimate_tokens(final_text)

    print("\n" + "=" * 56)
    print(f"[DONE] 已生成并覆盖: {output_file}")
    print(f"  - 完整源码文件 : {len(full_files)} 个")
    print(f"  - API 骨架文件 : {len(skeleton_files)} 个")
    print(f"  - 产物总行数   : {total_lines} 行")
    print(f"  - 产物字符数   : {total_chars:,} 字符")
    print(f"  - 估算 Token   : 约 {est_tokens / 1000:.1f}k tokens ({est_tokens:,})")
    print("=" * 56)


# =============================================================================
# 交互式菜单 & 命令行入口
# =============================================================================

def interactive_choose():
    print("=" * 56)
    print("QQNT-Patcher 上下文打包工具 (输出覆盖 merged_qqntpatch.txt)")
    print("=" * 56)
    print("  [1] 写新功能模式   - 精简上下文 (规范 + 骨架 + 桩类 + 黄金范例)")
    print("  [2] 修 Bug / 改功能 - 按功能关键词、类名或文件精准召回")
    print("  [3] 按子系统切片   - build / rules / core / plugin / killer")
    print("  [4] Git 增量模式   - 仅当前工作区改动文件 + 核心骨架")
    print("  [5] 发版全量审查   - 全项目有效源码 + 自动体检报告")
    print("  [0] 退出")
    print("-" * 56)

    try:
        choice = input("请选择模式 [1-5, 默认 1]: ").strip()
    except (EOFError, KeyboardInterrupt):
        print("\n已取消")
        sys.exit(0)

    if choice == "0":
        sys.exit(0)
    if choice == "" or choice == "1":
        return "new", []
    if choice == "2":
        try:
            kw_raw = input("请输入关键词 (空格分隔多个, 如 anti_revoke 撤回 / 语音 / 广告): ").strip()
        except (EOFError, KeyboardInterrupt):
            sys.exit(0)
        kws = [x for x in re.split(r"[\s,，]+", kw_raw) if x]
        if not kws:
            print("[WARN] 关键词为空，转为写新功能模式")
            return "new", []
        return "target", kws
    if choice == "3":
        print("可选域: [1] build  [2] rules  [3] core  [4] plugin  [5] killer")
        try:
            sc_raw = input("请输入序号或域名称: ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            sys.exit(0)
        mapping = {"1": "build", "2": "rules", "3": "core", "4": "plugin", "5": "killer"}
        sc = mapping.get(sc_raw, sc_raw)
        return "scope", [sc]
    if choice == "4":
        return "git", []
    if choice == "5":
        return "full", []

    print(f"[WARN] 无效选项 [{choice}]，默认使用写新功能模式")
    return "new", []


def main():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    output_file = os.path.join(base_dir, "merged_qqntpatch.txt")

    parser = argparse.ArgumentParser(description="QQNT-Patcher 代码上下文智能打包脚本")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--new", action="store_true", help="写新功能精简模式")
    group.add_argument("-t", "--target", nargs="+", help="按功能/关键词/类名定向导出")
    group.add_argument("-s", "--scope", choices=["build", "rules", "core", "plugin", "killer"], help="按子系统领域切片导出")
    group.add_argument("--git", action="store_true", help="仅导出 Git 工作区变动文件及相关依赖")
    group.add_argument("--full", action="store_true", help="发版全量审查模式")
    group.add_argument("--files", nargs="+", help="手动指定需要导出全文的相对路径")
    parser.add_argument("--include-vendor", action="store_true", help="包含 killer_native 中的三方 xHook 头文件")

    if len(sys.argv) == 1:
        mode, mode_args = interactive_choose()
        include_vendor = False
    else:
        args = parser.parse_args()
        include_vendor = args.include_vendor
        if args.full:
            mode, mode_args = "full", []
        elif args.target:
            mode, mode_args = "target", args.target
        elif args.scope:
            mode, mode_args = "scope", [args.scope]
        elif args.git:
            mode, mode_args = "git", []
        elif args.files:
            mode, mode_args = "files", args.files
        else:
            mode, mode_args = "new", []

    text_files, image_files = collect_project_files(base_dir, include_vendor=include_vendor)
    rules_meta = parse_rule_metadata(text_files)
    modules_meta = parse_java_modules_metadata(text_files)
    builtin_ids, bus_deps = parse_features_py(text_files)

    if mode == "full":
        full_list, skel_list, header = select_for_full(text_files, image_files, rules_meta, modules_meta, builtin_ids)
    elif mode == "target":
        full_list, skel_list, header = select_for_target(mode_args, text_files, rules_meta, modules_meta, builtin_ids, bus_deps)
    elif mode == "scope":
        full_list, skel_list, header = select_for_scope(mode_args[0], text_files, rules_meta, modules_meta, builtin_ids)
    elif mode == "git":
        full_list, skel_list, header = select_for_git(base_dir, text_files, rules_meta, modules_meta, builtin_ids, bus_deps)
    elif mode == "files":
        chosen = {p.replace("\\", "/") for p in mode_args if p.replace("\\", "/") in text_files}
        full_list = sorted(chosen)
        skel_list = sorted(p for p in SKELETON_INFRA_FILES if p in text_files and p not in chosen)
        header = f"模式: 指定文件列表 ({len(full_list)} 个)\n\n{build_dev_cheatsheet()}"
    else:
        full_list, skel_list, header = select_for_new_feature(text_files, image_files, rules_meta, modules_meta, builtin_ids)

    write_merged_output(base_dir, output_file, header, full_list, skel_list, text_files)

    if mode == "full":
        emoji_issues = find_emoji_issues(text_files)
        if emoji_issues:
            print(f"[WARN] 检测到 {len(emoji_issues)} 处非白名单 Emoji，请查看 merged_qqntpatch.txt 顶部报告")


if __name__ == "__main__":
    main()