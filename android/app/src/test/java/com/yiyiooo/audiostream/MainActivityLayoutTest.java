package com.yiyiooo.audiostream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * 界面的回归网：在 JVM 上把布局真的 inflate 一遍，断言控件与文案还在。
 *
 * 为什么要有这一层：这台开发机（VirtualBox 里的 Android-x86）图形栈会成片卡死，
 * uiautomator dump 抓到的往往是 System UI 自己的 ANR 弹窗而不是本应用，界面改坏了根本看不出来。
 * 而布局 inflate 失败（资源名写错、Material 样式找不到、id 拼错）恰恰是最常见的一类低级错误，
 * 它不需要屏幕就能验。
 *
 * 它验不了「好不好看」——那只能靠人眼。它验的是「还在不在、文案有没有被删掉、默认值对不对」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
public class MainActivityLayoutTest {

    private Activity create() {
        return Robolectric.buildActivity(MainActivity.class).create().get();
    }

    @Test
    public void 四张卡片的入口控件都在() {
        Activity activity = create();
        String[][] expected = {
                {"地址输入", "hostInput"},
                {"刷新按钮", "discoverButton"},
                {"设备下拉", "deviceSpinner"},
                {"连接按钮", "connectButton"},
                {"音量滑条", "volumeSlider"},
                {"音量数值", "volumeText"},
                {"开启来源端", "sourceButton"},
                {"允许内录", "captureButton"},
                {"主题三选", "themeGroup"},
                {"日志区", "logText"},
        };
        for (String[] pair : expected) {
            assertNotNull(pair[0] + "（id=" + pair[1] + "）不该从布局里消失",
                    findByResourceName(activity, pair[1]));
        }
    }

    @Test
    public void 音量滑条默认满增益且能调到底() {
        Slider slider = create().findViewById(R.id.volumeSlider);
        assertEquals("默认应是满增益，不能凭空把用户的声音压低", 1f, slider.getValue(), 0.001f);
        assertEquals("0（静音）必须是滑块能到的值", 0f, slider.getValueFrom(), 0.001f);
        assertEquals("1（原始增益）必须是滑块能到的值", 1f, slider.getValueTo(), 0.001f);
    }

    /**
     * 这句提示是「单独调整输出音量」这个需求的落点：用户看到一个音量条，
     * 首先会怀疑它是不是把整台手机的媒体音量改了。写在控件边上，他才不用去猜。
     */
    @Test
    public void 音量滑条旁边必须写明它不动系统音量() {
        View root = create().findViewById(android.R.id.content);
        assertTrue("音量滑条旁应保留「不影响系统媒体音量」的说明，否则用户不知道它只作用于本应用",
                hasText(root, "不影响系统媒体音量"));
    }

    /** 来源端那张卡片得告诉用户「这个地址填在电脑那边」，否则他不知道拿它干什么。 */
    @Test
    public void 来源端卡片要说明地址填在电脑那边() {
        View root = create().findViewById(android.R.id.content);
        assertTrue("来源端卡片应保留「在电脑的配置页填」的引导",
                hasText(root, "在电脑的配置页填"));
    }

    @Test
    public void 主题三选是单选且默认跟随系统() {
        Activity activity = create();
        MaterialButtonToggleGroup group = activity.findViewById(R.id.themeGroup);
        assertNotNull(group);
        assertEquals("外观应当恰好三个选项", 3, group.getChildCount());
        MaterialButton system = activity.findViewById(R.id.themeSystem);
        assertTrue("没存过偏好时必须默认「跟随系统」，不能替用户选死一个",
                system.isChecked());
    }

    /** 深度优先找一段文案。布局里的说明文字没有 id，只能按内容找。 */
    private static boolean hasText(View root, String fragment) {
        if (root instanceof TextView) {
            CharSequence text = ((TextView) root).getText();
            if (text != null && text.toString().contains(fragment)) return true;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasText(group.getChildAt(i), fragment)) return true;
            }
        }
        return false;
    }

    /**
     * 按资源名找控件。
     *
     * 不直接写 R.id.xxx：这张表是「布局里应该有哪些 id」的清单，
     * 如果某个 id 从布局里被删掉，R.id.xxx 会在编译期就报错（那反而挡住了这个测试要表达的意图），
     * 而按名字查能在运行期给出「哪一个不见了」的准确信息。
     */
    private static View findByResourceName(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "id", activity.getPackageName());
        if (id == 0) return null;
        return activity.findViewById(id);
    }
}
