package com.tencent.qqnt.patcher;

import top.nkbe.nza.sign.GenericSignatureKey;
import top.nkbe.nza.sign.V2V3SchemeSigner;
import top.nkbe.nza.zip.ZipConstant;
import top.nkbe.nza.zip.ZipEntry;
import top.nkbe.nza.zip.ZipFile;
import top.nkbe.nza.zip.ZipMaker;

import java.io.*;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

public class NeoPacker {

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("用法: java NeoPacker <orig_apk> <output_apk> <inject_dir> [keystore_path] [--no-killer] [--no-sign]");
            System.exit(1);
        }

        File origFile = new File(args[0]);
        File outFile = new File(args[1]);
        File injectDir = new File(args[2]);
        
        File keyOrStoreFile = args.length > 3 && !args[3].startsWith("--") ? new File(args[3]) : null;
        
        boolean enableKiller = true;
        boolean enableSign = true;

        for (String arg : args) {
            if ("--no-killer".equals(arg)) enableKiller = false;
            if ("--no-sign".equals(arg)) enableSign = false;
        }

        long t0 = System.currentTimeMillis();
        System.out.println("[NeoPacker] 启动装配引擎 (Killer模式: " + enableKiller + ", 签名: " + enableSign + ")...");

        try {
            // 1. 扫描 inject_dir 收集补丁与扩展文件 (防御过滤任何 input.apk 遗留)
            Map<String, File> injectedMap = new LinkedHashMap<>();
            if (injectDir.isDirectory()) {
                scanDirRecursive(injectDir, injectDir, injectedMap);
            }
            System.out.println("[NeoPacker] 待注入补丁/资源: " + injectedMap.size() + " 项");

            if (outFile.exists()) outFile.delete();

            try (ZipFile origZip = new ZipFile(origFile);
                 ZipMaker maker = new ZipMaker(outFile)) {

                maker.setLevel(ZipMaker.LEVEL_FASTER);

                // 2. 写入补丁文件 (DEX 极速压缩，SO 保持对齐)
                for (Map.Entry<String, File> e : injectedMap.entrySet()) {
                    String name = e.getKey();
                    File f = e.getValue();

                    ZipEntry origEntry = origZip.getEntry(name);
                    if (origEntry != null) {
                        maker.setMethod(origEntry.getMethod());
                    } else {
                        if (name.endsWith(".dex")) {
                            maker.setMethod(ZipMaker.METHOD_DEFLATED);
                        } else if (name.endsWith(".so")) {
                            maker.setMethod(ZipMaker.METHOD_STORED);
                        } else if (name.endsWith(".zip") || name.endsWith(".apk")) {
                            maker.setMethod(ZipMaker.METHOD_STORED);
                        } else {
                            maker.setMethod(ZipMaker.METHOD_DEFLATED);
                        }
                    }

                    maker.putNextEntry(name);
                    try (InputStream is = new BufferedInputStream(new FileInputStream(f))) {
                        maker.writeFully(is);
                    }
                    maker.closeEntry();
                }

                // 3. 原包内容处理
                if (enableKiller) {
                    // Killer 模式: 挂载原包 assets/Zcraft/input.apk 并建立零拷贝虚拟条目映射 (~380MB)
                    System.out.println("[NeoPacker] [Killer模式] 挂载 assets/Zcraft/input.apk 并建立数据复用映射...");
                    ZipMaker.HostEntryHolder host = maker.putNextHostEntry("assets/Zcraft/input.apk", origZip);

                    int virtualCount = 0;
                    for (ZipEntry entry : origZip.getEntries()) {
                        String name = entry.getName();
                        if (injectedMap.containsKey(name) || name.startsWith("META-INF/")) {
                            continue;
                        }
                        host.putNextVirtualEntry(name);
                        virtualCount++;
                    }
                    System.out.println("[NeoPacker] 数据复用完成: " + virtualCount + " 个文件直接映射，零体积膨胀");

                    // 注入原版 V1 证书三件套壳
                    byte[] rsaBytes = null;
                    boolean hasCertRsa = false;
                    for (ZipEntry entry : origZip.getEntries()) {
                        String name = entry.getName();
                        if (name.startsWith("META-INF/")) {
                            maker.copyZipEntry(entry, origZip);
                            if (name.equalsIgnoreCase("META-INF/CERT.RSA")) {
                                hasCertRsa = true;
                            } else if (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC")) {
                                try (InputStream is = origZip.getRawInputStream(entry);
                                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                                    byte[] buf = new byte[4096];
                                    int n;
                                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                                    rsaBytes = bos.toByteArray();
                                }
                            }
                        }
                    }

                    if (!hasCertRsa && rsaBytes != null) {
                        maker.setMethod(ZipMaker.METHOD_STORED);
                        maker.putNextEntry("META-INF/CERT.RSA");
                        maker.write(rsaBytes);
                        maker.closeEntry();
                    }
                } else {
                    // 纯净非 Killer 模式: 原样流式复用原包条目，绝不创建 input.apk 宿主条目 (~390MB)
                    System.out.println("[NeoPacker] [纯净模式] 直接合并原包条目，绝不内嵌 input.apk...");
                    int copyCount = 0;
                    for (ZipEntry entry : origZip.getEntries()) {
                        String name = entry.getName();
                        // 覆盖项或已修改条目不重复拷贝
                        if (!injectedMap.containsKey(name) && !name.startsWith("META-INF/")) {
                            maker.copyZipEntry(entry, origZip);
                            copyCount++;
                        }
                    }
                    System.out.println("[NeoPacker] 纯净合并完成: 复用原包 " + copyCount + " 个未修改条目");
                }
            } // maker.close() 写入 Central Directory 与 EOCD

            // 4. 签名阶段
            if (enableSign && keyOrStoreFile != null && keyOrStoreFile.isFile()) {
                System.out.println("[NeoPacker] 正在执行 V2 签名...");
                GenericSignatureKey sigKey = null;
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                try (InputStream is = new FileInputStream(keyOrStoreFile)) {
                    ks.load(is, "android".toCharArray());
                }
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(
                        "androiddebugkey", new KeyStore.PasswordProtection("android".toCharArray())
                );
                if (entry != null) {
                    sigKey = new GenericSignatureKey(entry.getPrivateKey(), (X509Certificate) entry.getCertificate());
                    V2V3SchemeSigner.sign(outFile, sigKey, true, false);
                    System.out.println("[NeoPacker] V2 签名完成");
                }
            } else {
                System.out.println("[NeoPacker] 跳过签名阶段");
            }

            long cost = System.currentTimeMillis() - t0;
            long mb = outFile.length() / (1024 * 1024);
            System.out.println("[NeoPacker] SUCCESS: 完成全部装配，耗时: " + cost + "ms，最终体积: " + mb + "MB");

        } catch (Throwable t) {
            System.err.println("[NeoPacker] 致命异常: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    private static void scanDirRecursive(File root, File current, Map<String, File> result) {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                scanDirRecursive(root, f, result);
            } else if (f.isFile() && !f.getName().startsWith(".")) {
                String relPath = root.toPath().relativize(f.toPath()).toString().replace(File.separator, "/");
                // 严密防御：彻底防止将 input.apk 误当作新资源再次写入
                if (!relPath.endsWith("input.apk")) {
                    result.put(relPath, f);
                }
            }
        }
    }
}
