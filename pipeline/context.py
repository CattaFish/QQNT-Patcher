# -*- coding: utf-8 -*-
import os
import sys
import shutil
import subprocess
import time
from .features import resolve_active_features, ALL_FEATURES

class Colors:
    RESET   = "\033[0m"
    BOLD    = "\033[1m"
    DIM     = "\033[2m"
    
    # 前景色
    BLUE    = "\033[38;5;75m"
    CYAN    = "\033[38;5;80m"
    GREEN   = "\033[38;5;78m"
    YELLOW  = "\033[38;5;214m"
    RED     = "\033[38;5;203m"
    PURPLE  = "\033[38;5;176m"
    GRAY    = "\033[38;5;244m"
    WHITE   = "\033[38;5;255m"

class ConsoleProgress:
    """轻量、优雅且自适应终端宽度的无依赖进度条"""
    def __init__(self, total, title="Processing", width=28):
        self.total = max(1, total)
        self.current = 0
        self.title = title
        self.width = width
        self.start_time = time.time()
        self.is_tty = sys.stdout.isatty()
        self.last_render_len = 0

    def update(self, step=1, item_name=""):
        self.current = min(self.total, self.current + step)
        self.render(item_name)

    def set_progress(self, current, item_name=""):
        self.current = min(self.total, current)
        self.render(item_name)

    def render(self, item_name=""):
        fraction = self.current / self.total
        percent = int(fraction * 100)
        filled = int(self.width * fraction)
        bar = "■" * filled + "·" * (self.width - filled)
        elapsed = time.time() - self.start_time

        item_str = f" [{item_name}]" if item_name else ""
        if len(item_str) > 28:
            item_str = item_str[:25] + "..."

        line = (
            f"  {Colors.BOLD}{Colors.CYAN}{self.title:<12}{Colors.RESET} "
            f"[{Colors.GREEN}{bar}{Colors.RESET}] "
            f"{Colors.BOLD}{percent:>3}%{Colors.RESET} "
            f"{Colors.DIM}({self.current}/{self.total}){Colors.RESET} "
            f"{Colors.GRAY}{elapsed:.1f}s{Colors.RESET}{Colors.CYAN}{item_str}{Colors.RESET}"
        )

        if self.is_tty:
            # 单行刷新
            cols = shutil.get_terminal_size((80, 20)).columns
            if len(line) > cols:
                line = line[:cols - 1]
            sys.stdout.write("\r" + line.ljust(self.last_render_len))
            sys.stdout.flush()
            self.last_render_len = len(line)
        else:
            # CI 或非交互式管道，每满 25% 或全部完成时换行打印
            if self.current == self.total or self.current % max(1, self.total // 4) == 0:
                sys.stdout.write(f"  {self.title} [{percent:>3}%] ({self.current}/{self.total}) {item_name}\n")
                sys.stdout.flush()

    def finish(self, summary=""):
        elapsed = time.time() - self.start_time
        if self.is_tty:
            sys.stdout.write("\r" + " " * (self.last_render_len + 4) + "\r")
            sys.stdout.flush()
        if summary:
            print(f"  {Colors.GREEN}✔{Colors.RESET} {summary} {Colors.DIM}({elapsed:.2f}s){Colors.RESET}")


class PipelineContext:
    def __init__(self, input_apk, output_apk, no_sign=False, skip_dex_patch=False, no_killer=False, skipped_keywords=None, only_keywords=None):
        self.root_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        self.tools_dir = os.path.join(self.root_dir, "tools")
        self.work_dir = os.path.join(self.root_dir, "build_cache")
        self.assets_src_dir = os.path.join(self.root_dir, "assets")
        self.preset_plugins_dir = os.path.join(self.root_dir, "preset_plugins")

        self.input_apk = os.path.abspath(input_apk)
        self.output_apk = os.path.abspath(output_apk)

        self.no_sign = no_sign
        self.skip_dex_patch = skip_dex_patch
        self.no_killer = no_killer
        self.skipped_keywords = skipped_keywords or []
        self.only_keywords = only_keywords or []

        # 特性决议
        self.active_features = resolve_active_features(self.only_keywords, self.skipped_keywords)
        if self.no_killer:
            self.active_features.discard("killer")
        elif "killer" not in self.active_features:
            self.no_killer = True

        # 工具链
        self.baksmali_jar = os.path.join(self.tools_dir, "baksmali.jar")
        self.smali_jar = os.path.join(self.tools_dir, "smali.jar")
        self.dexlib2_jar = os.path.join(self.tools_dir, "dexlib2.jar")
        self.guava_jar = os.path.join(self.tools_dir, "guava.jar")
        self.bsh_jar = os.path.join(self.tools_dir, "bsh.jar")
        self.dx_jar = os.path.join(self.tools_dir, "dx.jar")
        self.protobuf_jar = os.path.join(self.tools_dir, "protobuf.jar")
        self.android_jar = os.path.join(self.tools_dir, "android.jar")
        self.fixed_keystore = os.path.join(self.tools_dir, "debug.keystore")

        self.target_pkg = ""
        self.signature_b64 = ""
        self.host_entry_path = ""
        self.killer_app_dex = None

        self.orig_apk_md5 = ""
        self.orig_sig_md5 = ""
        self.dex_data_dict = {}
        self.dex_classes_map = {}
        self.max_dex_idx = 0
        self.all_rules = []
        self.dex_to_rules = {}
        self.modified_dex_files = []

        self.libs_dex_path = None
        self.patch_dex_path = None
        self.bsh_standalone_dex = None
        self.preset_plugins_zip = None
        self.engine_bin = None

        from .providers import get_provider
        p_name = "none" if self.no_sign else ("debug" if self.no_killer else "killer")
        self.provider = get_provider(self, p_name)

    def print_stage_header(self, step_idx, total_steps, title):
        width = min(shutil.get_terminal_size((80, 20)).columns, 82)
        prefix = f"── [{step_idx}/{total_steps}] {title.upper()} "
        bar = "─" * max(2, width - len(prefix))
        print(f"\n{Colors.BOLD}{Colors.BLUE}{prefix}{bar}{Colors.RESET}")

    def log(self, tag, msg, indent=0):
        badges = {
            "INFO":  f"{Colors.BLUE}[INFO]{Colors.RESET}",
            "OK":    f"{Colors.GREEN}[DONE]{Colors.RESET}",
            "WARN":  f"{Colors.YELLOW}[WARN]{Colors.RESET}",
            "ERR":   f"{Colors.RED}[FAIL]{Colors.RESET}",
            "TIME":  f"{Colors.PURPLE}[TIME]{Colors.RESET}",
            "CACHE": f"{Colors.CYAN}[CACHE]{Colors.RESET}",
            "STEP":  f"{Colors.GRAY}[····]{Colors.RESET}"
        }
        badge = badges.get(tag, f"[{tag}]")
        pad = "  " * indent
        print(f"{pad}{badge} {msg}")

    def create_progress(self, total, title="Progress"):
        return ConsoleProgress(total=total, title=title)

    def run_cmd(self, cmd, cwd=None):
        ret = subprocess.run(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=cwd)
        out_msg = ret.stdout.decode('utf-8', errors='ignore').strip()
        err_msg = ret.stderr.decode('utf-8', errors='ignore').strip()
        if err_msg:
            for line in err_msg.split("\n"):
                if "[WARN]" in line: self.log("WARN", line)
                elif "[ERROR]" in line or "Exception" in line: self.log("ERR", line)
        if ret.returncode != 0:
            self.log("ERR", f"命令执行异常 (Code {ret.returncode}): {cmd}")
            if err_msg: self.log("ERR", err_msg)
            return ""
        return out_msg

    def run_cmd_stream(self, cmd, on_line=None, cwd=None):
        p = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=cwd, universal_newlines=True, bufsize=1)
        for line in p.stdout:
            l = line.strip()
            if not l: continue
            if on_line:
                on_line(l)
            else:
                if "[WARN]" in l: self.log("WARN", l)
                elif "[ERROR]" in l: self.log("ERR", l)
                elif "[DexPatcher]" in l: print(f"  {Colors.CYAN}│{Colors.RESET} {l}")
                else: print(f"    {Colors.DIM}{l}{Colors.RESET}")
        p.wait()
        return p.returncode

    def find_tool(self, name):
        p = shutil.which(name)
        if p: return p
        h = os.environ.get("ANDROID_HOME", "")
        if h:
            bt = os.path.join(h, "build-tools")
            if os.path.isdir(bt):
                for v in sorted(os.listdir(bt), reverse=True):
                    tp = os.path.join(bt, v, name)
                    if os.path.isfile(tp): return tp
        return name