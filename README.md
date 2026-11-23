# OpenHC · Android 6 无线适配版

把上游开源车机端 [GGBondLab/OpenHC](https://github.com/GGBondLab/OpenHC)（0.2.2）适配到 **Android 6.0～7.1**：
上游的无线投屏要求 Android 8.0+（因为 `WifiManager.startLocalOnlyHotspot` 是 API 26 才有的公开接口），
这个分支补了一条旧版隐藏接口的热点实现，让 6.0 车机也能无线投屏。

- 🏷️ **显示名**：启动器里叫 **Hi Carlink**；包名仍是 `io.github.gcbond.openhc`（改包名会断掉升级链）
- 🚗 **用途**：装在华为/荣耀手机的车机上，手机 HiCar 投屏到车机屏幕（与华为、比亚迪等公司无关）
- 📦 **安装包**：[Releases](https://github.com/CHAIOR/Hi-Carlink/releases) 里的 `Hi-Carlink-0.2.3-a6-android6.apk`（11.3 MB，`versionCode 203`，`versionName 0.2.3-a6`）
- 🧩 **改动补丁**：[`dist/android6-support.patch`](dist/android6-support.patch)（相对上游 0.2.2，共 5 个文件）

> 本仓库是**第三方适配分支**，不是上游官方仓库。上游作者只在比亚迪车机 + 纯血鸿蒙手机上验证过，
> 这里的 6.0 适配也只在源码层面完成，**需要在实车上验证**。

---


## Windows 电脑版（实验，`desktop/` 模块）

同一套协议实现（`core:session`）移植到 Windows：手机扫码 → 蓝牙 RFCOMM 握手与认证 → 电脑「移动热点」→ TCP 会话
→ H.264 解码上屏（JavaCV/ffmpeg）+ 鼠标触摸回传 + PCM 声音。

- 下载：**[Hi Carlink PC 0.2.0](https://github.com/CHAIOR/Hi-Carlink/releases/tag/v0.2.0-pc)**
  - `HiCarlinkPC-Setup-0.2.0.exe`：安装包（简体中文向导、可改安装位置、单窗口无控制台）
  - `HiCarlinkPC-win64-0.2.0.zip`：绿色版（解压双击 `HiCarlinkPC.bat`，自带 Java 运行时）
- 需要：Win10/11 x64、蓝牙和 Wi-Fi 都打开、电脑能上网（Windows 开热点要求有网络配置）
- 平台实现：`WinRfcomm`（JNA 调 Winsock `AF_BTH`，注意 `SOCKADDR_BTH` 是 30 字节 pack(1) 布局）、
  `WinHotspot`（PowerShell + WinRT `NetworkOperatorTetheringManager`）、`VideoWindow`（JavaCV 解码 + Swing + 触摸）
- 已知限制：AAC 声音未接（向手机声明 PCM）、鼠标只有单指、Windows 热点固定在 2.4 GHz

## 为什么上游不支持 6.0

无线连接这条路是：**车机开一个本地热点 → 手机连上来 → 走 TCP 传控制与视频**。

- `WifiManager.startLocalOnlyHotspot()` 是 **API 26（Android 8.0）** 才加入的公开接口；
- Android 6.0 上第三方应用没有任何公开 API 能创建热点、读回 SSID/口令；
- 上游为了稳，没有走反射调 `@hide` 接口的野路子，而是按版本直接关掉了无线入口。

硬件本身没问题：车机系统设置里能开“个人热点”，说明 Wi-Fi 芯片支持 AP 模式；缺的是系统给的权限/接口。

## 这个分支改了什么

| 文件 | 改动 |
|---|---|
| `platform/.../wifi/LegacyHotspot.kt` | **新增**。反射调用 `setWifiApEnabled` / `getWifiApConfiguration` / `getWifiApState`，开一个 WPA2 热点；优先沿用车机原有的热点配置，否则生成固定的 `OpenHC-xxxx` + 10 位口令并存进 SharedPreferences（手机按网络名认车机，名字不能每次变） |
| `platform/.../wifi/LocalHotspot.kt` | API 23～25 的申请走旧版路径；`readReservation()` / `closeQuietly()` 认识旧版热点；旧接口没有“热点被收回”回调，就绪后每 3 秒查一次状态 |
| `app/.../wireless/WirelessNetworkPlan.kt` | `HOTSPOT_MIN_SDK` 26 → 23（无线入口、开机静默、悬浮窗判断一起放开） |
| `app/build.gradle.kts` | 版本号 `0.2.3-a6` / `versionCode 203` |
| `app/src/test/.../WifiRestorePolicyTest.kt` | 用例同步：23 起可用 |

协议层、解码、USB、音频都没动，**有线连接行为与上游 0.2.2 完全一致**。

## 安装

1. **先卸载车机上原来的 OpenHC**（官方包与这里的自签名不同，不能覆盖安装）；
2. 车机允许安装未知来源 → 装 Release 里的 `Hi-Carlink-0.2.3-a6-android6.apk`；
3. 打开 → 设置 → 连接方式：`无线方式 = 车机开热点`（默认），关掉“只用数据线连接”；
4. 首页 → 无线连接 → 开始 → 手机扫二维码；
5. 手机 HiCar 里如果有旧的车机记录，先删掉再扫。

第一次连不上时：设置 → 诊断 → 导出诊断日志，看 `OpenHC-Hotspot` 开头那几行。

## 已知风险

- **ROM 可能不允许**：旧接口在 AOSP 6.0 上只校验 `CHANGE_WIFI_STATE`，普通应用可调；但个别把“个人热点”做成系统独占的 ROM 会抛 `SecurityException`，应用侧无解。
  这种车机可以改走 Wi-Fi Direct（P2P 直连组）方案——上游 0.2.1 的“5 GHz 直连”就是这条路，只是门槛写死在 Android 10（按指定频率建组要 API 29），6.0 上去掉频率参数即可。
- 热点开在 2.4 GHz，与蓝牙同频；单射频车机开热点时自己的 Wi-Fi 会断（结束后应用会尝试恢复）。
- 1 GB 内存 + Compose 界面的车机上，界面切换会偏慢；投屏建议 720p。
- BLE 广播受车机蓝牙芯片能力限制：只影响“自动发现”，扫码连接不受影响。

## 从源码构建

需要 JDK 17+、Android SDK 36：

```bash
git clone <this-repo> && cd openhc-android6
echo 'sdk.dir=/path/to/Android/Sdk' > local.properties

# 连接参数 openhc-identity.json 不在仓库里（上游也不放）：
# 从官方发布包 assets/ 里取出来，放到某个目录，且该目录只能有这一个文件
export OPENHC_IDENTITY_DIR=/path/to/identity
# 签名配置（storeFile / storePassword / keyAlias / keyPassword）
export OPENHC_SIGNING=/path/to/signing.properties

./gradlew :app:assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> 在 Windows 上如果 Gradle 下载/依赖解析报 PKIX 证书错误（企业代理），给 JVM 加上
> `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT`（写进 `gradle.properties` 的 `org.gradle.jvmargs`）。

## 许可证与署名

- 上游：<https://github.com/GGBondLab/OpenHC>，**GPL-3.0**（见 [LICENSE](LICENSE)），本仓库同样以 GPL-3.0 发布；
- Release 里的 APK 是上游 0.2.2 源码 + 本仓库补丁的构建产物，署名同样归上游作者；
- 上游 README 备份在 [README.upstream.md](README.upstream.md)。

按 GPL-3.0，分发这个修改版需要同时提供对应源码——本仓库即为对应源码。