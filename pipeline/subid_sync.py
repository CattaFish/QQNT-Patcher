# -*- coding: utf-8 -*-
import os
import json
import time
import urllib.request

# 统一使用确切大小写 CattaFish，并添加时间戳击穿 CDN 缓存
SOURCE_GROUPS = [
    {
        "name": "QQNT-Patcher 官方主仓全量底表 (900+ 条)",
        "urls": [
            "https://ghfast.top/https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json",
            "https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json",
            "https://cdn.jsdelivr.net/gh/CattaFish/QQNT-Patcher@main/assets/subid.json"
        ]
    },
    {
        "name": "qqDecoder 自动化流水线实时增量表 (含豌豆荚等多渠道)",
        "urls": [
            "https://ghfast.top/https://raw.githubusercontent.com/CattaFish/qqDecoder/main/data/subid.json",
            "https://raw.githubusercontent.com/CattaFish/qqDecoder/main/data/subid.json",
            "https://cdn.jsdelivr.net/gh/CattaFish/qqDecoder@main/data/subid.json"
        ]
    }
]

def fetch_json_from_urls(urls):
    """带时间戳参数与防缓存头，强制击穿 CDN 节点旧缓存"""
    ts = int(time.time() * 1000)
    headers = {
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) QQNT-Patcher-Sync",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache"
    }

    for base_u in urls:
        sep = "&" if "?" in base_u else "?"
        u = f"{base_u}{sep}_nocache={ts}"
        try:
            req = urllib.request.Request(u, headers=headers)
            with urllib.request.urlopen(req, timeout=5.0) as resp:
                if resp.status == 200:
                    raw_data = resp.read().decode('utf-8')
                    data = json.loads(raw_data)
                    if isinstance(data, dict) and len(data) > 0:
                        return data
        except Exception:
            continue
    return None

def is_valid_subid(subid_str: str) -> bool:
    return subid_str.isdigit() and len(subid_str) == 9 and subid_str.startswith("537")

def merge_entry(local_dict, subid_str, remote_val):
    """深度合并：只增不减，并无条件吸纳渠道等新增字段"""
    if subid_str not in local_dict:
        local_dict[subid_str] = remote_val
        return "add"

    l_val = local_dict[subid_str]
    if isinstance(l_val, str) and isinstance(remote_val, dict):
        local_dict[subid_str] = remote_val
        return "upgrade"
    elif isinstance(l_val, dict) and isinstance(remote_val, dict):
        modified = False

        # 核心修复：如果远程提供了 channel 字段，无条件覆盖合并入本地！
        for rk, rv in remote_val.items():
            if rk not in l_val or (rk == "channel" and rv and rv != l_val.get("channel")):
                l_val[rk] = rv
                modified = True
            elif isinstance(rv, list) and isinstance(l_val.get(rk), list):
                combined = list(dict.fromkeys(l_val[rk] + rv))
                if len(combined) != len(l_val[rk]):
                    l_val[rk] = combined
                    modified = True

        l_ver = str(l_val.get("version", ""))
        r_ver = str(remote_val.get("version", ""))
        if l_ver and r_ver and l_ver != r_ver and r_ver not in l_ver:
            combined_ver = f"{l_ver} / {r_ver}"
            l_val["version"] = combined_ver
            l_val["summary"] = l_val.get("summary", "").replace(l_ver, combined_ver)
            modified = True

        return "upgrade" if modified else "none"

    return "none"

def sync_subid_incremental(ctx):
    assets_subid_path = os.path.join(ctx.assets_src_dir, "subid.json")
    os.makedirs(ctx.assets_src_dir, exist_ok=True)

    local_dict = {}
    if os.path.isfile(assets_subid_path):
        try:
            with open(assets_subid_path, "r", encoding="utf-8") as f:
                d = json.load(f)
                if isinstance(d, dict):
                    local_dict = {str(k).strip(): v for k, v in d.items() if is_valid_subid(str(k).strip())}
        except Exception as e:
            ctx.log("WARN", f"读取本地 assets/subid.json 异常: {e}")

    total_added = 0
    total_upgraded = 0

    for group in SOURCE_GROUPS:
        g_name = group["name"]
        remote_data = fetch_json_from_urls(group["urls"])

        if not remote_data:
            ctx.log("WARN", f"-> 数据源 [{g_name}] 镜像均未连通，跳过该源")
            continue

        g_added = 0
        g_upgraded = 0
        for subid, r_val in remote_data.items():
            subid_str = str(subid).strip()
            if not is_valid_subid(subid_str):
                continue

            act = merge_entry(local_dict, subid_str, r_val)
            if act == "add":
                g_added += 1
            elif act == "upgrade":
                g_upgraded += 1

        total_added += g_added
        total_upgraded += g_upgraded
        ctx.log("INFO", f"-> 从 [{g_name}] 同步: 新增 {g_added} 条, 升级属性 {g_upgraded} 条")

    if not local_dict:
        ctx.log("WARN", "未获取到任何有效 SubID 数据，放弃写盘")
        return

    sorted_dict = dict(sorted(local_dict.items(), key=lambda x: int(x[0]) if x[0].isdigit() else 0, reverse=True))

    # 原子写盘防中断截断
    temp_subid_path = assets_subid_path + ".tmp"
    try:
        with open(temp_subid_path, "w", encoding="utf-8") as f:
            json.dump(sorted_dict, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())

        os.replace(temp_subid_path, assets_subid_path)
        ctx.log("OK", f"-> SubID 字典全量对齐完成！当前收录 \033[36m{len(sorted_dict)}\033[0m 条 (已融合主仓与渠道数据)")
    except Exception as e:
        if os.path.exists(temp_subid_path):
            try: os.remove(temp_subid_path)
            except Exception: pass
        ctx.log("WARN", f"写入 assets/subid.json 失败: {e}")