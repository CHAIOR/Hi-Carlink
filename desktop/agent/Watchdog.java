import java.awt.Window;

/**
 * 窗口关了但进程还在：轮询 AWT 窗口，一旦「曾经出现过窗口、现在一个都没有」就退出 JVM。
 * 用 -javaagent 挂载，不需要改动主程序。
 */
public final class Watchdog {
    public static void premain(String args) {
        Thread t = new Thread(() -> {
            boolean seen = false;
            while (true) {
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    return;
                }
                boolean visible = false;
                for (Window w : Window.getWindows()) {
                    if (w.isDisplayable() && w.isVisible()) {
                        visible = true;
                        break;
                    }
                }
                if (visible) {
                    seen = true;
                } else if (seen) {
                    System.exit(0);
                }
            }
        }, "HiCarlink-Watchdog");
        t.setDaemon(true);
        t.start();
    }
}
