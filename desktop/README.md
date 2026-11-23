# Hi Carlink PC（Windows 版无线 HiCar 接收端）

复用 `core:session` 的无线编排器（蓝牙 RFCOMM 握手/认证 → 热点 → TCP → 投屏），把平台层换成 Windows 实现。

## 运行 / 打包

```bash
# 直接跑（需要 JDK 21 + Android SDK 36，见仓库根 local.properties）
./gradlew :desktop:installDist
desktop/build/install/hi-carlink-pc/bin/hi-carlink-pc.bat --identity <openhc-identity.json> --trust-dir <目录>

# 自检（不需要手机）
./gradlew :desktop:videoTest    # 解码 → 窗口 → 截图
./gradlew :desktop:aacTest      # AAC 解码 → WAV
./gradlew :desktop:hotspotTest  # 识别已开的 Windows 移动热点
./gradlew :desktop:trustTest    # 信任库原子写在 Windows 上可用
```

安装包用 Inno Setup（`desktop/installer/HiCarlinkPC.iss`）：先准备 `HiCarlinkPC/` 目录（runtime/app/identity/README/LICENSE/app.ico），
再 `ISCC.exe HiCarlinkPC.iss`。`runtime/` 从 JDK 21 复制 `bin/conf/lib/release`，**删掉 `bin/api-ms-win-*.dll`**——
某个环境的保护策略会拒绝这些文件名的移动/重命名（jlink、jpackage、Inno 都会失败），删掉后 java 会回落到 System32 的同名 API set，运行正常。

## 平台层要点（踩过的坑）

- **RFCOMM**：Winsock `AF_BTH`；`SOCKADDR_BTH` 是 `pack(1)` 的 **30 字节**布局（family@0、btAddr@2、guid@10、port@26），
  按 40 字节算 bind 会一直报 `10049`；蓝牙地址在结构里是反序字节。
- **关监听 ≠ 关连接**：`closeListener()` 只撤 SDP/停止 accept，已接的连接继续用（安卓 `BluetoothServerSocket.close()` 语义），
  否则手机会在认证子通道连上后被我们自己断开，且手机端没有任何提示。
- **热点**：`NetworkOperatorTetheringManager` 只能经 PowerShell/WinRT 调；部分机器上 Java 起 powershell 会报
  `CreateProcess error=5`，所以主路径是「等车主手动开热点」——认 `192.168.137.x` / Wi-Fi Direct 虚拟网卡。
- **H.264**：不要用 `FFmpegFrameGrabber` 读阻塞流（demuxer 会卡在探测/跳读），直接 `avcodec` 解访问单元；
  yuv420p→RGB 多线程分块写 `DataBufferInt`，2K 约 9ms/帧。
- **AAC**：extradata 必须 `av_malloc`（`avcodec_free_context` 会用 `av_free` 释放），采样数据按指针偏移读 float。
