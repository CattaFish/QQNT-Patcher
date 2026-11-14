package com.tencent.qqnt.patcher;

import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ValueType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class IconManifestPatcher {

    private static final String DEFAULT_ALIAS = "com.tencent.mobileqq.activity.ZzzDefaultSplashActivity";
    private static final String CUSTOM_ALIAS_PREFIX = "com.tencent.mobileqq.activity.ZzzIconAlias_";
    private static final String TARGET_ACTIVITY = "com.tencent.mobileqq.activity.SplashActivity";

    // AOSP 固定公开属性 ID (永远不变)
    private static final int ATTR_LABEL = 0x01010001;
    private static final int ATTR_ICON = 0x01010002;
    private static final int ATTR_NAME = 0x01010003;
    private static final int ATTR_ENABLED = 0x0101000e;
    private static final int ATTR_EXPORTED = 0x01010010;
    private static final int ATTR_TARGET_ACTIVITY = 0x01010202;

    static class IconSpec {
        String id;
        String displayName;
        String entryName;
        String resPath;
        String assetRelPath;
        File sourceFile;
        int resId;
        boolean isDefault;
    }

    static class NameSpec {
        String id;
        String label;
        boolean isDefault;
    }

    static class AliasSpec {
        String iconId;
        String nameId;
        String aliasName;
        int iconResId;
        String customLabel; // null 表示沿用原包 defaultLabelResId
        boolean enabled;
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        if (args.length < 3) {
            System.err.println("用法: java IconManifestPatcher <orig_apk> <out_dir> <icons_dir>");
            System.exit(1);
        }

        File origApk = new File(args[0]);
        File outDir = new File(args[1]);
        File iconsDir = new File(args[2]);

        try {
            List<File> iconFiles = collectIconFiles(iconsDir);
            List<String> customNames = collectCustomNames(new File(iconsDir, "names.txt"));

            if (iconFiles.isEmpty() && customNames.isEmpty()) {
                System.out.println("[IconManifestPatcher] icons 与 names.txt 均为空，跳过注入");
                return;
            }

            if (!outDir.exists() && !outDir.mkdirs()) {
                throw new IOException("无法创建输出目录: " + outDir.getAbsolutePath());
            }

            // 1. 构造图标列表（第 0 项固定为默认原生图标）
            List<IconSpec> iconSpecs = new ArrayList<>();
            IconSpec defaultIconSpec = new IconSpec();
            defaultIconSpec.id = "default";
            defaultIconSpec.displayName = "默认图标";
            defaultIconSpec.isDefault = true;
            iconSpecs.add(defaultIconSpec);

            List<IconSpec> customIconSpecs = new ArrayList<>();
            Set<String> usedIconIds = new HashSet<>();
            for (int i = 0; i < iconFiles.size(); i++) {
                File f = iconFiles.get(i);
                String rawName = f.getName();
                int dot = rawName.lastIndexOf('.');
                String displayName = dot > 0 ? rawName.substring(0, dot) : rawName;
                String ext = dot > 0 ? rawName.substring(dot).toLowerCase(Locale.ROOT) : ".png";

                String iconId = shortHash(rawName);
                if (!usedIconIds.add(iconId)) {
                    iconId = iconId + "_" + i;
                    usedIconIds.add(iconId);
                }

                IconSpec spec = new IconSpec();
                spec.id = iconId;
                spec.displayName = displayName;
                spec.entryName = "zzz_ic_" + i;
                spec.resPath = "res/drawable/" + spec.entryName + ext;
                spec.assetRelPath = "icons/" + spec.entryName + ext;
                spec.sourceFile = f;
                spec.isDefault = false;
                iconSpecs.add(spec);
                customIconSpecs.add(spec);
            }

            // 2. 构造名称列表（第 0 项固定为默认 QQ）
            List<NameSpec> nameSpecs = new ArrayList<>();
            NameSpec defaultNameSpec = new NameSpec();
            defaultNameSpec.id = "default";
            defaultNameSpec.label = "QQ (默认)";
            defaultNameSpec.isDefault = true;
            nameSpecs.add(defaultNameSpec);

            Set<String> usedNameIds = new HashSet<>();
            for (int i = 0; i < customNames.size(); i++) {
                String lbl = customNames.get(i);
                String nameId = "n_" + shortHash("name:" + lbl);
                if (!usedNameIds.add(nameId)) {
                    nameId = nameId + "_" + i;
                    usedNameIds.add(nameId);
                }
                NameSpec ns = new NameSpec();
                ns.id = nameId;
                ns.label = lbl;
                ns.isDefault = false;
                nameSpecs.add(ns);
            }

            List<AliasSpec> aliasSpecs = new ArrayList<>();

            try (ZipFile zip = new ZipFile(origApk)) {
                ZipEntry arscEntry = zip.getEntry("resources.arsc");
                ZipEntry manifestEntry = zip.getEntry("AndroidManifest.xml");
                if (arscEntry == null || manifestEntry == null) {
                    throw new IllegalStateException("APK 缺少 resources.arsc 或 AndroidManifest.xml");
                }

                // 3. 仅在存在自定义图片时修补 resources.arsc
                if (!customIconSpecs.isEmpty()) {
                    byte[] origArscBytes = readAll(zip.getInputStream(arscEntry));
                    byte[] patchedArscBytes = tryAppendArscInPlace(origArscBytes, customIconSpecs);

                    if (patchedArscBytes == null) {
                        System.out.println("[IconManifestPatcher] 检测到特殊 ARSC 格式，回退使用 ARSCLib 重建");
                        patchedArscBytes = patchArscWithArscLib(origArscBytes, customIconSpecs);
                    }

                    File outArsc = new File(outDir, "resources.arsc");
                    try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outArsc))) {
                        os.write(patchedArscBytes);
                    }
                }

                // 4. 修补 AndroidManifest.xml（生成 iconSpecs × nameSpecs 全组合别名）
                AndroidManifestBlock manifest;
                try (InputStream is = zip.getInputStream(manifestEntry)) {
                    manifest = AndroidManifestBlock.load(is);
                }

                patchManifest(manifest, iconSpecs, nameSpecs, aliasSpecs);

                File outManifest = new File(outDir, "AndroidManifest.xml");
                try (OutputStream os = new BufferedOutputStream(new FileOutputStream(outManifest))) {
                    manifest.writeBytes(os);
                }
            }

            // 5. 处理图标（透明轮廓图自动套纯白自适应背景）并写入 res/drawable/ 与 assets/icons/
            for (IconSpec spec : customIconSpecs) {
                File resOut = new File(outDir, spec.resPath);
                resOut.getParentFile().mkdirs();
                writeAdaptiveWhiteIconIfNeeded(spec.sourceFile, resOut);

                File assetOut = new File(outDir, "assets/" + spec.assetRelPath);
                assetOut.getParentFile().mkdirs();
                copyFile(resOut, assetOut);
            }

            // 6. 生成 assets/zzz_icons.json 运行时清单
            writeIconsJson(new File(outDir, "assets/zzz_icons.json"), iconSpecs, nameSpecs, aliasSpecs);

            System.out.println("[IconManifestPatcher] OK: 成功生成 "
                    + customIconSpecs.size() + " 套图标 × " + nameSpecs.size()
                    + " 个名称（共 " + aliasSpecs.size() + " 个桌面别名）");
        } catch (Throwable t) {
            System.err.println("[IconManifestPatcher] FAIL: " + t.getMessage());
            t.printStackTrace();
            System.exit(1);
        }
    }

    // =========================================================================
    // 透明背景图标自动套纯白自适应底 (#FFFFFF)
    // =========================================================================
    private static void writeAdaptiveWhiteIconIfNeeded(File srcFile, File dstFile) throws IOException {
        if (!srcFile.getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
            copyFile(srcFile, dstFile);
            return;
        }

        BufferedImage src;
        try {
            src = ImageIO.read(srcFile);
        } catch (Throwable t) {
            copyFile(srcFile, dstFile);
            return;
        }
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) {
            copyFile(srcFile, dstFile);
            return;
        }

        int w = src.getWidth();
        int h = src.getHeight();

        // 1. 若四角均不透明，说明本身已是满铺方形图标，直接保留原图
        if (isOpaqueAt(src, 0, 0) && isOpaqueAt(src, w - 1, 0)
                && isOpaqueAt(src, 0, h - 1) && isOpaqueAt(src, w - 1, h - 1)) {
            copyFile(srcFile, dstFile);
            return;
        }

        // 2. 判断四边中点是否不透明（预圆角图标只补白四角，不缩小）
        boolean midEdgesOpaque = isOpaqueAt(src, w / 2, 1)
                && isOpaqueAt(src, w / 2, h - 2)
                && isOpaqueAt(src, 1, h / 2)
                && isOpaqueAt(src, w - 2, h / 2);

        final int outSize = midEdgesOpaque ? Math.max(w, h) : 432;
        BufferedImage dst = new BufferedImage(outSize, outSize, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < outSize; y++) {
            for (int x = 0; x < outSize; x++) {
                dst.setRGB(x, y, 0xFFFFFF);
            }
        }

        int minX = 0, minY = 0, maxX = w - 1, maxY = h - 1;
        double targetRatio = 1.0;

        if (!midEdgesOpaque) {
            // 扫描可见前景边界，防止自带过多透明边距导致二次缩太小
            int foundMinX = w, foundMinY = h, foundMaxX = -1, foundMaxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = (src.getRGB(x, y) >>> 24) & 0xFF;
                    if (a > 8) {
                        if (x < foundMinX) foundMinX = x;
                        if (x > foundMaxX) foundMaxX = x;
                        if (y < foundMinY) foundMinY = y;
                        if (y > foundMaxY) foundMaxY = y;
                    }
                }
            }
            if (foundMaxX >= foundMinX && foundMaxY >= foundMinY) {
                minX = foundMinX;
                minY = foundMinY;
                maxX = foundMaxX;
                maxY = foundMaxY;
            }
            targetRatio = 0.74; // 占画布 74%，适配圆形/圆角矩形遮罩且不被裁边
        }

        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        double maxBox = outSize * targetRatio;
        double scale = maxBox / Math.max(bw, bh);

        int dstW = Math.max(1, (int) Math.round(bw * scale));
        int dstH = Math.max(1, (int) Math.round(bh * scale));
        int offX = (outSize - dstW) / 2;
        int offY = (outSize - dstH) / 2;

        // 预乘 Alpha 双线性插值合成到纯白底，边缘抗锯齿无黑边
        for (int dy = 0; dy < dstH; dy++) {
            int py = offY + dy;
            if (py < 0 || py >= outSize) continue;
            double sy = minY + (dy + 0.5) / scale - 0.5;
            int y0 = clamp((int) Math.floor(sy), 0, h - 1);
            int y1 = clamp(y0 + 1, 0, h - 1);
            double fy = Math.max(0.0, Math.min(1.0, sy - Math.floor(sy)));

            for (int dx = 0; dx < dstW; dx++) {
                int px = offX + dx;
                if (px < 0 || px >= outSize) continue;
                double sx = minX + (dx + 0.5) / scale - 0.5;
                int x0 = clamp((int) Math.floor(sx), 0, w - 1);
                int x1 = clamp(x0 + 1, 0, w - 1);
                double fx = Math.max(0.0, Math.min(1.0, sx - Math.floor(sx)));

                int c00 = src.getRGB(x0, y0);
                int c10 = src.getRGB(x1, y0);
                int c01 = src.getRGB(x0, y1);
                int c11 = src.getRGB(x1, y1);

                double w00 = (1.0 - fx) * (1.0 - fy);
                double w10 = fx * (1.0 - fy);
                double w01 = (1.0 - fx) * fy;
                double w11 = fx * fy;

                double a = alphaNorm(c00) * w00 + alphaNorm(c10) * w10 + alphaNorm(c01) * w01 + alphaNorm(c11) * w11;
                double pr = premul(c00, 16) * w00 + premul(c10, 16) * w10 + premul(c01, 16) * w01 + premul(c11, 16) * w11;
                double pg = premul(c00, 8)  * w00 + premul(c10, 8)  * w10 + premul(c01, 8)  * w01 + premul(c11, 8)  * w11;
                double pb = premul(c00, 0)  * w00 + premul(c10, 0)  * w10 + premul(c01, 0)  * w01 + premul(c11, 0)  * w11;

                int r = clamp((int) Math.round(pr + 255.0 * (1.0 - a)), 0, 255);
                int g = clamp((int) Math.round(pg + 255.0 * (1.0 - a)), 0, 255);
                int b = clamp((int) Math.round(pb + 255.0 * (1.0 - a)), 0, 255);

                dst.setRGB(px, py, (r << 16) | (g << 8) | b);
            }
        }

        ImageIO.write(dst, "png", dstFile);
    }

    private static boolean isOpaqueAt(BufferedImage img, int x, int y) {
        return ((img.getRGB(x, y) >>> 24) & 0xFF) >= 240;
    }

    private static double alphaNorm(int argb) {
        return ((argb >>> 24) & 0xFF) / 255.0;
    }

    private static double premul(int argb, int shift) {
        double a = ((argb >>> 24) & 0xFF) / 255.0;
        return ((argb >>> shift) & 0xFF) * a;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // =========================================================================
    // 零重排、字节级尾部追加修补 resources.arsc（保持原有索引 100% 不变）
    // =========================================================================
    private static byte[] tryAppendArscInPlace(byte[] buf, List<IconSpec> specs) throws IOException {
        if (buf.length < 12 || readU16(buf, 0) != 0x0002) return null;
        int tableHeaderSize = readU16(buf, 2);
        int origTableSize = readU32(buf, 4);
        if (origTableSize != buf.length) return null;

        int globalPoolOff = tableHeaderSize;
        if (readU16(buf, globalPoolOff) != 0x0001) return null;
        int origGlobalPoolSize = readU32(buf, globalPoolOff + 4);
        int origGlobalStrCount = readU32(buf, globalPoolOff + 8);

        List<String> newGlobalStrings = new ArrayList<>();
        for (IconSpec s : specs) newGlobalStrings.add(s.resPath);
        byte[] patchedGlobalPool = appendStringsToPool(buf, globalPoolOff, origGlobalPoolSize, newGlobalStrings);

        // 定位第一个 Package (0x7f)
        int pos = globalPoolOff + origGlobalPoolSize;
        int targetPkgOff = -1;
        int targetPkgSize = 0;
        while (pos < origTableSize) {
            int ctype = readU16(buf, pos);
            int csize = readU32(buf, pos + 4);
            if (ctype == 0x0200) {
                int pkgId = readU32(buf, pos + 8);
                if (pkgId == 0x7f || targetPkgOff == -1) {
                    targetPkgOff = pos;
                    targetPkgSize = csize;
                    if (pkgId == 0x7f) break;
                }
            }
            pos += csize;
        }
        if (targetPkgOff == -1) return null;

        int pkgHeaderSize = readU16(buf, targetPkgOff + 2);
        int pkgId = readU32(buf, targetPkgOff + 8);
        int typeStringsRel = readU32(buf, targetPkgOff + 268);
        int keyStringsRel = readU32(buf, targetPkgOff + 276);
        int lastPublicKey = readU32(buf, targetPkgOff + 280);

        int typeStringsOff = targetPkgOff + typeStringsRel;
        int keyStringsOff = targetPkgOff + keyStringsRel;
        if (readU16(buf, typeStringsOff) != 0x0001 || readU16(buf, keyStringsOff) != 0x0001) return null;

        List<String> typeNames = readPoolStrings(buf, typeStringsOff);
        int drawableTypeId = -1;
        for (int i = 0; i < typeNames.size(); i++) {
            if ("drawable".equals(typeNames.get(i))) {
                drawableTypeId = i + 1;
                break;
            }
        }
        if (drawableTypeId <= 0) return null;

        int origKeyPoolSize = readU32(buf, keyStringsOff + 4);
        int origKeyStrCount = readU32(buf, keyStringsOff + 8);
        List<String> newKeyStrings = new ArrayList<>();
        for (IconSpec s : specs) newKeyStrings.add(s.entryName);
        byte[] patchedKeyPool = appendStringsToPool(buf, keyStringsOff, origKeyPoolSize, newKeyStrings);

        // 先遍历检查 drawable 的所有 TypeSpec/Type 是否均为 flags==0x00 的标准格式，并找到默认空配置 Type
        int pkgEnd = targetPkgOff + targetPkgSize;
        int sub = targetPkgOff + pkgHeaderSize;
        int defaultDrawableTypeOff = -1;
        int firstDrawableTypeOff = -1;
        int drawableEntryCount = -1;

        while (sub < pkgEnd) {
            int st = readU16(buf, sub);
            int shsz = readU16(buf, sub + 2);
            int scsz = readU32(buf, sub + 4);
            if (st == 0x0202 && (buf[sub + 8] & 0xFF) == drawableTypeId) {
                drawableEntryCount = readU32(buf, sub + 12);
            } else if (st == 0x0201 && (buf[sub + 8] & 0xFF) == drawableTypeId) {
                int tflags = buf[sub + 9] & 0xFF;
                if (tflags != 0) return null; // 若为 SPARSE/OFFSET16 则走 ARSCLib 回退路径
                if (firstDrawableTypeOff == -1) firstDrawableTypeOff = sub;
                if (defaultDrawableTypeOff == -1 && isDefaultEmptyConfig(buf, sub + 20, shsz - 20)) {
                    defaultDrawableTypeOff = sub;
                }
            }
            sub += scsz;
        }
        if (drawableEntryCount < 0 || firstDrawableTypeOff == -1) return null;
        if (defaultDrawableTypeOff == -1) defaultDrawableTypeOff = firstDrawableTypeOff;

        int n = specs.size();
        for (int i = 0; i < n; i++) {
            specs.get(i).resId = (pkgId << 24) | (drawableTypeId << 16) | (drawableEntryCount + i);
        }

        // 重组 Package 字节流
        ByteArrayOutputStream pkgBody = new ByteArrayOutputStream(targetPkgSize + n * 64);
        pkgBody.write(buf, targetPkgOff, pkgHeaderSize);

        int newTypeStringsRel = typeStringsRel;
        int newKeyStringsRel = keyStringsRel;

        sub = targetPkgOff + pkgHeaderSize;
        while (sub < pkgEnd) {
            int st = readU16(buf, sub);
            int shsz = readU16(buf, sub + 2);
            int scsz = readU32(buf, sub + 4);

            if (sub == typeStringsOff) {
                newTypeStringsRel = pkgBody.size();
                pkgBody.write(buf, sub, scsz);
            } else if (sub == keyStringsOff) {
                newKeyStringsRel = pkgBody.size();
                pkgBody.write(patchedKeyPool);
            } else if (st == 0x0202 && (buf[sub + 8] & 0xFF) == drawableTypeId) {
                // TypeSpec: 追加 n 个 0x00000000 flag
                byte[] chunk = new byte[scsz + n * 4];
                System.arraycopy(buf, sub, chunk, 0, scsz);
                writeU32(chunk, 4, chunk.length);
                writeU32(chunk, 12, drawableEntryCount + n);
                pkgBody.write(chunk);
            } else if (st == 0x0201 && (buf[sub + 8] & 0xFF) == drawableTypeId) {
                int ec = readU32(buf, sub + 12);
                int estart = readU32(buf, sub + 16);
                int offsEnd = shsz + ec * 4;
                int dataLen = scsz - estart;

                if (sub == defaultDrawableTypeOff) {
                    // 默认配置 Type: 插入 n 个偏移，并在尾部追加 n 个 16 字节 Simple Entry
                    ByteArrayOutputStream tOut = new ByteArrayOutputStream(scsz + n * 20);
                    tOut.write(buf, sub, offsEnd);
                    for (int i = 0; i < n; i++) {
                        int relEntryOff = dataLen + i * 16;
                        writeU32LE(tOut, relEntryOff);
                    }
                    if (estart > offsEnd) {
                        tOut.write(buf, sub + offsEnd, estart - offsEnd);
                    }
                    tOut.write(buf, sub + estart, dataLen);

                    for (int i = 0; i < n; i++) {
                        // ResTable_entry (8 bytes)
                        writeU16LE(tOut, 8);
                        writeU16LE(tOut, 0);
                        writeU32LE(tOut, origKeyStrCount + i);
                        // Res_value (8 bytes)
                        writeU16LE(tOut, 8);
                        tOut.write(0);    // res0
                        tOut.write(0x03); // TYPE_STRING
                        writeU32LE(tOut, origGlobalStrCount + i);
                    }

                    byte[] chunk = tOut.toByteArray();
                    writeU32(chunk, 4, chunk.length);
                    writeU32(chunk, 12, ec + n);
                    writeU32(chunk, 16, estart + n * 4);
                    pkgBody.write(chunk);
                } else {
                    // 非默认配置 Type: 插入 n 个 NO_ENTRY (0xFFFFFFFF)
                    ByteArrayOutputStream tOut = new ByteArrayOutputStream(scsz + n * 4);
                    tOut.write(buf, sub, offsEnd);
                    for (int i = 0; i < n; i++) {
                        writeU32LE(tOut, 0xFFFFFFFF);
                    }
                    if (estart > offsEnd) {
                        tOut.write(buf, sub + offsEnd, estart - offsEnd);
                    }
                    tOut.write(buf, sub + estart, dataLen);

                    byte[] chunk = tOut.toByteArray();
                    writeU32(chunk, 4, chunk.length);
                    writeU32(chunk, 12, ec + n);
                    writeU32(chunk, 16, estart + n * 4);
                    pkgBody.write(chunk);
                }
            } else {
                pkgBody.write(buf, sub, scsz);
            }
            sub += scsz;
        }

        byte[] patchedPkg = pkgBody.toByteArray();
        writeU32(patchedPkg, 4, patchedPkg.length);
        writeU32(patchedPkg, 268, newTypeStringsRel);
        writeU32(patchedPkg, 276, newKeyStringsRel);
        if (lastPublicKey > 0) {
            writeU32(patchedPkg, 280, origKeyStrCount + n);
        }

        // 拼接最终完整 resources.arsc
        ByteArrayOutputStream finalOut = new ByteArrayOutputStream(buf.length + 1024);
        finalOut.write(buf, 0, tableHeaderSize);
        finalOut.write(patchedGlobalPool);

        pos = globalPoolOff + origGlobalPoolSize;
        while (pos < origTableSize) {
            int csize = readU32(buf, pos + 4);
            if (pos == targetPkgOff) {
                finalOut.write(patchedPkg);
            } else {
                finalOut.write(buf, pos, csize);
            }
            pos += csize;
        }

        byte[] result = finalOut.toByteArray();
        writeU32(result, 4, result.length);
        return result;
    }

    private static byte[] appendStringsToPool(byte[] buf, int poolOff, int origChunkSize, List<String> extra) throws IOException {
        int hsz = readU16(buf, poolOff + 2);
        int origStrCount = readU32(buf, poolOff + 8);
        int flags = readU32(buf, poolOff + 16);
        int origStringsStart = readU32(buf, poolOff + 20);
        int origStylesStart = readU32(buf, poolOff + 24);
        boolean isUtf8 = (flags & 0x100) != 0;

        int n = extra.size();
        int origStrDataEnd = (origStylesStart != 0) ? origStylesStart : origChunkSize;
        int origStrDataLen = origStrDataEnd - origStringsStart;

        ByteArrayOutputStream newStrBytes = new ByteArrayOutputStream();
        int[] newRelOffsets = new int[n];
        for (int i = 0; i < n; i++) {
            newRelOffsets[i] = origStrDataLen + newStrBytes.size();
            writePoolString(newStrBytes, extra.get(i), isUtf8);
        }
        while ((newStrBytes.size() & 3) != 0) {
            newStrBytes.write(0);
        }
        byte[] addedStrBytes = newStrBytes.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream(origChunkSize + n * 4 + addedStrBytes.length);
        // 1. Header + 原 stringOffsets
        out.write(buf, poolOff, hsz + origStrCount * 4);
        // 2. 新增 stringOffsets
        for (int i = 0; i < n; i++) {
            writeU32LE(out, newRelOffsets[i]);
        }
        // 3. styleOffsets (若有) + 与字符串区之间的空隙
        int afterStrOffsets = hsz + origStrCount * 4;
        if (origStringsStart > afterStrOffsets) {
            out.write(buf, poolOff + afterStrOffsets, origStringsStart - afterStrOffsets);
        }
        // 4. 原字符串数据区 + 新增字符串数据区
        out.write(buf, poolOff + origStringsStart, origStrDataLen);
        out.write(addedStrBytes);
        // 5. 原 styles 数据区（原封不动）
        if (origStylesStart != 0 && origChunkSize > origStylesStart) {
            out.write(buf, poolOff + origStylesStart, origChunkSize - origStylesStart);
        }

        byte[] res = out.toByteArray();
        writeU32(res, 4, res.length);
        writeU32(res, 8, origStrCount + n);
        writeU32(res, 16, flags & ~1); // 清除 SORTED_FLAG 保证线性安全查找
        writeU32(res, 20, origStringsStart + n * 4);
        if (origStylesStart != 0) {
            writeU32(res, 24, origStylesStart + n * 4 + addedStrBytes.length);
        }
        return res;
    }

    private static void writePoolString(ByteArrayOutputStream out, String s, boolean utf8) throws IOException {
        if (utf8) {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            writeUtf8Len(out, s.length());
            writeUtf8Len(out, bytes.length);
            out.write(bytes);
            out.write(0);
        } else {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_16LE);
            writeU16Len(out, s.length());
            out.write(bytes);
            out.write(0);
            out.write(0);
        }
    }

    private static void writeUtf8Len(ByteArrayOutputStream out, int len) {
        if (len >= 0x80) {
            out.write(((len >> 8) & 0x7F) | 0x80);
            out.write(len & 0xFF);
        } else {
            out.write(len);
        }
    }

    private static void writeU16Len(ByteArrayOutputStream out, int len) {
        if (len >= 0x8000) {
            int hi = ((len >> 16) & 0x7FFF) | 0x8000;
            out.write(hi & 0xFF);
            out.write((hi >> 8) & 0xFF);
            out.write(len & 0xFF);
            out.write((len >> 8) & 0xFF);
        } else {
            out.write(len & 0xFF);
            out.write((len >> 8) & 0xFF);
        }
    }

    private static List<String> readPoolStrings(byte[] buf, int poolOff) {
        int hsz = readU16(buf, poolOff + 2);
        int count = readU32(buf, poolOff + 8);
        int flags = readU32(buf, poolOff + 16);
        int stringsStart = readU32(buf, poolOff + 20);
        boolean utf8 = (flags & 0x100) != 0;
        int base = poolOff + stringsStart;
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int p = base + readU32(buf, poolOff + hsz + i * 4);
            if (utf8) {
                p += ((buf[p] & 0x80) != 0) ? 2 : 1;
                int bLen;
                if ((buf[p] & 0x80) != 0) {
                    bLen = ((buf[p] & 0x7F) << 8) | (buf[p + 1] & 0xFF);
                    p += 2;
                } else {
                    bLen = buf[p] & 0xFF;
                    p += 1;
                }
                list.add(new String(buf, p, bLen, StandardCharsets.UTF_8));
            } else {
                int cLen = readU16(buf, p);
                p += 2;
                if ((cLen & 0x8000) != 0) {
                    cLen = ((cLen & 0x7FFF) << 16) | readU16(buf, p);
                    p += 2;
                }
                list.add(new String(buf, p, cLen * 2, StandardCharsets.UTF_16LE));
            }
        }
        return list;
    }

    private static boolean isDefaultEmptyConfig(byte[] buf, int cfgOff, int cfgLen) {
        if (cfgLen <= 4) return true;
        for (int i = 4; i < cfgLen; i++) {
            if (buf[cfgOff + i] != 0) return false;
        }
        return true;
    }

    private static byte[] patchArscWithArscLib(byte[] origArscBytes, List<IconSpec> specs) throws IOException {
        TableBlock tableBlock = TableBlock.load(new ByteArrayInputStream(origArscBytes));
        PackageBlock pkg = findMainPackage(tableBlock);
        if (pkg == null) {
            throw new IllegalStateException("未在 resources.arsc 中找到主资源包 (0x7f)");
        }
        for (IconSpec spec : specs) {
            Entry entry = pkg.getOrCreate("", "drawable", spec.entryName);
            entry.setValueAsString(spec.resPath);
            spec.resId = entry.getResourceId();
        }
        tableBlock.refresh();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(origArscBytes.length + 1024);
        tableBlock.writeBytes(bos);
        return bos.toByteArray();
    }

    private static PackageBlock findMainPackage(TableBlock tableBlock) {
        PackageBlock first = null;
        for (PackageBlock pb : tableBlock.listPackages()) {
            if (first == null) first = pb;
            if (pb.getId() == 0x7f) return pb;
        }
        return first;
    }

    // =========================================================================
    // AndroidManifest.xml 修补逻辑
    // =========================================================================
    private static void patchManifest(AndroidManifestBlock manifest,
                                      List<IconSpec> iconSpecs,
                                      List<NameSpec> nameSpecs,
                                      List<AliasSpec> outAliases) {
        ResXmlElement app = manifest.getApplicationElement();
        if (app == null) {
            throw new IllegalStateException("AndroidManifest.xml 缺少 <application> 节点");
        }

        int appIconResId = readReferenceAttr(app, "icon", ATTR_ICON);
        int appLabelResId = readReferenceAttr(app, "label", ATTR_LABEL);

        String targetActivityName = TARGET_ACTIVITY;
        int defaultIconResId = appIconResId;
        int defaultLabelResId = appLabelResId;

        // 定位 SplashActivity，摘除其 LAUNCHER intent-filter（保留其本体启用状态与 MAINACTIVITY filter）
        ResXmlElement splashActivity = null;
        for (ResXmlElement act : app.listElements("activity")) {
            ResXmlAttribute nameAttr = act.searchAttributeByName("name");
            if (nameAttr != null && TARGET_ACTIVITY.equals(nameAttr.getValueAsString())) {
                splashActivity = act;
                break;
            }
        }

        if (splashActivity != null) {
            int actIcon = readReferenceAttr(splashActivity, "icon", ATTR_ICON);
            int actLabel = readReferenceAttr(splashActivity, "label", ATTR_LABEL);
            if (actIcon != 0) defaultIconResId = actIcon;
            if (actLabel != 0) defaultLabelResId = actLabel;

            List<ResXmlElement> filters = new ArrayList<>(splashActivity.listElements("intent-filter"));
            for (ResXmlElement filter : filters) {
                if (hasLauncherCategory(filter)) {
                    filter.removeSelf();
                }
            }
        }

        if (defaultIconResId == 0) {
            throw new IllegalStateException("未解析到原包默认 icon 资源 ID");
        }

        // 先移除可能已存在的同名 Zzz 别名（防重入）
        List<ResXmlElement> existingAliases = new ArrayList<>(app.listElements("activity-alias"));
        for (ResXmlElement alias : existingAliases) {
            ResXmlAttribute nameAttr = alias.searchAttributeByName("name");
            if (nameAttr != null) {
                String val = nameAttr.getValueAsString();
                if (DEFAULT_ALIAS.equals(val) || (val != null && val.startsWith(CUSTOM_ALIAS_PREFIX))) {
                    alias.removeSelf();
                }
            }
        }

        // 构建 图标 × 名称 全组合别名列表
        for (IconSpec ic : iconSpecs) {
            for (NameSpec nm : nameSpecs) {
                AliasSpec as = new AliasSpec();
                as.iconId = ic.id;
                as.nameId = nm.id;
                as.iconResId = ic.isDefault ? defaultIconResId : ic.resId;
                as.customLabel = nm.isDefault ? null : nm.label;
                as.enabled = (ic.isDefault && nm.isDefault);

                if (ic.isDefault && nm.isDefault) {
                    as.aliasName = DEFAULT_ALIAS;
                } else if (!ic.isDefault && nm.isDefault) {
                    as.aliasName = CUSTOM_ALIAS_PREFIX + ic.id;
                } else if (ic.isDefault) {
                    as.aliasName = CUSTOM_ALIAS_PREFIX + "def_" + nm.id;
                } else {
                    as.aliasName = CUSTOM_ALIAS_PREFIX + ic.id + "_" + nm.id;
                }

                appendLauncherAlias(app, as.aliasName, targetActivityName, as.enabled, as.iconResId, defaultLabelResId, as.customLabel);
                outAliases.add(as);
            }
        }

        manifest.refresh();
    }

    private static boolean hasLauncherCategory(ResXmlElement filter) {
        for (ResXmlElement cat : filter.listElements("category")) {
            ResXmlAttribute nameAttr = cat.searchAttributeByName("name");
            if (nameAttr != null && "android.intent.category.LAUNCHER".equals(nameAttr.getValueAsString())) {
                return true;
            }
        }
        return false;
    }

    private static int readReferenceAttr(ResXmlElement el, String attrName, int attrId) {
        ResXmlAttribute attr = el.searchAttributeByResourceId(attrId);
        if (attr == null) attr = el.searchAttributeByName(attrName);
        if (attr != null && attr.getValueType() == ValueType.REFERENCE) {
            return attr.getData();
        }
        return 0;
    }

    private static void appendLauncherAlias(ResXmlElement app, String aliasName, String targetActivity,
                                            boolean enabled, int iconResId, int defaultLabelResId, String customLabel) {
        ResXmlElement alias = app.newElement();
        alias.setName("activity-alias");

        alias.getOrCreateAndroidAttribute("name", ATTR_NAME).setValueAsString(aliasName);
        alias.getOrCreateAndroidAttribute("targetActivity", ATTR_TARGET_ACTIVITY).setValueAsString(targetActivity);
        alias.getOrCreateAndroidAttribute("enabled", ATTR_ENABLED).setValueAsBoolean(enabled);
        alias.getOrCreateAndroidAttribute("exported", ATTR_EXPORTED).setValueAsBoolean(true);

        ResXmlAttribute iconAttr = alias.getOrCreateAndroidAttribute("icon", ATTR_ICON);
        iconAttr.setTypeAndData(ValueType.REFERENCE, iconResId);

        if (customLabel != null && !customLabel.isEmpty()) {
            // 直接写入非本地化字符串（nonLocalizedLabel），无需改动 resources.arsc
            alias.getOrCreateAndroidAttribute("label", ATTR_LABEL).setValueAsString(customLabel);
        } else if (defaultLabelResId != 0) {
            ResXmlAttribute labelAttr = alias.getOrCreateAndroidAttribute("label", ATTR_LABEL);
            labelAttr.setTypeAndData(ValueType.REFERENCE, defaultLabelResId);
        }

        ResXmlElement filter = alias.newElement();
        filter.setName("intent-filter");

        ResXmlElement action = filter.newElement();
        action.setName("action");
        action.getOrCreateAndroidAttribute("name", ATTR_NAME).setValueAsString("android.intent.action.MAIN");

        ResXmlElement category = filter.newElement();
        category.setName("category");
        category.getOrCreateAndroidAttribute("name", ATTR_NAME).setValueAsString("android.intent.category.LAUNCHER");
    }

    private static List<File> collectIconFiles(File dir) {
        List<File> result = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return result;
        File[] files = dir.listFiles();
        if (files == null) return result;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            if (name.startsWith(".")) continue;
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png") || lower.endsWith(".webp")) {
                result.add(f);
            }
        }
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    private static List<String> collectCustomNames(File namesFile) {
        List<String> names = new ArrayList<>();
        if (namesFile == null || !namesFile.isFile()) return names;
        Set<String> seen = new HashSet<>();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(namesFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if ("QQ".equals(line)) continue; // 默认名称已内置
                if (seen.add(line)) {
                    names.add(line);
                }
            }
        } catch (Throwable ignored) {}
        return names;
    }

    private static void writeIconsJson(File outFile, List<IconSpec> iconSpecs, List<NameSpec> nameSpecs, List<AliasSpec> aliasSpecs) throws IOException {
        outFile.getParentFile().mkdirs();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"default_alias\": \"").append(escapeJson(DEFAULT_ALIAS)).append("\",\n");

        sb.append("  \"icons\": [\n");
        for (int i = 0; i < iconSpecs.size(); i++) {
            IconSpec s = iconSpecs.get(i);
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(escapeJson(s.id)).append("\",\n");
            sb.append("      \"name\": \"").append(escapeJson(s.displayName)).append("\",\n");
            sb.append("      \"asset\": \"").append(escapeJson(s.assetRelPath != null ? s.assetRelPath : "")).append("\",\n");
            sb.append("      \"is_default\": ").append(s.isDefault).append("\n");
            sb.append("    }").append(i < iconSpecs.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"names\": [\n");
        for (int i = 0; i < nameSpecs.size(); i++) {
            NameSpec n = nameSpecs.get(i);
            sb.append("    {\n");
            sb.append("      \"id\": \"").append(escapeJson(n.id)).append("\",\n");
            sb.append("      \"name\": \"").append(escapeJson(n.label)).append("\",\n");
            sb.append("      \"is_default\": ").append(n.isDefault).append("\n");
            sb.append("    }").append(i < nameSpecs.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  ],\n");

        sb.append("  \"aliases\": {\n");
        for (int i = 0; i < aliasSpecs.size(); i++) {
            AliasSpec a = aliasSpecs.get(i);
            String key = a.iconId + "|" + a.nameId;
            sb.append("    \"").append(escapeJson(key)).append("\": \"").append(escapeJson(a.aliasName)).append("\"")
              .append(i < aliasSpecs.size() - 1 ? "," : "").append("\n");
        }
        sb.append("  }\n");

        sb.append("}\n");

        try (Writer w = new OutputStreamWriter(new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    private static String shortHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xFF));
            }
            return sb.toString();
        } catch (Throwable t) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void copyFile(File src, File dst) throws IOException {
        Files.copy(src.toPath(), dst.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) bos.write(b, 0, n);
            return bos.toByteArray();
        }
    }

    private static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static void writeU32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >> 8) & 0xFF);
        b[off + 2] = (byte) ((v >> 16) & 0xFF);
        b[off + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void writeU16LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeU32LE(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }
}