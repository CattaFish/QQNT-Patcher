import os

def scan_and_merge():
    # 1. 路径设置：向上跳转，定位到模块根目录 app/zzz
    base_dir = os.path.dirname(os.path.abspath(__file__))
    target_root = os.path.normpath(os.path.join(base_dir, "."))
    output_file = os.path.join(base_dir, "merged_qqntpatch.txt")

    # 2. 定义规则
    text_extensions = {'.js', '.kt', '.java', '.xml', '.gradle', '.md', '.pro', '.cpp', '.h', '.proto', '.properties', '.yml', '.py'}
    image_extensions = {'.png', '.jpg', '.jpeg', '.webp', '.ico'}
    
    # 忽略文件夹名（通用的垃圾/构建目录）
    ignore_dirs = {'.gradle', '.idea', 'build', 'bin', 'gen', 'out', 'gradle'}
    
    # 【新增】指定忽略的具体相对路径（统一使用 '/'，适配 Windows 和 Linux）
    ignore_rel_paths = {'src/bsh'}

    if not os.path.exists(target_root):
        print(f"错误: 找不到目录 {target_root}")
        return

    print(f"正在全量雷达扫描: {target_root}")
    print(f"结果将保存至: {output_file}")

    with open(output_file, 'w', encoding='utf-8') as f_out:
        for root, dirs, files in os.walk(target_root):
            # 【核心修改点】：过滤目录
            # 必须就地修改 dirs[:]，这样 os.walk 就不会递归遍历被过滤掉的文件夹
            filtered_dirs = []
            for d in dirs:
                # 1. 按名称忽略
                if d in ignore_dirs:
                    continue
                
                # 2. 按具体相对路径忽略（例如 src/bsh）
                # 获取子文件夹相对于 target_root 的路径并标准化为 / 分隔符
                rel_sub_dir = os.path.relpath(os.path.join(root, d), target_root).replace('\\', '/')
                if any(rel_sub_dir == p or rel_sub_dir.startswith(p + '/') for p in ignore_rel_paths):
                    continue
                    
                filtered_dirs.append(d)
                
            dirs[:] = filtered_dirs  # 更新待遍历目录

            # 获取当前文件夹相对于根目录的深度
            rel_dir = os.path.relpath(root, target_root)
            
            # 【单层/深层控制】
            if rel_dir != "." and "src" not in rel_dir and rel_dir != "app":
                pass 

            for file in files:
                file_path = os.path.join(root, file)
                # 计算输出的显示路径
                display_path = os.path.relpath(file_path, os.path.join(target_root, "..", ".."))
                ext = os.path.splitext(file)[1].lower()

                # 情况 A: 文本文件
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

                # 情况 B: 图片文件
                elif ext in image_extensions:
                    f_out.write(f"\n【图片文件路径】: {display_path}\n")

    print("扫描完成")

if __name__ == "__main__":
    scan_and_merge()