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
        if (args.length < 4) {
            System.err.println("用法: java NeoPacker <orig_apk> <output_apk> <inject_dir> <keystore_or_pk8> [cert_der] [--no-killer]");
            System.exit(1);
        }

        File origFile = new File(args[0]);
        File outFile = new File(args[1]);
        File injectDir = new File(args[2]);
        File keyOrStoreFile = new File(args[3]);
        
        boolean enableKiller = true;
        for (String arg : args) {
            if ("--no-killer".equals(arg)) enableKiller = false;
        }

        long t0 = System.currentTimeMillis();
        System.out.println("[NeoPacker] 启动 NeoApk 工业级流式打包签名引擎...");

        try {
            // 1. 扫描 inject_dir
            Map<String, File> injectedMap = new LinkedHashMap<>();
            if (injectDir.isDirectory()) {
                scanDirRecursive(injectDir, injectDir, injectedMap);
            }
            System.out.println("[NeoPacker] 待注入新资源/DEX/SO: " + injectedMap.size() + " 项");

            // 2. 原生加载签名密钥 (自适应 Keystore 或 DER，彻底摆脱 OpenSSL 外部依赖)
            GenericSignatureKey sigKey = null;
            String nameLower = keyOrStoreFile.getName().toLowerCase();
            
            if (nameLower.endsWith(".keystore") || nameLower.endsWith(".jks") || nameLower.endsWith(".p12")) {
                KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                try (InputStream is = new FileInputStream(keyOrStoreFile)) {
                    ks.load(is, "android".toCharArray());
                }
                KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry) ks.getEntry(
                        "androiddebugkey", new KeyStore.PasswordProtection("android".toCharArray())
                );
                if (entry == null) {
                    throw new IllegalStateException("Keystore 中未找到别名 androiddebugkey");
                }
                sigKey = new GenericSignatureKey(entry.getPrivateKey(), (X509Certificate) entry.getCertificate());
                System.out.println("[NeoPacker] 已通过 Java 原生加载 Keystore 签名证书 (零 OpenSSL 依赖)");
            } else {
                // DER 格式兼容
                File certFile = new File(args[4]);
                byte[] pk8Bytes = Files.readAllBytes(keyOrStoreFile.toPath());
                PrivateKey privKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pk8Bytes));
                byte[] certBytes = Files.readAllBytes(certFile.toPath());
                X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certBytes));
                sigKey = new GenericSignatureKey(privKey, cert);
            }

            if (outFile.exists()) outFile.delete();

            try (ZipFile origZip = new ZipFile(origFile);
                 ZipMaker maker = new ZipMaker(outFile)) {

                maker.setLevel(ZipMaker.LEVEL_FASTER);

                // 3.1 写入新资源/DEX/SO (智能继承原包压缩方式，DEX 极速 Deflate 压缩)
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

                // 3.2 Killer 模式: 挂载原包 assets/Zcraft/input.apk 并建立虚拟条目映射
                if (enableKiller) {
                    System.out.println("[NeoPacker] 正在以 16KB 对齐挂载原包 assets/Zcraft/input.apk...");
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
                    System.out.println("[NeoPacker] 虚拟条目映射完成: " + virtualCount + " 个文件直接复用原包数据段 (零体积膨胀)");
                } else {
                    for (ZipEntry entry : origZip.getEntries()) {
                        String name = entry.getName();
                        if (!injectedMap.containsKey(name) && !name.startsWith("META-INF/")) {
                            maker.copyZipEntry(entry, origZip);
                        }
                    }
                }

                // 3.3 注入原版 V1 证书三件套壳
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
            }

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
                String relPath = root.toPath().relativize(f.toPath()).toString().replace('\', '/');
                result.put(relPath, f);
            }
        }
    }
}
