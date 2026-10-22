# -*- coding: utf-8 -*-
import os
import re
import json
import struct
import base64
import random
import shlex
import hashlib
import zipfile
import subprocess

from rules.engine import discover_rule_plugins
from .features import BUS_DEPENDENCIES
from . import killer_config as KC
from .killer_payload import build_killer_app_dex, fqcn_to_dex_desc
from .context import Colors


def get_file_mtime_safe(file_path):
    try:
        return os.path.getmtime(file_path)
    except Exception:
        return 0


def _extract_package_name(ctx, apk):
    aapt = ctx.find_tool("aapt")
    try:
        out = subprocess.getoutput(f"{aapt} dump badging {shlex.quote(apk)} 2>/dev/null")
        m = re.search(r"package: name='([^']+)'", out)
        if m:
            return m.group(1)
    except Exception:
        pass
    return ""


def _extract_cert_der_b64(apk):
    try:
        out = subprocess.getoutput(f"keytool -printcert -jarfile {shlex.quote(apk)} -rfc 2>/dev/null")
        m = re.search(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", out, re.DOTALL)
        if m:
            return re.sub(r"\s+", "", m.group(1))
    except Exception:
        pass
    return ""


def _extract_p7_b64(apk):
    try:
        with zipfile.ZipFile(apk, "r") as zf:
            for n in zf.namelist():
                if re.match(r"META-INF/.*\.(RSA|DSA|EC)$", n):
                    return base64.b64encode(zf.read(n)).decode()
    except Exception:
        pass
    return ""


def _extract_signature_b64(ctx, apk):
    if KC.SIGNATURE_MODE == "cert_der":
        s = _extract_cert_der_b64(apk)
        if s:
            ctx.log("OK", "签名来源: X.509 DER (cert_der)", indent=1)
            return s
        ctx.log("WARN", "cert_der 提取失败，降级为 p7_raw", indent=1)
        s = _extract_p7_b64(apk)
        if s:
            ctx.log("OK", "签名来源: META-INF/*.RSA (p7_raw)", indent=1)
            return s
    else:
        s = _extract_p7_b64(apk)
        if s:
            ctx.log("OK", "签名来源: META-INF/*.RSA (p7_raw)", indent=1)
            return s
        ctx.log("WARN", "p7_raw 提取失败，尝试 cert_der", indent=1)
        s = _extract_cert_der_b64(apk)
        if s:
            ctx.log("OK", "签名来源: X.509 DER (cert_der)", indent=1)
            return s
    return ""


def extract_apk_metadata(ctx):
    cache_file = os.path.join(ctx.work_dir, "apk_meta_cache.json")
    apk_stat = os.stat(ctx.input_apk)

    need_killer_meta = ("killer" in ctx.active_features)

    if os.path.exists(cache_file):
        try:
            with open(cache_file, "r", encoding="utf-8") as cf:
                d = json.load(cf)
            if (d.get("apk_path") == ctx.input_apk
                    and d.get("mtime") == apk_stat.st_mtime
                    and d.get("size") == apk_stat.st_size):
                ctx.orig_apk_md5 = d.get("apk_md5", "")
                ctx.orig_sig_md5 = d.get("sig_md5", "")
                ctx.target_pkg = d.get("target_pkg", "")
                ctx.signature_b64 = d.get("signature_b64", "")

                if (not need_killer_meta) or (ctx.target_pkg and ctx.signature_b64):
                    ctx.log("CACHE", f"原包指纹缓存命中 (MD5: {Colors.CYAN}{ctx.orig_apk_md5}{Colors.RESET})")
                    return
        except Exception:
            pass

    ctx.log("INFO", "正在提取官方原包特征指纹...")
    h = hashlib.md5()
    with open(ctx.input_apk, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    ctx.orig_apk_md5 = h.hexdigest().lower()
    ctx.log("OK", f"原包 APK MD5 : {Colors.CYAN}{ctx.orig_apk_md5}{Colors.RESET}")

    try:
        cert_out = subprocess.getoutput(f"apksigner verify --print-certs {shlex.quote(ctx.input_apk)}")
        m = re.search(r"certificate MD5 digest:\s*([0-9a-fA-F]{32})", cert_out)
        if m:
            ctx.orig_sig_md5 = m.group(1).lower()
    except Exception:
        pass

    if ctx.orig_sig_md5:
        ctx.log("OK", f"官方证书 MD5 : {Colors.CYAN}{ctx.orig_sig_md5}{Colors.RESET}")

    if need_killer_meta:
        ctx.target_pkg = _extract_package_name(ctx, ctx.input_apk)
        if ctx.target_pkg:
            ctx.log("OK", f"识别目标包名 : {Colors.CYAN}{ctx.target_pkg}{Colors.RESET}")
        ctx.signature_b64 = _extract_signature_b64(ctx, ctx.input_apk)

    try:
        with open(cache_file, "w", encoding="utf-8") as cf:
            json.dump({
                "apk_path": ctx.input_apk,
                "mtime": apk_stat.st_mtime,
                "size": apk_stat.st_size,
                "apk_md5": ctx.orig_apk_md5,
                "sig_md5": ctx.orig_sig_md5,
                "target_pkg": ctx.target_pkg,
                "signature_b64": ctx.signature_b64,
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
        "tinker": "tinker",
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
        "killer_app_desc": fqcn_to_dex_desc(KC.KILLER_APP_CLASS),
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
            r["_plugin_name"] = p.name
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

    active_rules = []
    plugin_summary = {}

    for r in all_discovered:
        p_id = r.get("_plugin_id", "")
        p_name = r.get("_plugin_name", p_id)
        if is_rule_active(r, p_id, ctx.active_features):
            active_rules.append(r)
            plugin_summary[p_name] = plugin_summary.get(p_name, 0) + 1

    # 树状紧凑优雅展示，杜绝刷屏
    print(f"  {Colors.CYAN}┌{Colors.RESET} 规则决议汇总 ({Colors.GREEN}{len(active_rules)}{Colors.RESET} 条生效规则, 涉及 {len(plugin_summary)} 个模块):")
    summary_items = list(plugin_summary.items())
    for i, (p_name, count) in enumerate(summary_items):
        branch = "└─" if i == len(summary_items) - 1 else "├─"
        print(f"  {Colors.CYAN}│{Colors.RESET}  {branch} {p_name:<24} ({Colors.BOLD}{count:>2}{Colors.RESET} 条规则)")

    return active_rules


def _make_host_entry_path():
    if KC.HOST_ENTRY_MODE == "fixed":
        return KC.HOST_ENTRY_FIXED
    alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
    d = "".join(random.choice(alphabet) for _ in range(random.randint(6, 8)))
    f = "".join(random.choice(alphabet) for _ in range(random.randint(8, 11)))
    return f"assets/{d}/{f}.apk"


def _prepare_killer_payload(ctx):
    if "killer" not in ctx.active_features:
        return

    if not ctx.target_pkg or not ctx.signature_b64:
        ctx.log("ERR", "目标包名或签名提取缺失，终止 Killer 载荷生成")
        raise SystemExit(1)

    ctx.host_entry_path = _make_host_entry_path()
    ctx.killer_app_dex = build_killer_app_dex(
        ctx, ctx.target_pkg, ctx.signature_b64, ctx.host_entry_path
    )


def run_stage2(ctx):
    ctx.print_stage_header(2, 5, "Fingerprint & Semantic Rule Resolve")
    extract_apk_metadata(ctx)

    with zipfile.ZipFile(ctx.input_apk, 'r') as zf:
        for name in zf.namelist():
            if re.match(r'^classes\d*\.dex$', name):
                ctx.dex_data_dict[name] = zf.read(name)

    def dex_index(name):
        if name == "classes.dex": return 1
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
            ctx.log("WARN", f"规则未命中当前包: [{r['name']}]")

    _prepare_killer_payload(ctx)