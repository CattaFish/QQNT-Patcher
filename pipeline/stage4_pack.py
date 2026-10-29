# -*- coding: utf-8 -*-
import os
import re
import json
import shutil
import zipfile
from .context import Colors


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
        "assets": []
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

    features_json_path = os.path.join(assets_dir, "zzz_features.json")
    with open(features_json_path, "w", encoding="utf-8") as f:
        json.dump(list(ctx.active_features), f, ensure_ascii=False, indent=2)
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

    print(f"  {Colors.CYAN}┌{Colors.RESET} 注入产物清单汇总:")
    print(f"  {Colors.CYAN}│{Colors.RESET}  ├─ 重构宿主 DEX  : {Colors.BOLD}{injected_summary['host_dex']}{Colors.RESET} 个分包")
    print(f"  {Colors.CYAN}│{Colors.RESET}  ├─ 注入扩展 DEX  : {Colors.BOLD}{injected_summary['extra_dex']}{Colors.RESET} 个分包")
    if injected_summary["sos"]:
        so_desc = ", ".join(injected_summary["sos"])
        print(f"  {Colors.CYAN}│{Colors.RESET}  ├─ 原生 Native 库: {Colors.BOLD}{so_desc}{Colors.RESET}")
    print(f"  {Colors.CYAN}│{Colors.RESET}  └─ 挂载静态资产  : {Colors.BOLD}{len(injected_summary['assets'])}{Colors.RESET} 项文件 (含自动装配清单)")

    ctx.log("OK", "装配注入目录准备完毕")
