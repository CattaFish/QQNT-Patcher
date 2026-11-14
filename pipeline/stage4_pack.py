# -*- coding: utf-8 -*-
import os
import re
import json
import shlex
import shutil
import hashlib
import zipfile
from .context import Colors


def _collect_icon_files(icons_dir):
    if not os.path.isdir(icons_dir):
        return []
    files = []
    for name in sorted(os.listdir(icons_dir)):
        if name.startswith("."):
            continue
        fp = os.path.join(icons_dir, name)
        if os.path.isfile(fp) and name.lower().endswith((".png", ".webp")):
            files.append((name, fp))
    return files


def _collect_custom_names(icons_dir):
    names_file = os.path.join(icons_dir, "names.txt")
    if not os.path.isfile(names_file):
        return []
    names = []
    try:
        with open(names_file, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip().lstrip("\ufeff")
                if not s or s.startswith("#") or s == "QQ":
                    continue
                if s not in names:
                    names.append(s)
    except Exception:
        pass
    return names


def _compute_icon_cache_key(ctx, icon_files, custom_names):
    h = hashlib.sha256()
    h.update((ctx.orig_apk_md5 or "").encode("utf-8"))
    patcher_src = os.path.join(ctx.tools_dir, "IconManifestPatcher.java")
    if os.path.isfile(patcher_src):
        h.update(str(os.path.getmtime(patcher_src)).encode("utf-8"))
    for name, fp in icon_files:
        h.update(name.encode("utf-8"))
        with open(fp, "rb") as f:
            while chunk := f.read(65536):
                h.update(chunk)
    h.update(json.dumps(custom_names, ensure_ascii=False).encode("utf-8"))
    return h.hexdigest()[:16]


def _prepare_custom_icons(ctx, inject_dir, injected_summary):
    if "custom_icon" not in ctx.active_features:
        return
    icon_files = _collect_icon_files(ctx.icons_src_dir)
    custom_names = _collect_custom_names(ctx.icons_src_dir)
    if not icon_files and not custom_names:
        return

    icon_patcher_class = os.path.join(ctx.engine_bin, "com/tencent/qqnt/patcher/IconManifestPatcher.class")
    if not os.path.isfile(ctx.arsclib_jar) or not os.path.isfile(icon_patcher_class):
        ctx.log("WARN", "缺少 tools/arsclib.jar 或 IconManifestPatcher，跳过自定义图标/名称注入")
        return

    cache_root = os.path.join(ctx.work_dir, "icon_cache")
    os.makedirs(cache_root, exist_ok=True)
    cache_key = _compute_icon_cache_key(ctx, icon_files, custom_names)
    cached_dir = os.path.join(cache_root, cache_key)

    manifest_ok = os.path.isfile(os.path.join(cached_dir, "AndroidManifest.xml"))
    arsc_ok = (not icon_files) or os.path.isfile(os.path.join(cached_dir, "resources.arsc"))
    json_ok = os.path.isfile(os.path.join(cached_dir, "assets", "zzz_icons.json"))

    if manifest_ok and arsc_ok and json_ok:
        ctx.log("CACHE", f"自定义图标与名称缓存命中 ({len(icon_files)} 套图标, {len(custom_names)} 个名称)")
    else:
        # 清理旧缓存，避免堆积 9MB 级别的旧 resources.arsc
        for old in os.listdir(cache_root):
            old_p = os.path.join(cache_root, old)
            if os.path.isdir(old_p):
                shutil.rmtree(old_p, ignore_errors=True)
        os.makedirs(cached_dir, exist_ok=True)

        cp = f"{shlex.quote(ctx.engine_bin)}:{shlex.quote(ctx.arsclib_jar)}"
        cmd = (
            f"java -Xms128m -Xmx512m -cp {cp} "
            f"com.tencent.qqnt.patcher.IconManifestPatcher "
            f"{shlex.quote(ctx.input_apk)} {shlex.quote(cached_dir)} {shlex.quote(ctx.icons_src_dir)}"
        )
        ret = ctx.run_cmd_stream(cmd)
        if ret != 0 or not os.path.isfile(os.path.join(cached_dir, "AndroidManifest.xml")):
            ctx.log("ERR", "IconManifestPatcher 执行失败，跳过本次图标/名称注入")
            shutil.rmtree(cached_dir, ignore_errors=True)
            return

    # 合并写入 inject_dir (AndroidManifest.xml, resources.arsc, res/..., assets/...)
    for root, _, files in os.walk(cached_dir):
        for fname in files:
            src_fp = os.path.join(root, fname)
            rel_fp = os.path.relpath(src_fp, cached_dir)
            dst_fp = os.path.join(inject_dir, rel_fp)
            os.makedirs(os.path.dirname(dst_fp), exist_ok=True)
            shutil.copyfile(src_fp, dst_fp)
            if rel_fp.startswith("assets" + os.sep) or rel_fp.startswith("assets/"):
                injected_summary["assets"].append(rel_fp.replace(os.sep, "/")[len("assets/"):])

    injected_summary["custom_icons"] = len(icon_files)
    injected_summary["custom_names"] = len(custom_names)


def run_stage4(ctx):
    ctx.print_stage_header(4, 5, "Assemble Assets & Payload")

    inject_dir = os.path.join(ctx.work_dir, "inject")
    if os.path.exists(inject_dir):
        shutil.rmtree(inject_dir, ignore_errors=True)
    os.makedirs(inject_dir, exist_ok=True)

    injected_summary = {
        "host_dex": 0,
        "extra_dex": 0,
        "sos": [],
        "assets": [],
        "custom_icons": 0,
        "custom_names": 0
    }

    for local_path, in_zip_name in ctx.modified_dex_files:
        if os.path.isfile(local_path):
            shutil.copyfile(local_path, os.path.join(inject_dir, in_zip_name))
            injected_summary["host_dex"] += 1

    libs_dex_name = f"classes{ctx.max_dex_idx + 1}.dex"
    patch_dex_name = f"classes{ctx.max_dex_idx + 2}.dex"
    if ctx.libs_dex_path and os.path.isfile(ctx.libs_dex_path):
        shutil.copyfile(ctx.libs_dex_path, os.path.join(inject_dir, libs_dex_name))
        injected_summary["extra_dex"] += 1

    if ctx.patch_dex_path and os.path.isfile(ctx.patch_dex_path):
        shutil.copyfile(ctx.patch_dex_path, os.path.join(inject_dir, patch_dex_name))
        injected_summary["extra_dex"] += 1

    if "killer" in ctx.active_features:
        for dex_name, src_path in ctx.provider.get_extra_dexes(ctx):
            shutil.copyfile(src_path, os.path.join(inject_dir, dex_name))
            injected_summary["extra_dex"] += 1

    if "killer" in ctx.active_features:
        apk_abis = set()
        with zipfile.ZipFile(ctx.input_apk, 'r') as zf:
            for n in zf.namelist():
                m = re.match(r'^lib/([^/]+)/', n)
                if m: apk_abis.add(m.group(1))

        for abi, rel_so_path, src_path in ctx.provider.get_extra_sos(ctx, apk_abis):
            dst_so_dir = os.path.join(inject_dir, "lib", abi)
            os.makedirs(dst_so_dir, exist_ok=True)
            shutil.copyfile(src_path, os.path.join(dst_so_dir, os.path.basename(rel_so_path)))
            injected_summary["sos"].append(f"{abi}/{os.path.basename(rel_so_path)}")

    assets_dir = os.path.join(inject_dir, "assets")
    os.makedirs(assets_dir, exist_ok=True)

    # 包含 only_mode 元数据，以便运行时通用判断精简构建模式
    features_json_path = os.path.join(assets_dir, "zzz_features.json")
    features_payload = {
        "only_mode": bool(ctx.only_keywords),
        "features": list(ctx.active_features)
    }
    with open(features_json_path, "w", encoding="utf-8") as f:
        json.dump(features_payload, f, ensure_ascii=False, indent=2)
    injected_summary["assets"].append("zzz_features.json")

    # 动态扫描 Java 模块并生成自动装配清单 zzz_modules.list
    modules_dir = os.path.join(ctx.root_dir, "src", "com", "tencent", "qqnt", "patch", "modules")
    modules_list = []
    if os.path.isdir(modules_dir):
        for root, _, files in os.walk(modules_dir):
            for f in files:
                if f.endswith("Module.java"):
                    rel_p = os.path.relpath(os.path.join(root, f), os.path.join(ctx.root_dir, "src"))
                    cls_name = os.path.splitext(rel_p)[0].replace(os.sep, ".")
                    modules_list.append(cls_name)

    modules_list.sort()
    modules_list_path = os.path.join(assets_dir, "zzz_modules.list")
    with open(modules_list_path, "w", encoding="utf-8") as f:
        for m_name in modules_list:
            f.write(m_name + "\n")
    injected_summary["assets"].append("zzz_modules.list")

    if "script" in ctx.active_features:
        if ctx.bsh_standalone_dex and os.path.isfile(ctx.bsh_standalone_dex):
            shutil.copyfile(ctx.bsh_standalone_dex, os.path.join(assets_dir, "bsh.dex"))
            injected_summary["assets"].append("bsh.dex")

        if ctx.preset_plugins_zip and os.path.isfile(ctx.preset_plugins_zip):
            shutil.copyfile(ctx.preset_plugins_zip, os.path.join(assets_dir, "preset_plugins.zip"))
            injected_summary["assets"].append("preset_plugins.zip")

    if "setting" in ctx.active_features and os.path.isdir(ctx.assets_src_dir):
        for root, _, files in os.walk(ctx.assets_src_dir):
            for f in files:
                if not f.startswith("."):
                    src_fp = os.path.join(root, f)
                    rel_fp = os.path.relpath(src_fp, ctx.assets_src_dir)
                    dst_fp = os.path.join(assets_dir, rel_fp)
                    os.makedirs(os.path.dirname(dst_fp), exist_ok=True)
                    shutil.copyfile(src_fp, dst_fp)
                    injected_summary["assets"].append(rel_fp)

    # 注入自定义图标与名称（修改 AndroidManifest.xml + resources.arsc + res/ + assets/icons/）
    _prepare_custom_icons(ctx, inject_dir, injected_summary)

    print(f"  {Colors.CYAN}┌{Colors.RESET} 注入产物清单汇总:")
    print(f"  {Colors.CYAN}│{Colors.RESET}  ├─ 重构宿主 DEX  : {Colors.BOLD}{injected_summary['host_dex']}{Colors.RESET} 个分包")
    print(f"  {Colors.CYAN}│{Colors.RESET}  ├─ 注入扩展 DEX  : {Colors.BOLD}{injected_summary['extra_dex']}{Colors.RESET} 个分包")
    if injected_summary["sos"]:
        so_desc = ", ".join(injected_summary["sos"])
        print(f"  {Colors.CYAN}│{Colors.RESET}  ├─ 原生 Native 库: {Colors.BOLD}{so_desc}{Colors.RESET}")
    if injected_summary["custom_icons"] > 0 or injected_summary["custom_names"] > 0:
        print(f"  {Colors.CYAN}│{Colors.RESET}  ├─ 自定义图标名称: {Colors.BOLD}{injected_summary['custom_icons']}{Colors.RESET} 套图标 / {Colors.BOLD}{injected_summary['custom_names']}{Colors.RESET} 个名称")
    print(f"  {Colors.CYAN}│{Colors.RESET}  └─ 挂载静态资产  : {Colors.BOLD}{len(injected_summary['assets'])}{Colors.RESET} 项文件 (含自动装配清单)")

    ctx.log("OK", "装配注入目录准备完毕")