# -*- coding: utf-8 -*-
import os
import json
import time
import urllib.request

SOURCE_GROUPS = [
    {
        "name": "QQNT-Patcher 官方主仓全量底表",
        "is_authoritative": True,  # 权威主仓源：云端修改直接覆盖本地已有字段
        "urls": [
            "https://ghfast.top/https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json",
            "https://cdn.jsdelivr.net/gh/CattaFish/QQNT-Patcher@main/assets/subid.json",
            "https://fastly.jsdelivr.net/gh/CattaFish/QQNT-Patcher@main/assets/subid.json"
        ]
    },
    {
        "name": "qqDecoder 自动化流水线实时增量表",
        "is_authoritative": False,  # 增量补充源：只补全缺失 SubID，不反向覆盖主仓字段
        "urls": [
            "https://ghfast.top/https://raw.githubusercontent.com/CattaFish/qqDecoder/main/data/subid.json",
            "https://cdn.jsdelivr.net/gh/CattaFish/qqDecoder@main/data/subid.json",
            "https://fastly.jsdelivr.net/gh/CattaFish/qqDecoder@main/data/subid.json"
        ]
    }
]

def fetch_json_from_urls(urls):
    """带时间戳与防缓存头，击穿 CDN 缓存获取最新云端数据"""
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
            with urllib.request.urlopen(req, timeout=6.0) as resp:
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

def merge_entry(local_dict, subid_str, remote_val, is_authoritative=True):
    """
    深度合并：
    - 新增 SubID：直接吸纳
    - 权威主仓源 (is_authoritative=True)：云端修改强制覆盖本地现有字段
    - 增量补充源 (is_authoritative=False)：补充缺失字段；若云端检测出为 Beta 则支持升级
    """
    if subid_str not in local_dict:
        local_dict[subid_str] = remote_val
        return "add"

    l_val = local_dict[subid_str]

    if isinstance(l_val, str) and isinstance(remote_val, dict):
        local_dict[subid_str] = remote_val
        return "upgrade"

    elif isinstance(l_val, dict) and isinstance(remote_val, dict):
        modified = False

        # 1. 数组去重合并 (packages, versions)
        for rk in ("packages", "versions"):
            if rk in remote_val and isinstance(remote_val[rk], list):
                if rk not in l_val or not isinstance(l_val[rk], list):
                    l_val[rk] = remote_val[rk]
                    modified = True
                else:
                    combined = list(dict.fromkeys(l_val[rk] + remote_val[rk]))
                    if len(combined) != len(l_val[rk]):
                        l_val[rk] = combined
                        modified = True

        # 2. 标量字段 (summary, version, channel, date, arch, platform, is_beta 等)
        for rk, rv in remote_val.items():
            if rk in ("packages", "versions"):
                continue
            if rv is not None and str(rv).strip():
                if is_authoritative:
                    if rk not in l_val or l_val[rk] != rv:
                        l_val[rk] = rv
                        modified = True
                else:
                    if rk not in l_val:
                        l_val[rk] = rv
                        modified = True
                    # ★ 增量源特殊保护：若增量源检测出为 Beta，而本地尚未标记，允许升级
                    elif rk == "is_beta" and rv is True and not l_val.get("is_beta"):
                        l_val[rk] = True
                        modified = True

        return "upgrade" if modified else "none"

    elif is_authoritative and isinstance(remote_val, str) and l_val != remote_val:
        local_dict[subid_str] = remote_val
        return "upgrade"

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
        is_auth = group.get("is_authoritative", True)
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

            act = merge_entry(local_dict, subid_str, r_val, is_authoritative=is_auth)
            if act == "add":
                g_added += 1
            elif act == "upgrade":
                g_upgraded += 1

        total_added += g_added
        total_upgraded += g_upgraded
        ctx.log("INFO", f"-> 从 [{g_name}] 同步: 新增 {g_added} 条, 覆盖/更新属性 {g_upgraded} 条")

    if not local_dict:
        ctx.log("WARN", "未获取到任何有效 SubID 数据，放弃写盘")
        return

    sorted_dict = dict(sorted(local_dict.items(), key=lambda x: int(x[0]) if x[0].isdigit() else 0, reverse=True))

    temp_subid_path = assets_subid_path + ".tmp"
    try:
        with open(temp_subid_path, "w", encoding="utf-8") as f:
            json.dump(sorted_dict, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())

        os.replace(temp_subid_path, assets_subid_path)
        ctx.log("OK", f"-> SubID 字典全量对齐完成！当前收录 \033[36m{len(sorted_dict)}\033[0m 条 (已应用最新云端修改)")
    except Exception as e:
        if os.path.exists(temp_subid_path):
            try: os.remove(temp_subid_path)
            except Exception: pass
        ctx.log("WARN", f"写入 assets/subid.json 失败: {e}")