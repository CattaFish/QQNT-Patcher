#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import shutil
import zipfile
import subprocess
import re
import shlex
import struct
import time
import hashlib
import json
import rules
import native_patcher

TOOLS_DIR = os.path.abspath("./tools")
PRESET_PLUGINS_DIR = os.path.abspath("./preset_plugins")
BAKSMALI_JAR = os.path.join(TOOLS_DIR, "baksmali.jar")
SMALI_JAR = os.path.join(TOOLS_DIR, "smali.jar")
DEXLIB2_JAR = os.path.join(TOOLS_DIR, "dexlib2.jar")
GUAVA_JAR = os.path.join(TOOLS_DIR, "guava.jar")
BSH_JAR = os.path.join(TOOLS_DIR, "bsh.jar")
DX_JAR = os.path.join(TOOLS_DIR, "dx.jar")
PROTOBUF_JAR = os.path.join(TOOLS_DIR, "protobuf.jar")
ANDROID_JAR = os.path.join(TOOLS_DIR, "android.jar")
FIXED_KEYSTORE = os.path.join(TOOLS_DIR, "debug.keystore")

JAVA_OPTS = "-Xms256m -Xmx768m -XX:+UseParallelGC"

REQUIRED_JARS = [
    ("baksmali.jar", BAKSMALI_JAR),
    ("smali.jar", SMALI_JAR),
    ("dexlib2.jar", DEXLIB2_JAR),
    ("guava.jar", GUAVA_JAR),
    ("bsh.jar", BSH_JAR),
    ("dx.jar", DX_JAR),
    ("protobuf.jar", PROTOBUF_JAR),
    ("android.jar", ANDROID_JAR)
]

def log(tag, msg):
    colors = {
        "INFO": "\033[1;34m[INFO]\033[0m",
        "OK": "\033[1;32m[SUCCESS]\033[0m",
        "WARN": "\033[1;33m[WARN]\033[0m",
        "ERR": "\033[1;31m[ERROR]\033[0m",
        "TIME": "\033[1;35m[TIME]\033[0m"
    }
    print(f"{colors.get(tag, '[*]')} {msg}")

def run_cmd(cmd, cwd=None):
    ret = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd)
    out_msg = ret.stdout.decode('utf-8', errors='ignore').strip()
    err_msg = ret.stderr.decode('utf-8', errors='ignore').strip()
    if err_msg:
        for line in err_msg.split("\n"):
            if "[WARN]" in line:
                log("WARN", line)
            elif "[ERROR]" in line or "Exception" in line:
                log("ERR", line)
    if ret.returncode != 0:
        log("ERR", f"命令执行失败 (Exit code {ret.returncode}): {cmd}")
        if err_msg: log("ERR", err_msg)
        if out_msg: log("WARN", out_msg)
        return ""
    return ret.stdout.decode('utf-8', errors='ignore')

def run_cmd_stream(cmd, cwd=None):
    p = subprocess.Popen(
        cmd,
        shell=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        cwd=cwd,
        universal_newlines=True,
        bufsize=1
    )
    for line in p.stdout:
        line_str = line.strip()
        if not line_str:
            continue
        if "[WARN]" in line_str:
            log("WARN", line_str)
        elif "[ERROR]" in line_str:
            log("ERR", line_str)
        elif "[DexPatcher]" in line_str:
            print(f"\033[1;32m[*] {line_str}\033[0m")
        else:
            print(f"    {line_str}")
    p.wait()
    return p.returncode

def ensure_smali_jars():
    missing_jars = []
    for name, path in REQUIRED_JARS:
        if not os.path.exists(path):
            missing_jars.append(f"{name} (文件不存在)")
        elif os.path.getsize(path) <= 1024:
            missing_jars.append(f"{name} (文件小于等于 1KB: {os.path.getsize(path)} 字节)")

    if missing_jars:
        log("ERR", "=" * 60)
        log("ERR", "缺少必要的 tools 依赖 Jar 包，无法继续构建：")
        for item in missing_jars:
            log("ERR", f"  -> tools/{item}")
        log("ERR", "=" * 60)
        sys.exit(1)

def ensure_fixed_keystore():
    os.makedirs(TOOLS_DIR, exist_ok=True)
    if not os.path.exists(FIXED_KEYSTORE):
        log("INFO", "正在初始化固定签名证书 (仅首次生成)...")
        run_cmd(f"keytool -genkey -v -keystore {shlex.quote(FIXED_KEYSTORE)} -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -storepass android -keypass android -dname 'CN=Android Debug,O=Android,C=US'")

def ensure_preset_plugins_dir():
    os.makedirs(PRESET_PLUGINS_DIR, exist_ok=True)
    keep_file = os.path.join(PRESET_PLUGINS_DIR, ".gitkeep")
    if not os.path.exists(keep_file):
        try:
            with open(keep_file, "w", encoding="utf-8") as f:
                f.write("")
        except Exception:
            pass

