package com.tencent.qqnt.patcher;

import top.nkbe.nza.sign.GenericSignatureKey;
import top.nkbe.nza.sign.V2V3SchemeSigner;
import top.nkbe.nza.zip.ZipConstant;
import top.nkbe.nza.zip.ZipEntry;
import top.nkbe.nza.zip.ZipFile;
import top.nkbe.nza.zip.ZipMaker;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;

public class NeoPacker {

    public static void main(String[] args) {
        if (args.length < 5) {
            System.err.println("用法: java NeoPacker <orig_apk> <output_apk> <inject_dir> <key_pk8> <cert_der> [--no-killer]");
            System.exit(1);
        }

        File origFile = new File(args[0]);
        File outFile = new File(args[1]);
        File injectDir = new File(args[2]);
        File pk8File = new File(args[3]);
        File certFile = new File(args[4]);
        boolean enableKiller = args.length < 6 || !"--no-killer".equals(args[5]);

        long t0 = System.currentTimeMillis();
        System.out.println("[NeoPacker] 启动 NeoApk 工业级流式打包签名引擎...");

        try {
            // 1. 扫描 inject_dir 收集所有需要注入/覆盖的文件
            Map<String, File> injectedMap = new LinkedHashMap<>();
            if (injectDir.isDirectory()) {
                scanDirRecursive(injectDir, injectDir, injectedMap);
            }
            System.out.println("[NeoPacker] 待注入新资源/DEX/SO: " + injectedMap.size() + " 项");

            // 2. 准备签名密钥
            byte[] pk8Bytes = Files.readAllBytes(pk8File.toPath());
            KeyFactory kf = KeyFactory.getInstance("RSA");
            PrivateKey privKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pk8Bytes));

            byte[] certBytes = Files.readAllBytes(certFile.toPath());
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));
            GenericSignatureKey sigKey = new GenericSignatureKey(privKey, cert);

            if (outFile.exists()) outFile.delete();

            try (ZipFile origZip = new ZipFile(origFile);
                 ZipMaker maker = new ZipMaker(outFile)) {

                // 设置极速压缩等级 (兼顾压缩体积与打包速度，耗时大幅减少)
                maker.setLevel(ZipMaker.LEVEL_FASTER);

                // 3.1 写入注入/修改的文件 (智能匹配压缩方式，杜绝 DEX 裸存膨胀)
                for (Map.Entry<String, File> e : injectedMap.entrySet()) {
                    String name = e.getKey();
                    File f = e.getValue();
                    
                    // ★★★ 核心修复：优先继承原包压缩方式；新增 DEX 启用 Deflate 压缩 ★★★
                    ZipEntry origEntry = origZip.getEntry(name);
                    if (origEntry != null) {
                        maker.setMethod(origEntry.getMethod());
                    } else {
                        if (name.endsWith(".dex")) {
                            maker.setMethod(ZipMaker.METHOD_DEFLATED);
                        } else if (name.endsWith(".so")) {
                            // SO 库按原包方式对齐 (若新 SO 则 STORED 并 16KB 对齐)
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

                // 3.2 Killer 模式: 挂载原包 assets/SignedByRS/input.apk 并建立零拷贝虚拟条目映射
                if (enableKiller) {
                    System.out.println("[NeoPacker] 正在以 16KB 对齐挂载原包 assets/SignedByRS/input.apk...");
                    ZipMaker.HostEntryHolder host = maker.putNextHostEntry("assets/SignedByRS/input.apk", origZip);

                    int virtualCount = 0;
                    for (ZipEntry entry : origZip.getEntries()) {
                        String name = entry.getName();
                        if (injectedMap.containsKey(name) || name.startsWith("META-INF/")) {
                            continue;
                        }
                        host.putNextVirtualEntry(name);
                        virtualCount++;
                    }
                    System.out.println("[NeoPacker] 虚拟条目映射完成: " + virtualCount + " 个文件直接复用原包数据段 (零体积膨胀)");
                } else {
                    for (ZipEntry entry : origZip.getEntries()) {
                        String name = entry.getName();
                        if (!injectedMap.containsKey(name) && !name.startsWith("META-INF/")) {
                            maker.copyZipEntry(entry, origZip);
                        }
                    }
                }

                // 3.3 注入原版 V1 证书三件套壳 (META-INF/*.RSA, *.SF, MANIFEST.MF)
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
                    System.out.println("[NeoPacker] 已自动生成 META-INF/CERT.RSA 原版证书壳别名");
                }
            } // ZipMaker close() 自动写入 Central Directory 与 EOCD

            System.out.println("[NeoPacker] ZIP 打包完成，正在执行 NeoApk 原地 V2 签名...");

            // 4. 原地切入 V2 签名块
            V2V3SchemeSigner.sign(outFile, sigKey, true, false);

            long cost = System.currentTimeMillis() - t0;
            long mb = outFile.length() / (1024 * 1024);
            System.out.println("[NeoPacker] SUCCESS: 打包与 V2 签名全部完成，耗时: " + cost + "ms，最终体积: " + mb + "MB");

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
                String relPath = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                result.put(relPath, f);
            }
        }
    }
}
