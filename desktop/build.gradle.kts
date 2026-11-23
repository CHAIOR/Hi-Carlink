// Windows 版无线 HiCar 接收端（实验）。
// 复用 core:session 的无线编排器（蓝牙握手/认证/投屏控制），平台层换成本机实现：
// 蓝牙 RFCOMM（JNA 调 Windows 蓝牙栈）、移动热点（PowerShell + WinRT）、H.264 解码（JavaCV）、Java Sound。
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":core:session"))
    // 二维码：把 PairingReady 里的内容画成图，手机扫。
    implementation(libs.zxing.core)
    // Windows 蓝牙 RFCOMM（Winsock AF_BTH）。
    implementation(libs.jna)
    // H.264 解码（JavaCV 打包了 Windows x64 的 ffmpeg 原生库）。
    implementation("org.bytedeco:javacv:1.5.14")
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14:windows-x86_64")
}

application {
    mainClass.set("io.github.gcbond.openhc.pc.PcMainKt")
    applicationName = "hi-carlink-pc"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}
// 自检：只开一个 RFCOMM 监听，打印系统分配的通道号，用来确认这台电脑的蓝牙服务端能起来。
tasks.register<JavaExec>("selftest") {
    mainClass.set("io.github.gcbond.openhc.pc.RfcommSelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// 视频管线自检：现场编码一段 H.264，喂进解码器，抓窗口截图存成 PNG。
tasks.register<JavaExec>("videoTest") {
    mainClass.set("io.github.gcbond.openhc.pc.VideoSelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
    systemProperty("openhc.snapshot", layout.projectDirectory.file("build/video-selftest.png").asFile.absolutePath)
}

// 解码器自检：现场编码一段 H.264，再解出来数帧。
tasks.register<JavaExec>("decodeTest") {
    mainClass.set("io.github.gcbond.openhc.pc.DecodeSelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// 直播解码器自检：按访问单元喂 avcodec，统计帧数与耗时。
tasks.register<JavaExec>("avcodecTest") {
    mainClass.set("io.github.gcbond.openhc.pc.AvcodecSelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
}


// AAC 解码自检：编码 1kHz 正弦 → 按 RTP 载荷逐帧解 → 写 WAV。
tasks.register<JavaExec>("aacTest") {
    mainClass.set("io.github.gcbond.openhc.pc.AacSelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = projectDir
}

// 诊断：试出系统认的蓝牙 socket 安全选项。
tasks.register<JavaExec>("bthopts") {
    mainClass.set("io.github.gcbond.openhc.pc.BthOptsKt")
    classpath = sourceSets["main"].runtimeClasspath
}
