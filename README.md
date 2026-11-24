# Aegis Browser

超轻量、隐私优先的 Android 浏览器。零账号、零遥测、零后台服务，默认即隐身。

## 隐私原则

- **零三方依赖**：仅使用 Android 系统 framework，不引入任何第三方库
- **最小权限**：仅 `INTERNET`、`VIBRATE`、`CAMERA`（仅扫码）与 `REQUEST_INSTALL_PACKAGES`（安装更新包），不申请存储、定位、通讯录与短信
- **默认拒绝第三方 Cookie**：第一方 Cookie 可用，跨站追踪默认阻断
- **不备份、不上报**：`allowBackup=false`，无遥测、无崩溃上报、无推送
- **无后台行为**：不运行 Service、不做定时任务，规则更新仅由用户手动触发

## 技术栈

| 项 | 版本 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| 构建 | Gradle 8.9 + AGP 8.7.2 |
| UI | 传统 View 系统（无 AndroidX、无 Compose） |
| 内核 | 系统 WebView |
| compileSdk / targetSdk | 35 |
| minSdk | 26（Android 8.0） |
| JDK | 17 |

仓库已配置阿里云与腾讯云镜像，国内网络可直接同步依赖。

## 目录结构

```
.
├── app/                    # 应用模块：MainActivity、地址栏、导航与标签 UI
│   └── src/main/
│       ├── java/dev/aegis/browser/MainActivity.kt
│       └── res/            # 布局、主题、vector 图标、自适应启动图标
├── core-browser/           # 内核模块（Android Library，零三方依赖）
│   └── src/main/java/dev/aegis/browser/core/
│       ├── web/            # PrivacyWebView、WebView/Chrome 客户端、拦截接口
│       └── tab/            # TabManager 多标签池与 BrowserTab
├── feature-search/         # 搜索引擎模块：12 个内置引擎、自定义引擎、搜索建议
├── feature-privacy/        # 拦截引擎：规则匹配、白名单、JS 注入、规则更新
│   └── src/main/assets/filters/default.filter   # 内置规则（每行一个域名）
├── gradle/
│   ├── libs.versions.toml  # Version Catalog
│   └── wrapper/            # Gradle Wrapper
├── settings.gradle.kts
└── build.gradle.kts
```

## 环境要求

- JDK 17
- Android SDK Platform 35
- Android Studio（Ladybug 或更新版本），或命令行 SDK Manager

## 构建

用 Android Studio 打开项目根目录，等待 Gradle 同步后直接 Run。

命令行构建：

```bash
# Debug APK
./gradlew :app:assembleDebug

# Release APK（需配置签名，见下）
./gradlew :app:assembleRelease
```

产物路径：`app/build/outputs/apk/`。

Release 签名通过环境变量 `AEGIS_KEYSTORE_PATH`、`AEGIS_KEYSTORE_PASSWORD`、`AEGIS_KEY_ALIAS`、`AEGIS_KEY_PASSWORD` 注入；本地也可在仓库根目录创建 `keystore.properties`（`storeFile`、`storePassword`、`keyAlias`、`keyPassword`、`storeType=PKCS12`），该文件已被 `.gitignore` 排除。

## M1 已完成（浏览内核）

- PrivacyWebView 隐私安全封装（第三方 Cookie 阻断、file:// 关闭等）
- 地址栏：URL 与搜索词自动识别，非 URL 文本走默认搜索引擎
- 前进 / 后退 / 刷新，系统返回键优先回退页面历史
- 多标签：独立 WebView 池，visibility 切换，标签切换对话框与角标计数
- 加载进度条、外部链接（VIEW intent）打开、自适应启动图标

## M2 已完成（搜索引擎管理）

- 12 个内置引擎（Bing 中国默认、百度、搜狗、360、神马、Google、DuckDuckGo、Brave 等）
- 自定义引擎：名称 + 含 `%s` 的 URL 模板，带格式校验，可删除
- 搜索建议默认关闭，300ms 防抖，过期结果序号防护
- 全部设置持久化于 SharedPreferences

## M3 已完成（拦截引擎）

- 双层拦截：`shouldInterceptRequest` 网络层返回空响应（请求不离开设备）+ 页面启动时 JS 注入隐藏广告容器
- 内置规则 93,766 个域名，合并自 yoyo、EasyPrivacy、EasyList，仅提取纯域名级规则（带路径/通配的复杂规则不做降级）
- 域名精确匹配 + 逐级回退父域，规则命中自身及所有子域
- 站点白名单（一键开关本站拦截）、累计拦截计数、规则数量展示
- 规则手动更新：下载 StevenBlack 统一 hosts 并本地解析，可恢复内置规则
- 规则在工作线程异步加载，加载完成前不拦截；引擎引用整体替换，线程安全

## M4 已完成（隐私数据层）

- 退出自动清除：开关默认关闭，开启后退出时清除 Cookie、缓存、历史、表单与网页存储，不影响规则与引擎设置
- 立即清除：隐私设置页可手动清除全部浏览数据（带确认）
- HTTPS-Only：自动将 http 导航升级为 https；站点不支持时弹窗询问，确认后才以 http 继续；豁免仅本次会话有效
- 证书错误一律取消，不降级
- 按站点 JavaScript 权限：默认允许，可添加禁用 JS 的域名（带域名校验），在文档脚本执行前生效

## M5 已完成（体验完善）

- 下载管理：无论直链还是 blob/data 按钮，点击下载后自动打开下载管理页，实时显示进度（百分比/大小），所有下载统一记录。自研 HttpURLConnection 下载器手动跟随重定向，下载管理页集中查看进行中任务与历史文件；短按打开文件，长按弹出操作菜单（打开、所在位置、删除），可在系统文件管理器中定位文件；支持批量多选删除，单个与批量删除都会一并清理本地文件；blob/data 经 JS 桥接处理。全程不增加存储权限，API 29+ 经 MediaStore 存公共 Downloads，低版本存应用私有外部目录
- 深色模式：浅色 / 深色 / 跟随系统三态，切换即时生效；API 33+ 网页内容随系统算法变暗
- 书签：内置 org.json 持久化，菜单一键添加，书签页点击打开、长按删除，按 URL 去重
- URL 跟踪参数剥离：主框架导航自动移除 utm_*、gclid、fbclid、spm 等常见跟踪参数，保留原始编码，默认开启可关闭

## M6 已完成（隐私验证与发布）

- 反指纹基线：`navigator` 平台、厂商、硬件参数与真实 UA 自洽，隐私设置可关闭
- Referrer 收紧：页面级注入 `strict-origin-when-cross-origin`，跨站请求只带源
- 拦截记录页：最近 200 条命中域名、来源页面与时间，误伤可一键加入白名单
- 无痕零落盘：不写入最后访问地址、不保存标签会话，退出与销毁时统一清理
- 自动发布：推送 `v*` 标签触发 `Release APK` 工作流，使用正式签名密钥编译并创建 GitHub Release
- 五项验收清单见 [`docs/M6-隐私自检.md`](docs/M6-隐私自检.md)

## 应用内检查更新

设置 → 实用工具与关于 → 关于 Aegis → 「检查更新」：读取 GitHub Releases 最新版本并逐段比较；接口限流时自动回退到 `releases.atom`；发现新版本可直接前往 Release 下载。

## 后续计划

- 体验与性能持续优化
- 更多隐私增强能力按需评估

## 许可证

[MIT License](LICENSE)
