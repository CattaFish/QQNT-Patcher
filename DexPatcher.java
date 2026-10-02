package com.tencent.qqnt.patcher;

import org.antlr.runtime.CommonTokenStream;
import org.antlr.runtime.tree.CommonTree;
import org.antlr.runtime.tree.CommonTreeNodeStream;
import org.jf.baksmali.Adaptors.ClassDefinition;
import org.jf.baksmali.BaksmaliOptions;
import org.jf.baksmali.formatter.BaksmaliWriter;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.writer.builder.DexBuilder;
import org.jf.dexlib2.writer.io.FileDataStore;
import org.jf.dexlib2.writer.io.MemoryDataStore;
import org.jf.dexlib2.writer.pool.DexPool;
import org.jf.smali.smaliFlexLexer;
import org.jf.smali.smaliParser;
import org.jf.smali.smaliTreeWalker;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DexPatcher {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("用法: java DexPatcher <batch_config.txt>");
            System.exit(1);
        }

        String batchConfigFile = args[0];
        try {
            long t0 = System.currentTimeMillis();
            List<DexTask> tasks = loadBatchTasks(batchConfigFile);
            Opcodes opcodes = Opcodes.forApi(26);

            int totalTasks = tasks.size();
            int availableCores = Runtime.getRuntime().availableProcessors();
            int threadCount = Math.max(1, Math.min(totalTasks, availableCores));

            System.out.println("[DexPatcher] 启动纯内存 AST 编译引擎 (待处理分包: " + totalTasks + ", 并发线程: " + threadCount + ")");
            System.out.flush();

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<?>> futures = new ArrayList<>();
            AtomicInteger completedCounter = new AtomicInteger(0);

            for (DexTask task : tasks) {
                futures.add(executor.submit(() -> {
                    String dexName = new File(task.dexIn).getName();
                    try {
                        System.out.println("[DexPatcher] 正在处理: " + dexName + " (装载 " + task.rules.size() + " 条规则)...");
                        System.out.flush();

                        long tTask = System.currentTimeMillis();
                        patchSingleDex(task, opcodes);

                        int cur = completedCounter.incrementAndGet();
                        System.out.println("[DexPatcher] [" + cur + "/" + totalTasks + "] " + dexName + " 重构完成，耗时: " + (System.currentTimeMillis() - tTask) + "ms");
                        System.out.flush();
                    } catch (Exception e) {
                        System.err.println("[ERROR] 处理 " + task.dexIn + " 致命异常: " + e.getMessage());
                        new File(task.dexOut).delete();
                        throw new RuntimeException("Patch failed for " + task.dexIn, e);
                    }
                }));
            }

            boolean hasError = false;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    System.err.println("[ERROR] 任务执行失败: " + e.getMessage());
                    hasError = true;
                }
            }
            executor.shutdown();

            if (hasError) {
                System.err.println("[ERROR] 存在构建失败的分包，终止构建以防污染缓存！");
                System.exit(1);
            }

            System.out.println("[DexPatcher] 全部分包重构耗时: " + (System.currentTimeMillis() - t0) + "ms");
            System.out.flush();
        } catch (Throwable t) {
            System.err.println("[ERROR] 引擎主流程捕获异常: " + t.getMessage());
        }
    }

    private static void patchSingleDex(DexTask task, Opcodes opcodes) throws Exception {
        if (task.rules.isEmpty()) {
            copyFile(new File(task.dexIn), new File(task.dexOut));
            return;
        }

        DexBackedDexFile dexFile;
        try (InputStream is = new BufferedInputStream(new FileInputStream(task.dexIn))) {
            dexFile = DexBackedDexFile.fromInputStream(opcodes, is);
        }

        Map<String, List<PatchRule>> ruleMap = new HashMap<>();
        for (PatchRule r : task.rules) {
            ruleMap.computeIfAbsent(r.targetClass, k -> new ArrayList<>()).add(r);
        }

        BaksmaliOptions baksmaliOptions = new BaksmaliOptions();
        Map<String, ClassDef> replacedClasses = new HashMap<>();

        for (ClassDef classDef : dexFile.getClasses()) {
            String clsType = classDef.getType();
            List<PatchRule> matchedRules = ruleMap.get(clsType);

            if (matchedRules != null && !matchedRules.isEmpty()) {
                try {
                    String smaliCode = disassembleClass(classDef, baksmaliOptions);
                    smaliCode = smaliCode.replace("\r\n", "\n");
                    for (PatchRule r : matchedRules) {
                        smaliCode = applyRule(smaliCode, r);
                    }
                    
                    // 纯内存即时汇编单个 ClassDef，彻底摆脱临时文件
                    ClassDef newClassDef = assembleSingleClassInMemory(smaliCode, opcodes, clsType);
                    if (newClassDef != null) {
                        replacedClasses.put(clsType, newClassDef);
                    } else {
                        System.err.println("[WARN] 类汇编未产生结果，保留原类: " + clsType);
                    }
                } catch (Throwable t) {
                    System.err.println("[WARN] 修补类 " + clsType + " 异常，保留原类: " + t.getMessage());
                }
            }
        }

        DexPool dexPool = new DexPool(opcodes);
        for (ClassDef classDef : dexFile.getClasses()) {
            if (replacedClasses.containsKey(classDef.getType())) {
                dexPool.internClass(replacedClasses.get(classDef.getType()));
            } else {
                dexPool.internClass(classDef);
            }
        }

        File outFile = new File(task.dexOut);
        if (outFile.exists()) outFile.delete();
        dexPool.writeTo(new FileDataStore(outFile));
    }

    private static String disassembleClass(ClassDef classDef, BaksmaliOptions options) throws Exception {
        StringWriter sw = new StringWriter();
        BaksmaliWriter bw = new BaksmaliWriter(sw, null);
        ClassDefinition cd = new ClassDefinition(options, classDef);
        cd.writeTo(bw);
        bw.close();
        return sw.toString();
    }

    private static ClassDef assembleSingleClassInMemory(String smaliCode, Opcodes opcodes, String classType) {
        try {
            StringReader reader = new StringReader(smaliCode);
            smaliFlexLexer lexer = new smaliFlexLexer(reader, opcodes.api);
            
            String virtualFileName = (classType != null) ? classType.replaceAll("[L;]", "").replace('/', '.') + ".smali" : "inline.smali";
            lexer.setSourceFile(new File(virtualFileName));

            CommonTokenStream tokens = new CommonTokenStream(lexer);
            smaliParser parser = new smaliParser(tokens);
            parser.setApiLevel(opcodes.api);
            parser.setVerboseErrors(false);

            smaliParser.smali_file_return result = parser.smali_file();
            if (parser.getNumberOfSyntaxErrors() > 0 || lexer.getNumberOfSyntaxErrors() > 0) {
                System.err.println("[WARN] Smali 语法解析错误 [" + classType + "]: 发现 " + parser.getNumberOfSyntaxErrors() + " 处语法错误");
                return null;
            }

            CommonTree tree = (CommonTree) result.getTree();
            CommonTreeNodeStream treeStream = new CommonTreeNodeStream(tree);
            treeStream.setTokenStream(tokens);

            DexBuilder dexBuilder = new DexBuilder(opcodes);
            smaliTreeWalker dexGen = new smaliTreeWalker(treeStream);
            dexGen.setDexBuilder(dexBuilder);
            dexGen.setApiLevel(opcodes.api);
            dexGen.setVerboseErrors(false);
            dexGen.smali_file();

            if (dexGen.getNumberOfSyntaxErrors() > 0) {
                System.err.println("[WARN] Smali 语义生成错误 [" + classType + "]");
                return null;
            }

            MemoryDataStore memoryStore = new MemoryDataStore();
            dexBuilder.writeTo(memoryStore);

            DexBackedDexFile singleDex = new DexBackedDexFile(opcodes, memoryStore.getData());
            Set<? extends ClassDef> classes = singleDex.getClasses();
            return classes.isEmpty() ? null : classes.iterator().next();

        } catch (Throwable t) {
            System.err.println("[WARN] 内存汇编类失败 [" + classType + "]: " + t.getMessage());
            return null;
        }
    }

    private static int calculateMaxRegisterIndex(String smaliSnippet) {
        int maxIndex = -1;
        Matcher m = Pattern.compile("(?<![\\w$])v(\\d+)(?![\\w$])").matcher(smaliSnippet);
        while (m.find()) {
            try {
                int idx = Integer.parseInt(m.group(1));
                if (idx > maxIndex) maxIndex = idx;
            } catch (Throwable ignored) {}
        }
        return maxIndex;
    }

    private static String applyRule(String code, PatchRule rule) {
        String methodName = rule.targetMethod;
        Pattern pattern;

        if (methodName.contains("(")) {
            String escaped = Pattern.quote(methodName);
            pattern = Pattern.compile("(\\.method[^\\n]*\\s+" + escaped + "\\s*?\\n.*?\\.end method)", Pattern.DOTALL);
        } else if ("<init>".equals(methodName)) {
            pattern = Pattern.compile("(\\.method[^\\n]*\\s+<init>\\([^\\n]*\\)\\w*?\\s*?\\n.*?\\.end method)", Pattern.DOTALL);
        } else {
            String escaped = Pattern.quote(methodName);
            pattern = Pattern.compile("(\\.method[^\\n]*\\s+" + escaped + "\\b.*?\\.end method)", Pattern.DOTALL);
        }

        Matcher methodMatcher = pattern.matcher(code);
        if (!methodMatcher.find()) {
            // 自动合成全新的静态代码块，容错目标类无 <clinit> 的情况
            if ("<clinit>()V".equals(rule.targetMethod) && "INSERT_BEFORE".equals(rule.type)) {
                StringBuilder sb = new StringBuilder();
                sb.append("\n# injected by KillerPatcher\n");
                sb.append(".method static constructor <clinit>()V\n");
                sb.append("    .registers 0\n\n");
                sb.append("    ").append(rule.smali.trim()).append("\n\n");
                sb.append("    return-void\n");
                sb.append(".end method\n");
                int idx = code.indexOf("\n.method ");
                if (idx < 0) idx = code.length();
                System.out.println("[DexPatcher] -> 目标类无静态代码块，已自动合成全新 <clinit>: " + rule.targetClass);
                return code.substring(0, idx) + sb.toString() + code.substring(idx);
            }
            System.err.println("[WARN] 未在类中定位到目标方法: " + rule.targetClass + "->" + rule.targetMethod);
            return code;
        }

        methodMatcher.reset();

        if ("REPLACE".equals(rule.type)) {
            return methodMatcher.replaceAll(Matcher.quoteReplacement(rule.smali));
        } else if ("INSERT_BEFORE".equals(rule.type)) {
            int requiredLocals = calculateMaxRegisterIndex(rule.smali) + 1;
            StringBuffer sb = new StringBuffer();
            while (methodMatcher.find()) {
                String mBody = methodMatcher.group(1);

                Matcher localsMatcher = Pattern.compile("(\\.locals\\s+)(\\d+)").matcher(mBody);
                if (localsMatcher.find()) {
                    int curLocals = Integer.parseInt(localsMatcher.group(2));
                    if (curLocals < requiredLocals) {
                        mBody = localsMatcher.replaceFirst("$1" + requiredLocals);
                    }
                } else {
                    Matcher regMatcher = Pattern.compile("(\\.registers\\s+)(\\d+)").matcher(mBody);
                    if (regMatcher.find()) {
                        int curRegs = Integer.parseInt(regMatcher.group(2));
                        if (curRegs < requiredLocals) {
                            mBody = regMatcher.replaceFirst("$1" + requiredLocals);
                        }
                    }
                }

                Matcher headerMatcher = Pattern.compile("(\\.registers\\s+\\d+|\\.locals\\s+\\d+)").matcher(mBody);
                if (headerMatcher.find()) {
                    int idx = headerMatcher.end();
                    String newBody = mBody.substring(0, idx) + "\n" + rule.smali + "\n" + mBody.substring(idx);
                    methodMatcher.appendReplacement(sb, Matcher.quoteReplacement(newBody));
                } else {
                    methodMatcher.appendReplacement(sb, Matcher.quoteReplacement(mBody));
                }
            }
            methodMatcher.appendTail(sb);
            return sb.toString();
        } else if ("REGEX_REPLACE".equals(rule.type)) {
            StringBuffer sb = new StringBuffer();
            while (methodMatcher.find()) {
                String mBody = methodMatcher.group(1);
                String javaReplacement = rule.smali.replaceAll("\\\\(\\d+)", "\\$$1");

                Pattern rp = Pattern.compile(rule.regex);
                Matcher rm = rp.matcher(mBody);
                int hitCount = 0;
                while (rm.find()) hitCount++;

                if (hitCount > 0) {
                    System.out.println("[DexPatcher] -> 规则 [" + rule.targetMethod.split("\\(")[0] + "] 正则命中 " + hitCount + " 处，注入成功");
                } else {
                    System.err.println("[WARN] -> 规则 [" + rule.targetMethod.split("\\(")[0] + "] 正则命中 0 处，未发生替换! 正则: " + rule.regex);
                }

                String replacedBody = rp.matcher(mBody).replaceAll(javaReplacement);
                methodMatcher.appendReplacement(sb, Matcher.quoteReplacement(replacedBody));
            }
            methodMatcher.appendTail(sb);
            return sb.toString();
        }
        return code;
    }

    private static List<DexTask> loadBatchTasks(String configFile) throws Exception {
        List<DexTask> taskList = new ArrayList<>();
        File file = new File(configFile);
        if (!file.exists()) return taskList;

        String raw = new String(readFile(file), StandardCharsets.UTF_8);
        String[] dexBlocks = raw.split("===DEX_TASK_SPLIT===");

        for (String dBlock : dexBlocks) {
            if (dBlock.trim().isEmpty()) continue;
            DexTask task = new DexTask();
            String[] ruleBlocks = dBlock.trim().split("===RULE_SPLIT===");

            for (int i = 0; i < ruleBlocks.length; i++) {
                String rBlock = ruleBlocks[i].trim();
                if (rBlock.isEmpty()) continue;

                if (i == 0) {
                    String[] lines = rBlock.split("\n");
                    for (String l : lines) {
                        if (l.startsWith("DEX_IN=")) task.dexIn = l.substring(7).trim();
                        else if (l.startsWith("DEX_OUT=")) task.dexOut = l.substring(8).trim();
                    }
                } else {
                    PatchRule r = new PatchRule();
                    String[] lines = rBlock.split("\n");
                    StringBuilder smaliSb = new StringBuilder();
                    boolean readingSmali = false;

                    for (String l : lines) {
                        if (!readingSmali) {
                            if (l.startsWith("TARGET_CLASS=")) r.targetClass = l.substring(13).trim();
                            else if (l.startsWith("TARGET_METHOD=")) r.targetMethod = l.substring(14).trim();
                            else if (l.startsWith("TYPE=")) r.type = l.substring(5).trim();
                            else if (l.startsWith("REGEX=")) r.regex = l.substring(6).trim();
                            else if (l.equals("---SMALI_START---")) readingSmali = true;
                        } else {
                            if (l.equals("---SMALI_END---")) {
                                readingSmali = false;
                            } else {
                                smaliSb.append(l).append("\n");
                            }
                        }
                    }
                    r.smali = smaliSb.toString().trim();
                    if (r.targetClass != null && r.targetMethod != null) {
                        task.rules.add(r);
                    }
                }
            }
            if (task.dexIn != null && task.dexOut != null) {
                taskList.add(task);
            }
        }
        return taskList;
    }

    private static byte[] readFile(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    static class DexTask {
        String dexIn;
        String dexOut;
        List<PatchRule> rules = new ArrayList<>();
    }

    static class PatchRule {
        String targetClass;
        String targetMethod;
        String type;
        String regex;
        String smali;
    }
}
