package com.yiyiooo.audiostream;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.yiyiooo.audiostream.route.PlaybackRoute;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 多路并发真机联调：同时拉两路，验「两路各自都真的出声」与「两路音量互不影响」。
 *
 * 为什么不起界面：这台开发机是 VirtualBox 里的 Android-x86，图形栈会成片卡死，
 * 走界面的测试会卡在点不动按钮上，那与多路本身对不对毫无关系。这里直接绑服务调
 * addRoute / setRouteVolume / removeRoute——那正是界面按钮底下调的东西。
 *
 * 参数（am instrument -e 传，缺省值对应本机联调那台 PC 端，经 adb reverse 映射到 127.0.0.1）：
 *   host     对端 IP，默认 127.0.0.1
 *   deviceA  第一路的设备号，默认 default（对端此刻的系统默认输出，扬声器环回）
 *   deviceB  第二路的设备号，默认 default-input（对端此刻的系统默认输入）
 *   seconds  等两路都出声的上限，默认 12
 *
 * 跑法：
 *   adb reverse tcp:12570 tcp:12570 ; adb reverse tcp:12670 tcp:12670
 *   gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yiyiooo.audiostream.MultiRouteIntegrationTest
 */
@RunWith(AndroidJUnit4.class)
public class MultiRouteIntegrationTest {

    private static final String TAG = "AudioStream.MultiRoute";

    private final Object lock = new Object();
    private Context context;
    private AudioStreamService service;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((AudioStreamService.LocalBinder) binder).service();
            bound = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
        }
    };

    @Before
    public void bindService() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, AudioStreamService.class);
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 10000;
            while (!bound && System.currentTimeMillis() < deadline) {
                lock.wait(500);
            }
        }
        assertTrue("服务没能在 10 秒内绑定上", bound);
    }

    /**
     * 收尾时把测试加的路全都移掉。它们会落盘，留着会让下一次跑从「已经有 N 路」开始，
     * 断言「应该正好两路」就会莫名其妙地失败。
     */
    @After
    public void cleanup() {
        AudioStreamService s = service;
        if (s != null) {
            for (PlaybackRoute route : s.routes()) {
                s.removeRoute(route.id());
            }
        }
        if (bound) {
            context.unbindService(connection);
            bound = false;
        }
    }

    @Test
    public void 同时拉两路并且两路音量互不影响() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        String host = text(args, "host", "127.0.0.1");
        String deviceA = text(args, "deviceA", "default");
        String deviceB = text(args, "deviceB", "default-input");
        int seconds = Integer.parseInt(text(args, "seconds", "12"));

        // 起点先清空，保证后面「正好两路」的断言不受上一次运行影响
        for (PlaybackRoute route : service.routes()) {
            service.removeRoute(route.id());
        }
        Thread.sleep(500);

        String errorA = service.addRoute(host, deviceA, "多路测试A");
        String errorB = service.addRoute(host, deviceB, "多路测试B");
        assertNull("第一路没加上：" + errorA, errorA);
        assertNull("第二路没加上：" + errorB, errorB);
        assertEquals("加了两路就该有两路", 2, service.routeCount());

        // 等两路都**真的写出字节**。
        // 只等 playingRouteCount()==2 是不够的：started 是在 out.play() 那一刻置位的，
        // 写入发生在它之后，所以「两路都在出声」已经成立时，可能一个字节都还没写。
        // 第一版就是这么写的，结果在写入之前跳出了等待，报「第一路一个字节都没写进声卡」。
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (allWritten(service.routes())) break;
            Thread.sleep(200);
        }

        List<PlaybackRoute> routes = service.routes();
        String snapshot = describe(routes);
        Log.i(TAG, "=== 多路结果 ===\n" + snapshot);

        assertEquals("应该正好两路", 2, routes.size());
        // 失败消息里带上全部诊断，免得每次都要回头翻 logcat
        assertEquals("两路都该在出声\n" + snapshot, 2, service.playingRouteCount());

        PlaybackRoute a = routes.get(0);
        PlaybackRoute b = routes.get(1);
        // 状态是 PLAYING 还不足以说明出声——必须有字节真的写进声卡才算
        assertTrue("第一路一个字节都没写进声卡\n" + snapshot, a.writtenBytes() > 0);
        assertTrue("第二路一个字节都没写进声卡\n" + snapshot, b.writtenBytes() > 0);
        assertNotEquals("两路应当是不同的来源设备", a.id(), b.id());

        // 音量互不影响
        service.setRouteVolume(a.id(), 0f);
        assertEquals("a 应当被调到静音", 0f, a.config().volume(), 0.001f);
        assertEquals("动 a 不该影响 b", 1f, b.config().volume(), 0.001f);

        service.setRouteVolume(b.id(), 0.3f);
        assertEquals("动 b 不该影响 a", 0f, a.config().volume(), 0.001f);
        assertEquals("b 应当是 30%", 0.3f, b.config().volume(), 0.001f);
        Log.i(TAG, "音量独立：a=" + a.config().volume() + " b=" + b.config().volume());

        // 移掉一路，另一路要接着响
        long before = b.writtenBytes();
        service.removeRoute(a.id());
        Thread.sleep(1500);
        assertEquals("移掉一路后应该只剩一路", 1, service.routeCount());
        assertEquals("剩下的应该是 b", b.id(), service.routes().get(0).id());
        assertTrue("移掉 a 之后 b 应当继续出声", b.writtenBytes() > before);
        Log.i(TAG, "移除一路后 b 继续写入，共 " + b.writtenBytes() + " 字节");
    }

    /** 两路都至少往声卡写过一次。用它当等待条件，而不是「状态是 PLAYING」。 */
    private static boolean allWritten(List<PlaybackRoute> routes) {
        if (routes.size() < 2) return false;
        for (PlaybackRoute route : routes) {
            if (route.writtenBytes() <= 0) return false;
        }
        return true;
    }

    /** 每路的完整诊断，拼进断言消息里。 */
    private static String describe(List<PlaybackRoute> routes) {
        StringBuilder sb = new StringBuilder();
        for (PlaybackRoute route : routes) {
            sb.append(route.config().sourceLabel())
                    .append(" | 状态=").append(route.state())
                    .append(" | 说明=").append(route.stateDetail())
                    .append(" | 格式=").append(route.format())
                    .append(" | 写入=").append(route.writtenBytes())
                    .append(" | 丢弃=").append(route.droppedBytes())
                    .append(" | 欠载=").append(route.underruns())
                    .append(" | 缓冲=").append(route.bufferedMs()).append("ms")
                    .append('\n');
        }
        return sb.toString();
    }

    private static String text(Bundle args, String key, String fallback) {
        String value = args == null ? null : args.getString(key);
        return value == null || value.isEmpty() ? fallback : value;
    }
}
