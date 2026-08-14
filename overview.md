# Code Review：当前改动相对 origin/fongmi 的完整审计

> 审计时间：2026-08-05 22:07
> 分支：fongmi（领先 origin/fongmi 2 个 commit）+ 大量未提交工作树修改

## 核心发现：third_party/maven 是"假 fork"

commit `a81fba418` 提交了 425 个文件（~30MB）的 media3 构件，版本号标为 `1.11.0-alpha01-fongmi`。
经反编译 aar 逐一验证，**6 项 fork 定制全部缺失**：

| fork 定制 | 验证方式 | 结果 |
|-----------|---------|------|
| DefaultRenderersFactory 扩展方法 (setFfmpegAudioPrefer 等 4 个) | javap | 缺失 |
| media3-mpvplayer 模块 (MpvPlayer) | 目录检查 | 整模块缺失 |
| PlayerView debug 方法 (toggleDebugView/hideDebugView) | javap | 缺失 |
| PlayerSeekView | javap | 缺失 |
| DiskPreloadManager | javap | 缺失 |
| AudioTrackAudioOutputProvider | javap | 缺失 |

**结论**：实质是官方 media3 构件改了版本号。上游 FongMi 源码强依赖这些定制，
工作树被迫裁剪 ExoUtil / PreCache / PlaybackActivity / PlayerEngineFactory + 删除 MpvPlayerEngine / MpvUtil（共 -369 行）才能编译通过。

## 改动五层分类

### 第 1 层 · 根因（已提交，红色）
- commit a81fba418：假 fork 构件 425 文件 + build.gradle/libs.versions.toml/settings.gradle 配置
- **建议**：用真正的完整 fork 产物替换（含 mpvplayer + 定制类），或放弃 fork 版本号直接用官方

### 第 2 层 · 连带（未提交，橙色）— 根因的后果
- ExoUtil / PreCache / PlaybackActivity / PlayerEngineFactory 裁剪 fork API
- 删除 MpvPlayerEngine.java / MpvUtil.java（-223 行）
- PyLoader 退化为 SpiderNull（chaquo 移除）
- **建议**：补全 fork 后这 6 个文件全部还原为上游原样（0 diff），冲突面归零

### 第 3 层 · 硬需求（已提交+未提交，蓝色）
- minSdk 24→23（MiTV4A Android 6.0）
- chaquo 移除（要求 minSdk 24）
- AndroidManifest overrideLibrary
- desugar 需统一为 nio（commit 4c1189e 改成 full 有误，工作树改回 nio 正确）
- **建议**：minSdk + chaquo + desugar + overrideLibrary 集中为 1 个 commit

### 第 4 层 · 保留（未提交，绿色）— 质量好，冲突小
- CrashGuard 崩溃守卫（新增文件 + VodConfig/HomeActivity 2 处 hook）
- 推荐源（RecommendDialog/Adapter + SettingActivity 2 处 hook）
- 包名伪装 App.getCatContext（绕过 spider 反篡改自杀）
- applicationIdSuffix .test（测试包并存）
- exclude rtmp-client（重复类修复）
- **建议**：各自独立 commit，hook 点尽量少改上游文件

### 第 5 层 · 清理（未提交，灰色）
- png→webp 替换（30+ 文件）+ wallpaper 压缩
- 55 个 tmp_*.py/xml + 12 个 build_*.log / hs_err / probe + 根目录残留
- **建议**：资源优化剥离到独立分支；临时文件删除 + .gitignore

## 精简路线（优先级排序）

1. **优先**：补全完整 fork 构件 → 第 2 层 -369 行裁剪全部消失，触碰上游文件从 ~20 降至 ~8
2. **必做**：清理临时文件 + .gitignore；desugar 统一为 nio
3. **保留**：CrashGuard、推荐源、包名伪装、构建兼容修复，各自独立 commit
4. **剥离**：资源 png→webp 优化移到独立分支（与功能无关，冲突面大）

## desugar 反复问题
- commit 4c1189e45 把 desugar_jdk_libs_nio 改成 desugar_jdk_libs（full）
- 但本地 AAR（hook/forcetech/thunder）要求 nio flavor → 构建失败
- 工作树改回 nio 正确，需修正 commit 或直接提交工作树的 nio 修正
