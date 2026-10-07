# -*- coding: utf-8 -*-
import os
import re
import json
import struct
import hashlib
import zipfile
import subprocess
import shlex
from rules.engine import discover_rule_plugins
from .features import BUS_DEPENDENCIES

def get_file_mtime_safe(file_path):
    try:
        return os.path.getmtime(file_path)
    except Exception:
        return 0

def extract_apk_metadata(ctx):
    cache_file = os.path.join(ctx.work_dir, "apk_meta_cache.json")
    apk_stat = os.stat(ctx.input_apk)
    
    if os.path.exists(cache_file):
        try:
            with open(cache_file, "r", encoding="utf-8") as cf:
                d = json.load(cf)
            if d.get("apk_path") == ctx.input_apk and d.get("mtime") == apk_stat.st_mtime and d.get("size") == apk_stat.st_size:
                ctx.orig_apk_md5 = d.get("apk_md5", "")
                ctx.orig_sig_md5 = d.get("sig_md5", "")
                ctx.log("OK", f"  -> 原版 APK MD5 : \033[36m{ctx.orig_apk_md5}\033[0m (缓存)")
                ctx.log("OK", f"  -> 原版 签名 MD5: \033[36m{ctx.orig_sig_md5}\033[0m (缓存)")
                return
        except Exception:
            pass

    ctx.log("INFO", "0. 正在提取官方原包特征指纹...")
    h = hashlib.md5()
    with open(ctx.input_apk, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    ctx.orig_apk_md5 = h.hexdigest().lower()
    ctx.log("OK", f"  -> 原版 APK MD5 : \033[36m{ctx.orig_apk_md5}\033[0m")

    try:
        cert_out = subprocess.getoutput(f"apksigner verify --print-certs {shlex.quote(ctx.input_apk)}")
        m = re.search(r"certificate MD5 digest:\s*([0-9a-fA-F]{32})", cert_out)
        if m:
            ctx.orig_sig_md5 = m.group(1).lower()
    except Exception:
        pass

    if ctx.orig_sig_md5:
        ctx.log("OK", f"  -> 原版 签名 MD5: \033[36m{ctx.orig_sig_md5}\033[0m")

    try:
        with open(cache_file, "w", encoding="utf-8") as cf:
            json.dump({
                "apk_path": ctx.input_apk,
                "mtime": apk_stat.st_mtime,
                "size": apk_stat.st_size,
                "apk_md5": ctx.orig_apk_md5,
                "sig_md5": ctx.orig_sig_md5
            }, cf)
    except Exception:
        pass

def get_defined_classes(dex_bytes):
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
            while dex_bytes[p] & 0x80:
                p += 1
            p += 1
            end = dex_bytes.find(b'\x00', p)
            if end != -1:
                classes.add(dex_bytes[p:end].decode('utf-8', errors='ignore'))
        return classes
    except Exception:
        return set()

def is_rule_active(rule: dict, plugin_id: str, active_features: set) -> bool:
    rule_feature = rule.get("feature")
    if rule_feature:
        return rule_feature in active_features

    bus_name = rule.get("bus")
    if bus_name:
        dependents = BUS_DEPENDENCIES.get(bus_name, set())
        return bool(dependents & active_features)

    id_map = {
        "killer": "killer",
        "security": "security",
        "tablet": "tablet",
        "multi_window": "multi_window",
        "group_file": "group_file",
        "troop_todo": "troop_todo",
        "setting": "setting",
        "browser_mitigation": "browser",
        "tg_stickers": "tg_stickers",
    }
    feature_id = id_map.get(plugin_id, plugin_id)
    return feature_id in active_features

def resolve_dynamic_rules_with_cache(ctx):
    cache_path = os.path.join(ctx.work_dir, "rule_discovery_cache.json")
    provider_name = ctx.provider.name
    cache_data = {}
    full_providers_cache = {}

    if os.path.exists(cache_path):
        try:
            with open(cache_path, "r", encoding="utf-8") as f:
                loaded = json.load(f)
                if loaded.get("apk_md5") == ctx.orig_apk_md5:
                    if "providers" in loaded and isinstance(loaded["providers"], dict):
                        full_providers_cache = loaded["providers"]
                        cache_data = full_providers_cache.get(provider_name, {})
                    elif loaded.get("provider") == provider_name:
                        cache_data = loaded.get("modules", {})
        except Exception:
            pass

    rules_dir = os.path.join(ctx.root_dir, "rules")
    plugins = discover_rule_plugins(rules_dir)
    
    meta = {
        "orig_apk_md5": ctx.orig_apk_md5,
        "orig_sig_md5": ctx.orig_sig_md5,
        "no_killer": ctx.no_killer,
        "provider": provider_name,
    }

    all_discovered = []
    new_cache = dict(cache_data)

    for p in plugins:
        if not p.enabled:
            continue
        
        mtime = get_file_mtime_safe(p.file_path)
        cached_entry = cache_data.get(p.plugin_id)
        
        if cached_entry and cached_entry.get("mtime") == mtime and "rules" in cached_entry:
            mod_rules = cached_entry["rules"]
        else:
            try:
                mod_rules = p.resolve(ctx.dex_data_dict, meta)
            except Exception as e:
                ctx.log("ERR", f"规则插件 [{p.name}] 执行异常: {e}")
                mod_rules = []
            new_cache[p.plugin_id] = {"mtime": mtime, "rules": mod_rules}

        for r in mod_rules:
            r["_plugin_id"] = p.plugin_id
        all_discovered.extend(mod_rules)

    full_providers_cache[provider_name] = new_cache
    try:
        with open(cache_path, "w", encoding="utf-8") as f:
            json.dump({
                "apk_md5": ctx.orig_apk_md5,
                "provider": provider_name,
                "providers": full_providers_cache,
                "modules": new_cache
            }, f, ensure_ascii=False)
    except Exception:
        pass

    # 依照 Feature 决策树精准过滤
    active_rules = []
    for r in all_discovered:
        p_id = r.get("_plugin_id", "")
        if is_rule_active(r, p_id, ctx.active_features):
            active_rules.append(r)
            ctx.log("OK", f"-> 规则生效: [{r.get('name', '未命名')}]")
        else:
            ctx.log("WARN", f"-> [测试跳过] 未激活特性规则: [{r.get('name', '未命名')}]")

    return active_rules

def run_stage2(ctx):
    extract_apk_metadata(ctx)
    ctx.log("INFO", "2. 正在提取 Dex 并动态推导安全风控穿透规则...")
    
    with zipfile.ZipFile(ctx.input_apk, 'r') as zf:
        for name in zf.namelist():
            if re.match(r'^classes\d*\.dex$', name):
                ctx.dex_data_dict[name] = zf.read(name)

    def dex_index(name):
        if name == "classes.dex":
            return 1
        m = re.match(r'classes(\d+)\.dex', name)
        return int(m.group(1)) if m else 0

    dex_list = sorted(ctx.dex_data_dict.keys(), key=dex_index)
    ctx.max_dex_idx = dex_index(dex_list[-1])

    ctx.all_rules = resolve_dynamic_rules_with_cache(ctx)

    dex_classes_cache_file = os.path.join(ctx.work_dir, "dex_classes_map.json")
    if os.path.exists(dex_classes_cache_file):
        try:
            with open(dex_classes_cache_file, "r", encoding="utf-8") as f:
                c_data = json.load(f)
                if c_data.get("apk_md5") == ctx.orig_apk_md5:
                    ctx.dex_classes_map = {k: set(v) for k, v in c_data.get("map", {}).items()}
        except Exception:
            pass

    if not ctx.dex_classes_map:
        for dex_name in dex_list:
            ctx.dex_classes_map[dex_name] = get_defined_classes(ctx.dex_data_dict[dex_name])
        try:
            with open(dex_classes_cache_file, "w", encoding="utf-8") as f:
                json.dump({"apk_md5": ctx.orig_apk_md5, "map": {k: list(v) for k, v in ctx.dex_classes_map.items()}}, f)
        except Exception:
            pass

    ctx.dex_to_rules = {}
    matched = set()
    for dex_name in dex_list:
        classes = ctx.dex_classes_map[dex_name]
        for r in ctx.all_rules:
            if r["target_class"] in classes:
                ctx.dex_to_rules.setdefault(dex_name, []).append(r)
                matched.add(r["name"])

    for r in ctx.all_rules:
        if r["name"] not in matched:
            ctx.log("WARN", f"-> 规则未命中当前包: [{r['name']}]")
