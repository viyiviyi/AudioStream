package com.yiyiooo.audiostream.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.media.AudioFormat;

import com.yiyiooo.audiostream.protocol.AudioEncoding;

import org.junit.Test;

/**
 * 格式 → {@code AudioTrack} 参数的映射。这一段不需要设备，而它正是「没声音」最常见的现场，
 * 所以必须能在 JVM 上验。
 *
 * 没覆盖的：32 位整数 PCM 在安卓 12 以上走 {@code ENCODING_PCM_32BIT} 那一支，
 * 它要读 {@code Build.VERSION.SDK_INT}，在纯 JVM 上拿不到真实值——留到模拟器上验。
 */
public class AudioTrackConfigTest {

    private static com.yiyiooo.audiostream.protocol.AudioFormat fmt(int rate, int bits, int channels, int encoding) {
        return new com.yiyiooo.audiostream.protocol.AudioFormat(rate, bits, channels, encoding);
    }

    @Test
    public void 十六位整数映射成PCM16() throws Exception {
        assertEquals(AudioFormat.ENCODING_PCM_16BIT,
                AudioTrackConfig.encodingOf(fmt(48000, 16, 2, AudioEncoding.PCM)));
    }

    @Test
    public void 三十二位浮点映射成PCMFLOAT() throws Exception {
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT,
                AudioTrackConfig.encodingOf(fmt(48000, 32, 2, AudioEncoding.IEEE_FLOAT)));
    }

    @Test
    public void 子格式GUID是浮点也认() throws Exception {
        com.yiyiooo.audiostream.protocol.AudioFormat format =
                new com.yiyiooo.audiostream.protocol.AudioFormat(48000, 32, 2, AudioEncoding.UNKNOWN,
                        AudioEncoding.SUBTYPE_IEEE_FLOAT);
        assertEquals(AudioFormat.ENCODING_PCM_FLOAT, AudioTrackConfig.encodingOf(format));
    }

    @Test
    public void 认不出的位深要报错而不是硬当十六位播() {
        assertUnsupported(fmt(48000, 24, 2, AudioEncoding.PCM));
        assertUnsupported(fmt(48000, 8, 2, AudioEncoding.PCM));
        // 压缩编码没有解码器，不能拿它的字节当 PCM 播
        assertUnsupported(fmt(48000, 16, 2, AudioEncoding.ADPCM));
    }

    @Test
    public void 只认单声道与立体声() throws Exception {
        assertEquals(AudioFormat.CHANNEL_OUT_MONO,
                AudioTrackConfig.channelMaskOf(fmt(48000, 16, 1, AudioEncoding.PCM)));
        assertEquals(AudioFormat.CHANNEL_OUT_STEREO,
                AudioTrackConfig.channelMaskOf(fmt(48000, 16, 2, AudioEncoding.PCM)));
        try {
            AudioTrackConfig.channelMaskOf(new com.yiyiooo.audiostream.protocol.AudioFormat(48000, 16, 6, AudioEncoding.PCM));
            fail("6 声道本该被拒");
        } catch (AudioSinkFactory.UnsupportedAudioException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("6 声道"));
        }
    }

    @Test
    public void 声卡缓冲不小于系统下限且按帧取整() {
        // 48000Hz 16 位立体声 = 192000 B/s，60ms 是 11520 字节
        assertEquals(11520,
                AudioTrackConfig.bufferBytesFor(fmt(48000, 16, 2, AudioEncoding.PCM), 1000));
        // 系统下限更大时听系统的
        assertEquals(20000,
                AudioTrackConfig.bufferBytesFor(fmt(48000, 16, 2, AudioEncoding.PCM), 20000));
        // 单声道一帧 2 字节：下限 96001 要抬到 96002
        assertEquals(96002,
                AudioTrackConfig.bufferBytesFor(fmt(48000, 16, 1, AudioEncoding.PCM), 96001));
    }

    @Test
    public void 系统下限不可用时要能识别出来() {
        assertTrue(AudioTrackConfig.isMinBufferUsable(1));
        assertTrue(!AudioTrackConfig.isMinBufferUsable(0));
        assertTrue(!AudioTrackConfig.isMinBufferUsable(-2));
    }

    private static void assertUnsupported(com.yiyiooo.audiostream.protocol.AudioFormat format) {
        try {
            AudioTrackConfig.encodingOf(format);
            fail("这种格式本该被拒：" + format.describe());
        } catch (AudioSinkFactory.UnsupportedAudioException e) {
            assertTrue("拒的理由要说人话：" + e.getMessage(),
                    e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }
}
