# Hi Carlink PC（Windows 版无线 HiCar 接收端）

复用 `core:session` 的无线编排器（蓝牙 RFCOMM 握手/认证 → 热点 → TCP → 投屏），把平台层换成 Windows 实现：
JNA 调 Winsock 蓝牙、识别 Windows 移动热点、avcodec 解码 H.264/AAC、Swing 单窗口界面。

## 运行 / 打包

```bash
# 直接跑（需要 JDK 21 + Android SDK 36，见仓库根 local.properties）
./gradlew :desktop:installDist
desktop/build/install/hi-carlink-pc/bin/hi-carlink-pc.bat --identity <openhc-identity.json> --trust-dir <目录>

# 自检（不需要手机）
./gradlew :desktop:videoTest    # 解码 → 窗口 → 截图
./gradlew :desktop:aacTest      # AAC 解码 → WAV
./gradlew :desktop:audioOutTest # 往默认设备写 1 秒 440Hz，确认能出声
./gradlew :desktop:hotspotTest  # 识别已开的 Windows 移动热点
./gradlew :desktop:trustTest    # 信任库原子写在 Windows 上可用
```

安装包用 Inno Setup（`desktop/installer/HiCarlinkPC.iss`）：先准备 `HiCarlinkPC/` 目录（runtime/app/identity/README/LICENSE/app.ico），
再 `ISCC.exe HiCarlinkPC.iss`。`runtime/` 从 JDK 21 复制 `bin/conf/lib/release`，并且**不要携带**下面这些系统同名 DLL：

- `msvcp140*.dll`、`vcruntime140*.dll`、`concrt140*.dll`、`vccorlib140.dll`、`vcomp140.dll`
- `ucrtbase.dll`
- `api-ms-win-*.dll`

部分机器（含作者的 Win11）会拦截这些文件名的创建/重命名（`MoveFile 失败；错误代码 5`），jlink/jpackage 也会因此失败；
System32 里本来就有它们，删掉后 Java 照常加载。

## 平台层要点（踩过的坑）

- **RFCOMM**：Winsock `AF_BTH`；`SOCKADDR_BTH` 是 `pack(1)` 的 **30 字节**布局（family@0、btAddr@2、guid@10、port@26），
  按 40 字节算 bind 会一直报 `10049`；蓝牙地址在结构里是反序字节。
- **关监听 ≠ 关连接**：`closeListener()` 只撤 SDP/停止 accept，已接的连接继续用（安卓 `BluetoothServerSocket.close()` 语义），
  否则手机会在认证子通道连上后被我们自己断开。
- **热点**：`NetworkOperatorTetheringManager` 只能经 PowerShell/WinRT 调；部分机器上 Java 起 powershell 会报
  `CreateProcess error=5`，所以主路径是「等车主手动开热点」——认 `192.168.137.x` / Wi-Fi Direct 虚拟网卡。
- **H.264**：不要用 `FFmpegFrameGrabber` 读阻塞流（demuxer 会卡在探测/跳读），直接 `avcodec` 解访问单元；
  yuv420p→RGB 多线程分块写 `DataBufferInt`，2K 约 9ms/帧。
- **AAC**：extradata 必须 `av_malloc`（`avcodec_free_context` 会用 `av_free` 释放），采样数据按指针偏移读 float。
- **信任库**：`PrivateFiles.writeAtomically` 在 Windows 上**不能**给临时文件带 POSIX 初始属性
  （`UnsupportedOperationException` → 首次配对报 `SAVE_LTPK_FAILED(31)`），要按 `POSIX` 分支只保留 `CREATE_NEW`。

## 关窗退出

会话结束后窗口会停在「重新开始」的等待上，关窗时要唤醒这个等待（`restartRequested?.countDown()`）。
另外附带一个小 agent（`desktop/agent/Watchdog.java`）：检测到「窗口曾经出现过、现在一个都没有」就退出 JVM，
兜底防止「窗口关了但进程还在、单实例锁被占住」。编译：

```bash
runtime/bin/java -m jdk.compiler/com.sun.tools.javac.Main -d out desktop/agent/Watchdog.java
# 打成 jar（MANIFEST: Premain-Class: Watchdog），启动参数加 -javaagent:app/hicarlink-agent.jar
```