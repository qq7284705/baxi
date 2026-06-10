# 群红包助手 · 息屏抢红包（LSPosed 模块）

微信群红包自动抢取的 Xposed/LSPosed 模块。核心思路是 **协议层（NetScene）** 而非 UI 点击，
所以 **息屏（锁屏 / 屏幕熄灭）也能抢**。

> 仅作用于 `com.tencent.mm`，自动化的是你自己设备上的微信。请遵守微信使用条款，自担账号风险。

## 工作原理

1. **息屏感知红包**：Hook 微信 WCDB 数据库的 `SQLiteDatabase.insertWithOnConflict`，
   拦截写入 `message` 表的每条新消息。红包到达时数据库一定会插入记录（`type=436207665`），
   这与 UI 是否渲染无关，因此息屏同样触发。
2. **保活**：在微信进程内持有 `PARTIAL_WAKE_LOCK`，保证息屏后 CPU 能即时处理。
3. **解析**：从红包消息的 `<appmsg><wcpayinfo>` XML 与 `nativeurl`（`...receivewxhb?...`）
   提取 `sendId / channelId / msgType / ver / sign / sceneId`。
4. **抢**：用 DexKit 定位微信混淆后的 `NetSceneQueue.doScene` 以及
   `NetSceneReceiveLuckyMoney`（领取）/ `NetSceneOpenLuckyMoney`（拆开）类，
   投递「领取」场景 → 回调成功 → 投递「拆开」场景。

只抢群红包（`talker` 以 `@chatroom` 结尾），不抢自己发的红包。

## 运行环境

- 已 **root** 且安装 **LSPosed / EdXposed**（`xposedminversion=82`）的设备
- 目标微信版本：**8.0.40**（其它版本可能需要重新校准，见下）
- minSdk 26 / targetSdk 34

## 安装

1. 编译或下载 `app-debug.apk`，安装到手机。
2. 打开 **LSPosed** → 模块 → 启用「群红包助手」→ 勾选作用域 **微信(com.tencent.mm)**。
3. **强制停止并重启微信**，使模块注入生效。
4. （可选）打开本 App 配置：开关、仅群红包、随机延迟（防风控）。

## 构建

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

## 校准（不同微信版本 / 抢不到时）

模块启动会把关键定位结果打到日志，真机执行：

```bash
adb logcat | grep Hongbao
```

重点关注：

- `WCDB hook OK ...`：数据库 Hook 是否生效（应能看到）。
- `检测到群红包 ... sendId=...`：是否成功感知红包（验证「息屏感知」这步）。
- `ReceiveLuckyMoney matched ... -> <类名>` 与 `ReceiveLuckyMoney.ctor(...)`：
  领取/拆开场景类及其 **构造函数签名**。若打印为 `NOT found`，需要调整
  `WxClassResolver.resolveLuckyMoneyScenes` 里的字符串锚点。
- `receive ctor(...) args=...` / `enqueue receive scene ok=...`：实际投递情况。

把这段日志发回来，即可据 **8.0.40 的真实构造函数** 固化领取/拆开的参数映射
（当前 `LuckyMoneyGrabber.fillArgs` 为启发式填充，作为校准前的最佳猜测）。

## 代码结构

```
app/src/main/java/com/xiaobai/hongbao/
├── hook/MainHook.kt           # Xposed 入口：进程过滤、WCDB Hook、WakeLock、红包分发
├── hook/RedPacket.kt          # 红包数据模型 + XML/nativeurl 解析
├── hook/WxClassResolver.kt    # DexKit 定位 NetSceneQueue/doScene 与领取/拆开场景类
├── hook/LuckyMoneyGrabber.kt  # 领取→拆开 编排（含场景回调 hook、去重、随机延迟）
├── hook/Config.kt             # XSharedPreferences 配置读取
├── MainActivity.kt            # 配置界面
└── service/                   # 前台保活服务 + 开机自启
```
