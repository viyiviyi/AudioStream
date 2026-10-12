import com.yiyiooo.audiostream.protocol.AudioFormat;
import com.yiyiooo.audiostream.pull.PullSession;
import com.yiyiooo.audiostream.pull.PullState;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在 PC（宿主机 JVM）上扮演拉取方，去拉手机上的来源端。
 *
 * 为什么需要它：宿主机与这台联调虚拟机之间的任意端口入站是不通的（只有 adb 自己那条通），
 * 所以 PC 端的 AudioStream.exe 没法直接填手机 IP 连过来。把拉取方搬到宿主机上、
 * 经 adb 转发连设备，就能在「真的跨了设备、跨了进程」的前提下验证：
 * PC 侧按协议说话，手机侧能不能把声音送出来。
 *
 * 它用的 PullSession 就是工程里那个拉取方实现（与 C# 端 NetAudioRedirector 同口径），
 * 不是另写一套，所以链路两边都是真实的。
 *
 * 编译与运行（在 android 目录下）：
 *   $cache = tools-cache
 *   $cls = app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes
 *   & "$cache\jdk17\bin\javac.exe" -encoding UTF-8 -cp $cls -d tools\pc-like-pull\out tools\pc-like-pull\PcLikePull.java
 *   $cache\android-sdk\platform-tools\adb.exe forward tcp:12671 tcp:12670
 *   & "$cache\jdk17\bin\java.exe" -cp "$cls;tools\pc-like-pull\out" PcLikePull 127.0.0.1 12671 mic:default 5
 */
public final class PcLikePull {

    public static void main(String[] args) throws Exception {
        final String host = args.length > 0 ? args[0] : "127.0.0.1";
        final int port = args.length > 1 ? Integer.parseInt(args[1]) : 12671;
        final String deviceId = args.length > 2 ? args[2] : "mic:default";
        final int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 5;

        final AtomicLong total = new AtomicLong();
        final AtomicLong nonZero = new AtomicLong();
        final AtomicLong frames = new AtomicLong();
        final AtomicReference<AudioFormat> format = new AtomicReference<AudioFormat>();
        final AtomicReference<PullState> state = new AtomicReference<PullState>(PullState.IDLE);
        final CountDownLatch firstAudio = new CountDownLatch(1);

        PullSession pull = new PullSession(host, port, deviceId, "手机麦克风",
                "22222222222222222222222222222222", "PC拉取方",
                new PullSession.Listener() {
                    @Override
                    public void onState(PullState value, String detail) {
                        state.set(value);
                        System.out.println("[PC拉取方] 状态 " + value.label() + "：" + detail);
                    }

                    @Override
                    public void onFormat(AudioFormat value) {
                        format.set(value);
                        System.out.println("[PC拉取方] 对端给的格式：" + value.describe());
                    }

                    @Override
                    public void onAudio(byte[] data, int offset, int length) {
                        frames.incrementAndGet();
                        total.addAndGet(length);
                        long nz = 0;
                        for (int i = offset; i < offset + length; i++) {
                            if (data[i] != 0) nz++;
                        }
                        nonZero.addAndGet(nz);
                        firstAudio.countDown();
                    }
                });

        pull.start();
        try {
            if (!firstAudio.await(5, TimeUnit.SECONDS)) {
                System.out.println("[PC拉取方] 5 秒内没收到音频，当前状态 " + state.get().label());
                System.exit(2);
            }
            Thread.sleep(seconds * 1000L);
        } finally {
            pull.close();
        }

        AudioFormat got = format.get();
        long bytes = total.get();
        long expected = got == null ? 0 : (long) got.bytesPerSecond() * seconds;
        System.out.println("[PC拉取方] 结果：格式=" + (got == null ? "无" : got.describe())
                + "，收到 " + bytes + " 字节 / " + seconds + " 秒（理论 " + expected + "）"
                + "，帧 " + frames.get()
                + "，非零 " + nonZero.get() + "（" + (bytes == 0 ? 0 : nonZero.get() * 100 / bytes) + "%）");
        System.exit(bytes > expected / 2 ? 0 : 1);
    }
}
