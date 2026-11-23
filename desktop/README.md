
## 0.2.2 的两个修复

- **首次配对 SAVE_LTPK_FAILED(31)**：`PrivateFiles.writeAtomically` 在 Windows 上给临时文件带了 POSIX 初始属性，
  会抛 `UnsupportedOperationException`，导致配对最后一步存 LTPK 失败。现在用 `POSIX` 判断分支，Windows 只保留 `CREATE_NEW`。
- **关窗后进程残留**：会话结束后停在「重新开始」的等待上，关窗没有唤醒等待。现在关窗时会 `restartRequested?.countDown()`；
  另外附带 `hicarlink-agent.jar`（`desktop/agent/Watchdog.java`），检测到「有过窗口、现在全没有了」就退出 JVM。
  编译 agent（用打包内的运行时即可）：

  ```bash
  runtime/bin/java -m jdk.compiler/com.sun.tools.javac.Main -d out desktop/agent/Watchdog.java
  # 打成 jar（MANIFEST 里 Premain-Class: Watchdog），启动参数加 -javaagent:app/hicarlink-agent.jar
  ```
