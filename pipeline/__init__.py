# -*- coding: utf-8 -*-
import os
import shutil
import time
from .context import PipelineContext, Colors
from .stage1_prepare import run_stage1
from .stage2_rules import run_stage2
from .stage3_transform import run_stage3
from .stage4_pack import run_stage4
from .stage5_sign import run_stage5

def run_pipeline(ctx: PipelineContext):
    t_start = time.time()
    
    # 打印现代简约标题
    print(f"{Colors.BOLD}{Colors.CYAN}┌─────────────────────────────────────────────────────────┐{Colors.RESET}")
    print(f"{Colors.BOLD}{Colors.CYAN}│{Colors.RESET}   QQNT-Patcher Pipeline Bus Engine                      {Colors.BOLD}{Colors.CYAN}│{Colors.RESET}")
    print(f"{Colors.BOLD}{Colors.CYAN}└─────────────────────────────────────────────────────────┘{Colors.RESET}")
    
    run_stage1(ctx)
    run_stage2(ctx)
    run_stage3(ctx)
    run_stage4(ctx)
    run_stage5(ctx)

    # 清理非持久缓存
    keep_list = {
        "patcher_bin", "dex_out", "bin", "bsh.dex", "bsh_dex",
        "libs_cached.dex", "preset_plugins.zip", "apk_meta_cache.json",
        "dex_cache", "rule_discovery_cache.json", "dex_classes_map.json",
        "killer_cache"
    }
    for f in os.listdir(ctx.work_dir):
        if f not in keep_list:
            p = os.path.join(ctx.work_dir, f)
            if os.path.isdir(p): shutil.rmtree(p, ignore_errors=True)
            else: os.remove(p)

    total_cost = round(time.time() - t_start, 2)
    out_size_mb = os.path.getsize(ctx.output_apk) / (1024 * 1024) if os.path.isfile(ctx.output_apk) else 0

    print(f"\n{Colors.BOLD}{Colors.GREEN}═══════════════════════════════════════════════════════════{Colors.RESET}")
    print(f"  {Colors.BOLD}{Colors.GREEN}BUILD SUCCESSFUL{Colors.RESET}")
    print(f"  {Colors.GRAY}├─ 产物路径:{Colors.RESET} {Colors.WHITE}{ctx.output_apk}{Colors.RESET}")
    print(f"  {Colors.GRAY}├─ 产物体积:{Colors.RESET} {Colors.CYAN}{out_size_mb:.2f} MB{Colors.RESET}")
    print(f"  {Colors.GRAY}└─ 构建耗时:{Colors.RESET} {Colors.PURPLE}{total_cost}s{Colors.RESET}")
    print(f"{Colors.BOLD}{Colors.GREEN}═══════════════════════════════════════════════════════════{Colors.RESET}\n")