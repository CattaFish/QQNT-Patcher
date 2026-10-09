# ============================================================
# 注意：核心项目全量雷达扫描与合并脚本，千万别删！
# 用于扫描工程所有源码与资产并导出为 merged_qqntpatch.txt
# ============================================================

import os

def scan_and_merge():
    base_dir = os.path.dirname(os.path.abspath(__file__))
    target_root = os.path.normpath(os.path.join(base_dir, "."))
    output_file = os.path.join(base_dir, "merged_qqntpatch.txt")

    text_extensions = {'.js', '.kt', '.java', '.xml', '.gradle', '.md', '.pro', '.cpp', '.h', '.proto', '.properties', '.yml', '.py'}
    image_extensions = {'.png', '.jpg', '.jpeg', '.webp', '.ico'}
    
    ignore_dirs = {'.gradle', '.idea', 'build', 'bin', 'gen', 'out', 'gradle'}
    ignore_rel_paths = {'src/bsh'}

    if not os.path.exists(target_root):
        print(f"错误: 找不到目录 {target_root}")
        return

    print(f"正在全量雷达扫描: {target_root}")
    print(f"结果将保存至: {output_file}")

    with open(output_file, 'w', encoding='utf-8') as f_out:
        for root, dirs, files in os.walk(target_root):
            filtered_dirs = []
            for d in dirs:
                if d in ignore_dirs:
                    continue
                
                rel_sub_dir = os.path.relpath(os.path.join(root, d), target_root).replace('\\', '/')
                if any(rel_sub_dir == p or rel_sub_dir.startswith(p + '/') for p in ignore_rel_paths):
                    continue
                    
                filtered_dirs.append(d)
                
            dirs[:] = filtered_dirs

            rel_dir = os.path.relpath(root, target_root)
            
            if rel_dir != "." and "src" not in rel_dir and rel_dir != "app":
                pass 

            for file in files:
                file_path = os.path.join(root, file)
                display_path = os.path.relpath(file_path, os.path.join(target_root, "..", ".."))
                ext = os.path.splitext(file)[1].lower()

                if ext in text_extensions:
                    f_out.write("\n" + "="*60 + "\n")
                    f_out.write(f"【文本文件内容】路径: {display_path}\n")
                    f_out.write("="*60 + "\n\n")
                    
                    try:
                        with open(file_path, 'r', encoding='utf-8', errors='ignore') as f_in:
                            f_out.write(f_in.read())
                    except Exception as e:
                        f_out.write(f"[读取失败: {str(e)}]\n")
                    f_out.write("\n\n")

                elif ext in image_extensions:
                    f_out.write(f"\n【图片文件路径】: {display_path}\n")

    print("扫描完成")

if __name__ == "__main__":
    scan_and_merge()
