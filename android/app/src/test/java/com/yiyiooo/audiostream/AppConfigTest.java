package com.yiyiooo.audiostream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * 播放输出的音量偏好。
 *
 * 音量要落盘（下次打开还停在用户调过的位置），而落盘的东西最容易出的两类问题都在这里钉住：
 * 一是越界或坏值（早期版本写坏、手改偏好文件）一路带进声卡；二是 0 与 1 这两个端点被当成「没设过」。
 */
@RunWith(RobolectricTestRunner.class)
public class AppConfigTest {

    private static android.content.Context context() {
        // 用 Robolectric 自带的入口，而不是 androidx.test.core 的 ApplicationProvider：
        // 后者只在本工程的 androidTest 依赖里，JVM 单元测试的 classpath 上没有它。
        return RuntimeEnvironment.getApplication();
    }

    @Test
    public void 没存过时是满增益() {
        // 不自作主张压低：用户没调过就该按对端发来的原始音量放
        assertEquals(AppConfig.DEFAULT_VOLUME, AppConfig.loadVolume(context()), 0.0001f);
    }

    @Test
    public void 存进去再读出来是同一个值() {
        AppConfig.saveVolume(context(), 0.42f);
        assertEquals(0.42f, AppConfig.loadVolume(context()), 0.0001f);
    }

    @Test
    public void 两端点不会被当成没设过() {
        // 0 是「静音」，1 是「满增益」，都是用户认真调出来的值，不能被默认值顶掉
        AppConfig.saveVolume(context(), 0f);
        assertEquals(0f, AppConfig.loadVolume(context()), 0.0001f);
        AppConfig.saveVolume(context(), 1f);
        assertEquals(1f, AppConfig.loadVolume(context()), 0.0001f);
    }

    @Test
    public void 越界与NaN都被夹到合法范围() {
        assertEquals(0f, AppConfig.clampVolume(-3f), 0.0001f);
        assertEquals(1f, AppConfig.clampVolume(9f), 0.0001f);
        assertEquals(AppConfig.DEFAULT_VOLUME, AppConfig.clampVolume(Float.NaN), 0.0001f);
        assertEquals(0.5f, AppConfig.clampVolume(0.5f), 0.0001f);
    }

    @Test
    public void 存进去的越界值也被夹住() {
        // 落盘时的夹取与读取时的夹取都要在：偏好文件可能被手改，也可能来自早期版本
        AppConfig.saveVolume(context(), 12f);
        assertEquals(1f, AppConfig.loadVolume(context()), 0.0001f);
    }
}
