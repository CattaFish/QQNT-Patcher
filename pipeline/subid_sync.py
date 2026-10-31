# -*- coding: utf-8 -*-
import os
import json
import time
import urllib.request

# 主源：Cloudflare 边缘 Worker 实时聚合端点
PRIMARY_API = "https://qqnt-patcher.zcraft.dpdns.org/subid.json"
# 唯一备用源：遵循 gh-proxy 官方规范的加速镜像
FALLBACK_PROXY = "https://gh-proxy.com/https://raw.githubusercontent.com/CattaFish/QQNT-Patcher/main/assets/subid.json"

def fetch_json(url):
    ts = int(time.time() * 1000)
    req_url = f"{url}?_nocache={ts}"
    headers = {
        "User-Agent": "QQNT-Patcher-Sync",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache"
    }
    try:
        req = urllib.request.Request(req_url, headers=headers)
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode('utf-8'))
                if isinstance(data, dict) and len(data) > 0:
                    return data
    except Exception:
        pass
    return None

def sync_subid_incremental(ctx):
    assets_subid_path = os.path.join(ctx.assets_src_dir, "subid.json")
    os.makedirs(ctx.assets_src_dir, exist_ok=True)

    # 1. 优先拉取 Cloudflare 边缘实时聚合数据
    remote_data = fetch_json(PRIMARY_API)
    if not remote_data:
        ctx.log("WARN", "-> Cloudflare 聚合端点未响应，尝试 gh-proxy 代理源兜底...")
        remote_data = fetch_json(FALLBACK_PROXY)

    if not remote_data:
        ctx.log("WARN", "-> 未能获取云端 SubID 字典，保留本地现有版本")
        return

    # 2. 原子写入 assets/subid.json
    temp_path = assets_subid_path + ".tmp"
    try:
        with open(temp_path, "w", encoding="utf-8") as f:
            json.dump(remote_data, f, ensure_ascii=False, indent=2)
            f.flush()
            os.fsync(f.fileno())

        os.replace(temp_path, assets_subid_path)
        ctx.log("OK", f"-> SubID 字典实时对齐完成！当前收录 \033[36m{len(remote_data)}\033[0m 条 (来自 Cloudflare 边缘聚合)")
    except Exception as e:
        if os.path.exists(temp_path):
            try: os.remove(temp_path)
            except Exception: pass
        ctx.log("WARN", f"写入 assets/subid.json 失败: {e}")