def pack_preset_plugins_if_exist(work_dir):
    ensure_preset_plugins_dir()
    valid_files = []
    for root, _, files in os.walk(PRESET_PLUGINS_DIR):
        for f in files:
            if f != ".gitkeep" and f != "README.md":
                valid_files.append(os.path.join(root, f))

    if not valid_files:
        return None

    target_zip = os.path.join(work_dir, "preset_plugins.zip")
    latest_plugin_mtime = max(os.path.getmtime(f) for f in valid_files)
    if os.path.exists(target_zip) and os.path.getmtime(target_zip) >= latest_plugin_mtime:
        return target_zip

    log("INFO", f"检测到预设脚本变动 (装载 {len(valid_files)} 个脚本/资源文件)，正在封装预设脚本包...")
    with zipfile.ZipFile(target_zip, 'w', compression=zipfile.ZIP_DEFLATED) as zf:
        for file_path in valid_files:
            arcname = os.path.relpath(file_path, PRESET_PLUGINS_DIR)
            zf.write(file_path, arcname)

    log("OK", f"  -> 预设脚本包封装完成: \033[36m{os.path.getsize(target_zip)} 字节\033[0m")
    return target_zip

def extract_original_apk_metadata(input_apk, work_dir):
    cache_file = os.path.join(work_dir, "apk_meta_cache.json")
    apk_stat = os.stat(input_apk)
    curr_mtime = apk_stat.st_mtime
    curr_size = apk_stat.st_size
    abs_apk_path = os.path.abspath(input_apk)

    if os.path.exists(cache_file):
        try:
            with open(cache_file, "r", encoding="utf-8") as cf:
                cache_data = json.load(cf)
            if (cache_data.get("apk_path") == abs_apk_path and 
                cache_data.get("mtime") == curr_mtime and 
                cache_data.get("size") == curr_size):
                log("INFO", "0. 命中官方原包指纹缓存，秒级复用...")
                orig_apk_md5 = cache_data.get("apk_md5", "")
                orig_sig_md5 = cache_data.get("sig_md5", "")
                log("OK", f"  -> 原版 APK MD5 : \033[36m{orig_apk_md5}\033[0m (缓存)")
                if orig_sig_md5:
                    log("OK", f"  -> 原版 签名 MD5: \033[36m{orig_sig_md5}\033[0m (缓存)")
                return orig_apk_md5, orig_sig_md5
        except Exception:
            pass

    log("INFO", "0. 正在提取官方原包特征指纹...")
    h = hashlib.md5()
    with open(input_apk, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    orig_apk_md5 = h.hexdigest().lower()
    log("OK", f"  -> 原版 APK MD5 : \033[36m{orig_apk_md5}\033[0m")

    orig_sig_md5 = ""
    try:
        cert_out = subprocess.getoutput(f"apksigner verify --print-certs {shlex.quote(input_apk)}")
        m = re.search(r"certificate MD5 digest:\s*([0-9a-fA-F]{32})", cert_out)
        if m:
            orig_sig_md5 = m.group(1).lower()
        else:
            kt_out = subprocess.getoutput(f"keytool -printcert -jarfile {shlex.quote(input_apk)}")
            m2 = re.search(r"MD5:\s*([0-9a-fA-F:]{47})", kt_out)
            if m2:
                orig_sig_md5 = m2.group(1).replace(":", "").lower()
    except Exception:
        pass

    if orig_sig_md5:
        log("OK", f"  -> 原版 签名 MD5: \033[36m{orig_sig_md5}\033[0m")

    try:
        with open(cache_file, "w", encoding="utf-8") as cf:
            json.dump({
                "apk_path": abs_apk_path,
                "mtime": curr_mtime,
                "size": curr_size,
                "apk_md5": orig_apk_md5,
                "sig_md5": orig_sig_md5
            }, cf)
    except Exception:
        pass

    return orig_apk_md5, orig_sig_md5

def compile_helper_dex_incremental(work_dir):
    """
    双分包解耦构建（Zero-Merge 架构）：
    1. 预编译基础依赖库 Dex (dx + protobuf)，持久缓存，0 秒秒级复用
    2. 纯业务代码独立编译为 patch_classes.dex，不带入巨型依赖进行二次 Merge，单文件修改 1~2 秒完成
    """
    src_dir = "./src"
    if not os.path.exists(src_dir):
        return None, None

    bin_dir = os.path.join(work_dir, "bin")
    dex_out = os.path.join(work_dir, "dex_out")
    target_patch_dex = os.path.join(dex_out, "patch_classes.dex")
    libs_cached_dex = os.path.join(work_dir, "libs_cached.dex")

    java_files = [os.path.join(r, f) for r, _, fs in os.walk(src_dir) for f in fs if f.endswith(".java")]
    if not java_files:
        return None, None

    os.makedirs(bin_dir, exist_ok=True)
    os.makedirs(dex_out, exist_ok=True)

    # 1. 预编译基础依赖库 Dex (dx + protobuf)，仅依赖更新时执行一次，后续 0 秒复用
    dep_mtime = max(
        os.path.getmtime(DX_JAR) if os.path.exists(DX_JAR) else 0,
        os.path.getmtime(PROTOBUF_JAR) if os.path.exists(PROTOBUF_JAR) else 0
    )
    if not os.path.exists(libs_cached_dex) or os.path.getmtime(libs_cached_dex) < dep_mtime:
        log("INFO", "检测到基础依赖库更新，正在预编译基础依赖 Dex (dx + protobuf)...")
        libs_temp_dir = os.path.join(work_dir, "libs_temp")
        os.makedirs(libs_temp_dir, exist_ok=True)
        run_cmd(f"d8 --min-api 26 --output {shlex.quote(libs_temp_dir)} {shlex.quote(DX_JAR)} {shlex.quote(PROTOBUF_JAR)}")
        temp_out = os.path.join(libs_temp_dir, "classes.dex")
        if os.path.exists(temp_out):
            shutil.move(temp_out, libs_cached_dex)
        shutil.rmtree(libs_temp_dir, ignore_errors=True)

    # 2. 检查纯业务代码是否需要增量重编
    latest_src_mtime = max(os.path.getmtime(f) for f in java_files)
    if not os.path.exists(target_patch_dex) or os.path.getmtime(target_patch_dex) < latest_src_mtime:
        modified_java = []
        for jf in java_files:
            rel = os.path.relpath(jf, src_dir)
            cf = os.path.join(bin_dir, os.path.splitext(rel)[0] + ".class")
            if not os.path.exists(cf) or os.path.getmtime(jf) > os.path.getmtime(cf):
                modified_java.append(jf)

        if modified_java:
            quoted_java = [shlex.quote(f) for f in modified_java]
            cp_dep = f"{shlex.quote(ANDROID_JAR)}:{shlex.quote(bin_dir)}:{shlex.quote(DX_JAR)}:{shlex.quote(PROTOBUF_JAR)}"
            run_cmd(f"javac -cp {cp_dep} -sourcepath {shlex.quote(src_dir)} -d {shlex.quote(bin_dir)} " + " ".join(quoted_java))

        patch_classes = [os.path.join(r, f) for r, _, fs in os.walk(bin_dir) for f in fs if f.endswith(".class")]
        if not patch_classes:
            log("ERR", "编译 helper java 失败！")
            return None, None

        # 核心：只对业务 class 极速转译 (彻底剥离巨型 dx.jar 的二次 Merge，1~2 秒完成)
        quoted_classes = [shlex.quote(f) for f in patch_classes]
        temp_patch_dir = os.path.join(work_dir, "patch_temp")
        os.makedirs(temp_patch_dir, exist_ok=True)
        run_cmd(f"d8 --min-api 26 --output {shlex.quote(temp_patch_dir)} " + " ".join(quoted_classes))
        t_out = os.path.join(temp_patch_dir, "classes.dex")
        if os.path.exists(t_out):
            shutil.move(t_out, target_patch_dex)
        shutil.rmtree(temp_patch_dir, ignore_errors=True)

    return (libs_cached_dex if os.path.exists(libs_cached_dex) else None,
            target_patch_dex if os.path.exists(target_patch_dex) else None)

def compile_bsh_to_asset_dex(work_dir):
    bsh_dex_dir = os.path.join(work_dir, "bsh_dex")
    target_bsh_dex = os.path.join(bsh_dex_dir, "classes.dex")
    final_bsh_dex = os.path.join(work_dir, "bsh.dex")

    dep_mtime = max(
        os.path.getmtime(BSH_JAR),
        os.path.getmtime(DX_JAR) if os.path.exists(DX_JAR) else 0,
        os.path.getmtime(PROTOBUF_JAR) if os.path.exists(PROTOBUF_JAR) else 0
    )
    if os.path.exists(final_bsh_dex) and os.path.getmtime(final_bsh_dex) >= dep_mtime:
        return final_bsh_dex

    os.makedirs(bsh_dex_dir, exist_ok=True)
    d8_inputs = f"{shlex.quote(BSH_JAR)} {shlex.quote(DX_JAR)} {shlex.quote(PROTOBUF_JAR)}"
    run_cmd(f"d8 --min-api 26 --output {shlex.quote(bsh_dex_dir)} {d8_inputs}")

    if os.path.exists(target_bsh_dex):
        shutil.copyfile(target_bsh_dex, final_bsh_dex)
        return final_bsh_dex
    return None

def build_dex_patcher_engine_incremental(work_dir):
    engine_src = "./DexPatcher.java"
    if not os.path.exists(engine_src):
        log("ERR", "未找到 DexPatcher.java！")
        sys.exit(1)

    engine_bin = os.path.join(work_dir, "patcher_bin")
    engine_class = os.path.join(engine_bin, "com/tencent/qqnt/patcher/DexPatcher.class")

    if os.path.exists(engine_class) and os.path.getmtime(engine_class) >= os.path.getmtime(engine_src):
        return engine_bin

    os.makedirs(engine_bin, exist_ok=True)
    cp = f"{shlex.quote(DEXLIB2_JAR)}:{shlex.quote(SMALI_JAR)}:{shlex.quote(BAKSMALI_JAR)}"
    run_cmd(f"javac -cp {cp} -d {shlex.quote(engine_bin)} {shlex.quote(engine_src)}")
    return engine_bin

def get_defined_classes_in_dex(dex_bytes):
    if len(dex_bytes) < 0x70 or dex_bytes[:4] != b'dex\n':
        return set()
    try:
        string_ids_off = struct.unpack_from('<I', dex_bytes, 0x3C)[0]
        type_ids_off = struct.unpack_from('<I', dex_bytes, 0x44)[0]
        class_defs_size, class_defs_off = struct.unpack_from('<II', dex_bytes, 0x60)

        classes = set()
        for i in range(class_defs_size):
            class_idx = struct.unpack_from('<I', dex_bytes, class_defs_off + i * 32)[0]
            desc_idx = struct.unpack_from('<I', dex_bytes, type_ids_off + class_idx * 4)[0]
            str_off = struct.unpack_from('<I', dex_bytes, string_ids_off + desc_idx * 4)[0]

            p = str_off
            while dex_bytes[p] & 0x80: p += 1
            p += 1

            end = dex_bytes.find(b'\x00', p)
            if end != -1:
                classes.add(dex_bytes[p:end].decode('utf-8', errors='ignore'))
        return classes
    except Exception:
        return set()

def dump_batch_tasks(dex_tasks, batch_file):
    with open(batch_file, "w", encoding="utf-8") as f:
        for dex_in, dex_out, r_list in dex_tasks:
            f.write("===DEX_TASK_SPLIT===\n")
            f.write(f"DEX_IN={dex_in}\n")
            f.write(f"DEX_OUT={dex_out}\n")
            for r in r_list:
                f.write("===RULE_SPLIT===\n")
                f.write(f"NAME={r.get('name', '未命名规则')}\n")
                f.write(f"TARGET_CLASS={r['target_class']}\n")
                f.write(f"TARGET_METHOD={r['target_method']}\n")
                f.write(f"TYPE={r['type']}\n")
                if "regex" in r:
                    f.write(f"REGEX={r['regex']}\n")
                f.write("---SMALI_START---\n")
                f.write(r['smali'].strip() + "\n")
                f.write("---SMALI_END---\n")

def print_patch_details(dex_name, rule_list):
    print(f"\n\033[1;36m{'='*25} [{dex_name}] 补丁明细 ({len(rule_list)} 项) {'='*25}\033[0m")
    for idx, r in enumerate(rule_list, 1):
        name = r.get("name", "未命名规则")
        target_cls = r.get("target_class", "")
        target_m = r.get("target_method", "")
        p_type = r.get("type", "")

        print(f"\033[1;33m[{idx:02d}] {name}\033[0m")
        print(f"  ├─ 目标类: \033[32m{target_cls}\033[0m")
        print(f"  ├─ 目标方法: \033[35m{target_m}\033[0m")
        print(f"  ├─ 修补类型: \033[36m{p_type}\033[0m")

        if p_type == "REGEX_REPLACE" and "regex" in r:
            print(f"  ├─ 匹配正则: \033[90m{r['regex']}\033[0m")
            print("  └─ 替换内容:")
        else:
            print("  └─ 注入 Smali 代码:")

        for sl in r.get("smali", "").strip().split("\n"):
            print(f"     \033[90m│\033[0m {sl}")
        print()

def compute_dex_patch_key(raw_dex_bytes, rules_list):
    h = hashlib.sha256()
    h.update(hashlib.md5(raw_dex_bytes).hexdigest().encode('utf-8'))
    rules_dump = json.dumps(rules_list, sort_keys=True, ensure_ascii=False)
    h.update(rules_dump.encode('utf-8'))
    return h.hexdigest()[:16]

def get_file_mtime_safe(file_path):
    try:
        return os.path.getmtime(file_path)
    except Exception:
        return 0

def resolve_dynamic_rules_with_cache(dex_data_dict, orig_apk_md5, orig_sig_md5, work_dir):
    """
    针对阶段 2 的细粒度规则推导缓存引擎：
    各个规则模块独立判断文件修改时间，没改的模块 0 秒秒读缓存，改动的模块才重新扫描。
    """
    cache_path = os.path.join(work_dir, "rule_discovery_cache.json")
    cache_data = {}
    if os.path.exists(cache_path):
        try:
            with open(cache_path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
                if loaded.get("apk_md5") == orig_apk_md5:
                    cache_data = loaded.get("modules", {})
        except Exception:
            cache_data = {}

    rules_dir = os.path.abspath("./rules")
    new_cache_modules = dict(cache_data)
    all_rules = list(rules.RULES)

    # 1. 安全穿透规则 (security_rules.py)
    sec_py = os.path.join(rules_dir, "security_rules.py")
    sec_mtime = get_file_mtime_safe(sec_py)
    cached_sec = cache_data.get("security")
    if cached_sec and cached_sec.get("mtime") == sec_mtime and "rules" in cached_sec:
        dynamic_sec_rules = cached_sec["rules"]
        log("INFO", f"规则推导缓存命中: [安全穿透规则] ({len(dynamic_sec_rules)} 项)")
    else:
        dynamic_sec_rules = rules.get_dynamic_security_rules(dex_data_dict, orig_apk_md5, orig_sig_md5)
        new_cache_modules["security"] = {"mtime": sec_mtime, "rules": dynamic_sec_rules}
        for r in dynamic_sec_rules:
            log("OK", f"-> 安全穿透规则生成: [{r['name']}]")
    all_rules.extend(dynamic_sec_rules)

    # 2. 设置入口匹配 (setting_rules.py)
    set_py = os.path.join(rules_dir, "setting_rules.py")
    set_mtime = get_file_mtime_safe(set_py)
    cached_set = cache_data.get("setting")
    if cached_set and cached_set.get("mtime") == set_mtime and "rule" in cached_set:
        dyn_setting_rule = cached_set["rule"]
        if dyn_setting_rule:
            log("INFO", f"规则推导缓存命中: [{dyn_setting_rule['name']}]")
            all_rules.append(dyn_setting_rule)
    else:
        dyn_setting_rule = rules.get_dynamic_setting_rule_fast(dex_data_dict)
        new_cache_modules["setting"] = {"mtime": set_mtime, "rule": dyn_setting_rule}
        if dyn_setting_rule:
            log("OK", f"-> 设置入口匹配: [{dyn_setting_rule['name']}]")
            all_rules.append(dyn_setting_rule)

    # 3. 群文件下载次数 (group_file_rules.py)
    gf_py = os.path.join(rules_dir, "group_file_rules.py")
    gf_mtime = get_file_mtime_safe(gf_py)
    cached_gf = cache_data.get("group_file")
    if cached_gf and cached_gf.get("mtime") == gf_mtime and "rules" in cached_gf:
        dyn_file_rules = cached_gf["rules"]
        log("INFO", f"规则推导缓存命中: [群文件下载次数] ({len(dyn_file_rules)} 项)")
    else:
        dyn_file_rules = rules.get_dynamic_group_file_rules(dex_data_dict)
        new_cache_modules["group_file"] = {"mtime": gf_mtime, "rules": dyn_file_rules}
        for r in dyn_file_rules:
            log("OK", f"-> 群文件下载次数动态匹配: [{r['name']}]")
    all_rules.extend(dyn_file_rules)

    # 4. 平板模式 (tablet_rules.py)
    tab_py = os.path.join(rules_dir, "tablet_rules.py")
    tab_mtime = get_file_mtime_safe(tab_py)
    cached_tab = cache_data.get("tablet")
    if cached_tab and cached_tab.get("mtime") == tab_mtime and "rule" in cached_tab:
        dyn_tablet_rule = cached_tab["rule"]
        if dyn_tablet_rule:
            log("INFO", f"规则推导缓存命中: [{dyn_tablet_rule['name']}]")
            all_rules.append(dyn_tablet_rule)
    else:
        dyn_tablet_rule = rules.get_dynamic_tablet_rule_fast(dex_data_dict)
        new_cache_modules["tablet"] = {"mtime": tab_mtime, "rule": dyn_tablet_rule}
        if dyn_tablet_rule:
            log("OK", f"-> 平板模式动态匹配: [{dyn_tablet_rule['name']}]")
            all_rules.append(dyn_tablet_rule)

    # 5. 群待办通知 (troop_todo_rules.py)
    todo_py = os.path.join(rules_dir, "troop_todo_rules.py")
    todo_mtime = get_file_mtime_safe(todo_py)
    cached_todo = cache_data.get("troop_todo")
    if cached_todo and cached_todo.get("mtime") == todo_mtime and "rule" in cached_todo:
        dyn_todo_rule = cached_todo["rule"]
        if dyn_todo_rule:
            log("INFO", f"规则推导缓存命中: [{dyn_todo_rule['name']}]")
            all_rules.append(dyn_todo_rule)
    else:
        dyn_todo_rule = rules.get_dynamic_troop_todo_rule(dex_data_dict)
        new_cache_modules["troop_todo"] = {"mtime": todo_mtime, "rule": dyn_todo_rule}
        if dyn_todo_rule:
            log("OK", f"-> 群待办通知动态匹配: [{dyn_todo_rule['name']}]")
            all_rules.append(dyn_todo_rule)
            
    # 6. 伪装处于非多窗口模式 (multi_window_rules.py)
    mw_py = os.path.join(rules_dir, "multi_window_rules.py")
    mw_mtime = get_file_mtime_safe(mw_py)
    cached_mw = cache_data.get("multi_window")
    if cached_mw and cached_mw.get("mtime") == mw_mtime and "rules" in cached_mw:
        dyn_mw_rules = cached_mw["rules"]
        log("INFO", f"规则推导缓存命中: [伪装非多窗口模式] ({len(dyn_mw_rules)} 项)")
    else:
        dyn_mw_rules = rules.get_dynamic_multi_window_rules(dex_data_dict)
        new_cache_modules["multi_window"] = {"mtime": mw_mtime, "rules": dyn_mw_rules}
        for r in dyn_mw_rules:
            log("OK", f"-> 伪装非多窗口模式动态匹配: [{r['name']}]")
    all_rules.extend(dyn_mw_rules)

    try:
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump({"apk_md5": orig_apk_md5, "modules": new_cache_modules}, f, ensure_ascii=False)
    except Exception:
        pass

    return all_rules

def main():
    t_start = time.time()
    ensure_smali_jars()

    args = sys.argv[1:]
    no_sign = False
    skip_dex_patch = False
    skipped_keywords = []
    only_keywords = []

    if "--no-sign" in args:
        no_sign = True; args.remove("--no-sign")
    if "-n" in args:
        no_sign = True; args.remove("-n")
    if "--skip-dex-patch" in args:
        skip_dex_patch = True; args.remove("--skip-dex-patch")

    while "--skip" in args:
        idx = args.index("--skip")
        if idx + 1 < len(args):
            skipped_keywords.append(args[idx + 1])
            del args[idx:idx + 2]
        else:
            args.remove("--skip")

    while "--only" in args:
        idx = args.index("--only")
        if idx + 1 < len(args):
            only_keywords.append(args[idx + 1])
            del args[idx:idx + 2]
        else:
            args.remove("--only")

    input_apk = args[0] if len(args) > 0 else "QQ.apk"
    output_apk = args[1] if len(args) > 1 else "QQ_Patched.apk"

    if not os.path.exists(input_apk):
        log("ERR", f"未找到输入 APK 文件: {input_apk}")
        sys.exit(1)

    work_dir = "./build_cache"
    dex_cache_dir = os.path.join(work_dir, "dex_cache")
    so_cache_dir = os.path.join(work_dir, "so_cache")
    os.makedirs(work_dir, exist_ok=True)
    os.makedirs(dex_cache_dir, exist_ok=True)
    os.makedirs(so_cache_dir, exist_ok=True)

    orig_apk_md5, orig_sig_md5 = extract_original_apk_metadata(input_apk, work_dir)

    t0 = time.time()
    log("INFO", "1. 正在准备构建环境与扩展 Dex...")
    engine_bin = build_dex_patcher_engine_incremental(work_dir)
    libs_dex_path, patch_dex_path = compile_helper_dex_incremental(work_dir)
    bsh_standalone_dex = compile_bsh_to_asset_dex(work_dir)
    preset_plugins_zip = pack_preset_plugins_if_exist(work_dir)

    if not patch_dex_path:
        log("ERR", "扩展 Dex 编译失败！")
        sys.exit(1)
    if not no_sign:
        ensure_fixed_keystore()
    t_phase1 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 1 耗时: {t_phase1}s")

    t0 = time.time()
    log("INFO", "2. 正在提取 Dex 并动态推导安全风控穿透规则...")
    dex_data_dict = {}
    with zipfile.ZipFile(input_apk, 'r') as zf:
        for name in zf.namelist():
            if re.match(r'^classes\d*\.dex$', name):
                dex_data_dict[name] = zf.read(name)

    def dex_index(name):
        if name == "classes.dex": return 1
        m = re.match(r'classes(\d+)\.dex', name)
        return int(m.group(1)) if m else 0

    dex_list = sorted(dex_data_dict.keys(), key=dex_index)
    max_idx = dex_index(dex_list[-1])
    # 物理双分包：依赖库作为 classes{max_idx+1}.dex，业务代码作为 classes{max_idx+2}.dex
    libs_dex_name = f"classes{max_idx + 1}.dex"
    patch_dex_name = f"classes{max_idx + 2}.dex"

    all_rules = resolve_dynamic_rules_with_cache(
        dex_data_dict,
        orig_apk_md5,
        orig_sig_md5,
        work_dir
    )

    filtered_rules = []
    for r in all_rules:
        r_name = r.get("name", "")
        if only_keywords and not any(k in r_name for k in only_keywords):
            continue
        if skipped_keywords and any(k in r_name for k in skipped_keywords):
            log("WARN", f"-> 调试跳过规则: [{r_name}]")
            continue
        filtered_rules.append(r)
    all_rules = filtered_rules

    dex_classes_cache_file = os.path.join(work_dir, "dex_classes_map.json")
    dex_classes_map = {}
    if os.path.exists(dex_classes_cache_file):
        try:
            with open(dex_classes_cache_file, "r", encoding="utf-8") as f:
                c_data = json.load(f)
                if c_data.get("apk_md5") == orig_apk_md5:
                    dex_classes_map = {k: set(v) for k, v in c_data.get("map", {}).items()}
        except Exception:
            dex_classes_map = {}

    if not dex_classes_map:
        for dex_name in dex_list:
            dex_classes_map[dex_name] = get_defined_classes_in_dex(dex_data_dict[dex_name])
        try:
            with open(dex_classes_cache_file, "w", encoding="utf-8") as f:
                json.dump({
                    "apk_md5": orig_apk_md5,
                    "map": {k: list(v) for k, v in dex_classes_map.items()}
                }, f)
        except Exception:
            pass

    dex_to_rules = {}
    matched_rule_names = set()

    for dex_name in dex_list:
        defined_classes = dex_classes_map[dex_name]
        for rule in all_rules:
            if rule["target_class"] in defined_classes:
                dex_to_rules.setdefault(dex_name, []).append(rule)
                matched_rule_names.add(rule["name"])

    for rule in all_rules:
        if rule["name"] not in matched_rule_names:
            log("WARN", f"-> 规则未命中当前包: [{rule['name']}]")

    t_phase2 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 2 耗时: {t_phase2}s")

    t0 = time.time()
    log("INFO", f"3. 正在执行 Dex 字节码内存 AST 重构 ({len(dex_to_rules)} 个分包)...")
    dex_tasks = []
    modified_dex_files = []
    cached_hit_count = 0

    if skip_dex_patch:
        log("WARN", "已开启 --skip-dex-patch: 跳过所有宿主 Dex 重构！")
    else:
        for dex_name, r_list in dex_to_rules.items():
            raw_dex_bytes = dex_data_dict[dex_name]
            cache_key = compute_dex_patch_key(raw_dex_bytes, r_list)
            cached_dex_file = os.path.join(dex_cache_dir, f"{dex_name}_{cache_key}.dex")
            target_out_dex = os.path.join(work_dir, f"patched_{dex_name}")

            if os.path.exists(cached_dex_file) and os.path.getsize(cached_dex_file) > 0:
                shutil.copyfile(cached_dex_file, target_out_dex)
                modified_dex_files.append((target_out_dex, dex_name))
                cached_hit_count += 1
                continue

            dex_raw_path = os.path.join(work_dir, dex_name)
            with open(dex_raw_path, "wb") as f:
                f.write(raw_dex_bytes)

            dex_tasks.append((dex_raw_path, target_out_dex, r_list, cached_dex_file))
            modified_dex_files.append((target_out_dex, dex_name))

            log("INFO", f"-> 分包 [{dex_name}] 规则或源码变动，需重构 ({len(r_list)} 条修改规则)")
            print_patch_details(dex_name, r_list)

        if cached_hit_count > 0:
            log("OK", f"分包缓存命中: {cached_hit_count} 个分包未变动，直接复用")

        if dex_tasks:
            batch_tasks_for_engine = [(t[0], t[1], t[2]) for t in dex_tasks]
            batch_cfg_path = os.path.join(work_dir, "batch_tasks.txt")
            dump_batch_tasks(batch_tasks_for_engine, batch_cfg_path)

            if engine_bin:
                cp = f"{shlex.quote(engine_bin)}:{shlex.quote(GUAVA_JAR)}:{shlex.quote(DEXLIB2_JAR)}:{shlex.quote(SMALI_JAR)}:{shlex.quote(BAKSMALI_JAR)}"
                cmd = f"java {JAVA_OPTS} -cp {cp} com.tencent.qqnt.patcher.DexPatcher {shlex.quote(batch_cfg_path)}"
                run_cmd_stream(cmd)

            for _, target_out_dex, _, cached_dex_file in dex_tasks:
                if os.path.exists(target_out_dex) and os.path.getsize(target_out_dex) > 0:
                    shutil.copyfile(target_out_dex, cached_dex_file)
        else:
            log("OK", "全部分包均命中缓存，跳过 Dex 编译流程")

    cached_so_file = os.path.join(so_cache_dir, f"libcodecwrapperV2_{orig_apk_md5}.so")
    patched_so_files = []
    if os.path.exists(cached_so_file) and os.path.getsize(cached_so_file) > 0:
        target_so_entry = "lib/arm64-v8a/libcodecwrapperV2.so"
        patched_so_files.append((cached_so_file, target_so_entry))
        log("OK", "-> Native SO 命中缓存，秒级复用")
    else:
        log("INFO", "3.1 正在扫描底层 Native SO 安全探针...")
        new_so_files = native_patcher.patch_native_so(input_apk, work_dir, log_func=log)
        for local_so, in_zip_so in new_so_files:
            if "libcodecwrapperV2.so" in in_zip_so:
                shutil.copyfile(local_so, cached_so_file)
            patched_so_files.append((local_so, in_zip_so))

    del dex_data_dict
    t_phase3 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 3 耗时: {t_phase3}s")

    t0 = time.time()
    log("INFO", "4. 正在打包 APK (复制原包并注入修改文件)...")
    if shutil.which("cp"):
        run_cmd(f"cp -f {shlex.quote(input_apk)} {shlex.quote(output_apk)}")
    else:
        shutil.copyfile(input_apk, output_apk)

    inject_dir = os.path.join(work_dir, "inject")
    if os.path.exists(inject_dir):
        shutil.rmtree(inject_dir, ignore_errors=True)
    os.makedirs(inject_dir, exist_ok=True)

    zip_args = []
    for local_path, in_zip_name in modified_dex_files:
        if os.path.exists(local_path):
            target_in_dir = os.path.join(inject_dir, in_zip_name)
            shutil.copyfile(local_path, target_in_dir)
            zip_args.append(shlex.quote(in_zip_name))

    for local_so, in_zip_so in patched_so_files:
        if os.path.exists(local_so):
            target_so_dir = os.path.join(inject_dir, os.path.dirname(in_zip_so))
            os.makedirs(target_so_dir, exist_ok=True)
            shutil.copyfile(local_so, os.path.join(inject_dir, in_zip_so))
            zip_args.append(shlex.quote(in_zip_so))

    # 4.1 注入基础依赖库 Dex (classes{max_idx+1}.dex)
    if libs_dex_path and os.path.exists(libs_dex_path):
        target_libs = os.path.join(inject_dir, libs_dex_name)
        shutil.copyfile(libs_dex_path, target_libs)
        zip_args.append(shlex.quote(libs_dex_name))

    # 4.2 注入业务代码 Dex (classes{max_idx+2}.dex)
    if patch_dex_path and os.path.exists(patch_dex_path):
        target_patch = os.path.join(inject_dir, patch_dex_name)
        shutil.copyfile(patch_dex_path, target_patch)
        zip_args.append(shlex.quote(patch_dex_name))

    if bsh_standalone_dex and os.path.exists(bsh_standalone_dex):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        shutil.copyfile(bsh_standalone_dex, os.path.join(target_assets_dir, "bsh.dex"))
        zip_args.append(shlex.quote("assets/bsh.dex"))

    if preset_plugins_zip and os.path.exists(preset_plugins_zip):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        shutil.copyfile(preset_plugins_zip, os.path.join(target_assets_dir, "preset_plugins.zip"))
        zip_args.append(shlex.quote("assets/preset_plugins.zip"))

    assets_src_dir = "assets"
    if os.path.exists(assets_src_dir):
        target_assets_dir = os.path.join(inject_dir, "assets")
        os.makedirs(target_assets_dir, exist_ok=True)
        for f in os.listdir(assets_src_dir):
            src_f = os.path.join(assets_src_dir, f)
            if os.path.isfile(src_f):
                shutil.copyfile(src_f, os.path.join(target_assets_dir, f))
                zip_args.append(shlex.quote(f"assets/{f}"))

    abs_output_apk = os.path.abspath(output_apk)
    if zip_args:
        run_cmd(f"cd {shlex.quote(inject_dir)} && zip -q -1 -u {shlex.quote(abs_output_apk)} " + " ".join(zip_args))

    if no_sign:
        subprocess.run(f"zip -q -d {shlex.quote(abs_output_apk)} 'META-INF/*' 2>/dev/null", shell=True)

    zipalign_bin = shutil.which("zipalign")
    if zipalign_bin:
        aligned_apk = os.path.join(work_dir, "aligned_temp.apk")
        run_cmd(f"{shlex.quote(zipalign_bin)} -p -f 4 {shlex.quote(abs_output_apk)} {shlex.quote(aligned_apk)}")
        if os.path.exists(aligned_apk) and os.path.getsize(aligned_apk) > 0:
            shutil.move(aligned_apk, abs_output_apk)

    t_phase4 = round(time.time() - t0, 2)
    log("TIME", f"  -> 阶段 4 耗时: {t_phase4}s")

    t_phase5 = 0.0
    if not no_sign:
        t0 = time.time()
        log("INFO", "5. 正在对 APK 进行固定证书签名...")
        if not os.path.exists(FIXED_KEYSTORE):
            ensure_fixed_keystore()

        run_cmd(f"apksigner sign --ks {shlex.quote(FIXED_KEYSTORE)} --ks-pass pass:android --key-pass pass:android {shlex.quote(output_apk)}")
        t_phase5 = round(time.time() - t0, 2)
        log("TIME", f"  -> 阶段 5 耗时: {t_phase5}s")
    else:
        log("INFO", "5. 跳过 APK 签名 (--no-sign)")

    keep_list = {
        "patcher_bin", 
        "dex_out", 
        "bin", 
        "bsh.dex", 
        "bsh_dex", 
        "libs_cached.dex", 
        "preset_plugins.zip",
        "apk_meta_cache.json",
        "dex_cache",
        "so_cache",
        "rule_discovery_cache.json",
        "dex_classes_map.json"
    }
    for f in os.listdir(work_dir):
        if f not in keep_list:
            p = os.path.join(work_dir, f)
            if os.path.isdir(p): shutil.rmtree(p, ignore_errors=True)
            else: os.remove(p)

    t_cost = round(time.time() - t_start, 2)
    log("OK", f"构建完成，耗时: {t_cost}s, 输出: {output_apk}")

if __name__ == "__main__":
    main()