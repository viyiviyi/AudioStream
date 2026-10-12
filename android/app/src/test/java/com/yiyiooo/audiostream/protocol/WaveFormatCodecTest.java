package com.yiyiooo.audiostream.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * 音频格式负载：C# 端写出来的两种形态（16 字节整数编码 / 48 字节 GUID）都要认得，
 * 自己写出去的字节也要和 C# 端的一样——手机当来源端时，这一串会被 PC 的
 * NetAudioRedirector.GetWaveFormatExtensible 拿去开声卡。
 */
public class WaveFormatCodecTest {

    /** 由 PowerShell + .NET BinaryWriter 按 C# SendWaveFormat 的规则生成的 48 字节负载。 */
    private static final String GUID_PAYLOAD_HEX =
            "44ac00001800000006000000"
                    + "30303030666666652d303030302d303031302d383030302d303061613030333839623731";

    @Test
    public void 解析十六字节的整数编码() {
        // 48000 / 32 / 2 / 3（IEEE 浮点）：Windows 上环回采集扬声器时的常见形态
        byte[] payload = payload(48000, 32, 2, AudioEncoding.IEEE_FLOAT);
        assertEquals(WaveFormatCodec.INT_ENCODING_LENGTH, payload.length);

        AudioFormat format = WaveFormatCodec.parse(payload, payload.length);
        assertEquals(48000, format.sampleRate);
        assertEquals(32, format.bitsPerSample);
        assertEquals(2, format.channels);
        assertEquals(AudioEncoding.IEEE_FLOAT, format.encoding);
        assertTrue(format.isFloat());
        assertTrue(format.isPlayable());
        assertEquals(8, format.blockAlign());
        assertEquals(384000, format.bytesPerSecond());
    }

    @Test
    public void 解析十六位整数PCM() {
        byte[] payload = payload(44100, 16, 1, AudioEncoding.PCM);
        AudioFormat format = WaveFormatCodec.parse(payload, payload.length);
        assertEquals(44100, format.sampleRate);
        assertEquals(16, format.bitsPerSample);
        assertEquals(1, format.channels);
        assertFalse(format.isFloat());
        assertTrue(format.isPlayable());
        assertEquals(2, format.blockAlign());
    }

    @Test
    public void 解析三十六字符GUID的负载() {
        byte[] payload = AudioFramingTest.hex(GUID_PAYLOAD_HEX);
        assertEquals(48, payload.length);

        AudioFormat format = WaveFormatCodec.parse(payload, payload.length);
        assertEquals(44100, format.sampleRate);
        assertEquals(24, format.bitsPerSample);
        assertEquals(6, format.channels);
        // 认不出的子格式不假装成 PCM
        assertEquals(AudioEncoding.UNKNOWN, format.encoding);
        assertFalse(format.isFloat());
        assertFalse(format.isPlayable());
        assertEquals("0000fffe-0000-0010-8000-00aa00389b71", format.subFormatGuid);
    }

    @Test
    public void GUID形态的PCM与浮点要认出来() {
        // .NET 的 Guid.ToString() 是大写带横线，但别处也可能写不带横线的大写：两种都要认
        byte[] pcm = guidPayload(48000, 16, 2, "0000000100000010800000AA00389B71");
        AudioFormat format = WaveFormatCodec.parse(pcm, pcm.length);
        assertEquals(AudioEncoding.PCM, format.encoding);
        assertFalse(format.isFloat());
        assertTrue(format.isPlayable());

        byte[] ieee = guidPayload(48000, 32, 2, "00000003-0000-0010-8000-00AA00389B71");
        AudioFormat floatFormat = WaveFormatCodec.parse(ieee, ieee.length);
        assertEquals(AudioEncoding.IEEE_FLOAT, floatFormat.encoding);
        assertTrue(floatFormat.isFloat());
    }

    @Test
    public void 编码GUID形态时与参照字节一致() {
        AudioFormat format = new AudioFormat(44100, 24, 6, AudioEncoding.UNKNOWN,
                "0000fffe-0000-0010-8000-00aa00389b71");
        assertArrayEquals(AudioFramingTest.hex(GUID_PAYLOAD_HEX), WaveFormatCodec.encode(format));
    }

    @Test
    public void 编码整数编码时是十六字节小端() {
        AudioFormat format = new AudioFormat(48000, 32, 2, AudioEncoding.IEEE_FLOAT);
        assertArrayEquals(payload(48000, 32, 2, AudioEncoding.IEEE_FLOAT), WaveFormatCodec.encode(format));
    }

    @Test
    public void 编解码往返一致() {
        AudioFormat[] formats = new AudioFormat[] {
                new AudioFormat(48000, 32, 2, AudioEncoding.IEEE_FLOAT),
                new AudioFormat(44100, 16, 1, AudioEncoding.PCM),
                new AudioFormat(96000, 32, 6, AudioEncoding.UNKNOWN, "0000fffe-0000-0010-8000-00aa00389b71"),
        };
        for (AudioFormat format : formats) {
            byte[] encoded = WaveFormatCodec.encode(format);
            AudioFormat parsed = WaveFormatCodec.parse(encoded, encoded.length);
            assertEquals(format.sampleRate, parsed.sampleRate);
            assertEquals(format.bitsPerSample, parsed.bitsPerSample);
            assertEquals(format.channels, parsed.channels);
            assertEquals(format.encoding, parsed.encoding);
            assertEquals(format.subFormatGuid, parsed.subFormatGuid);
        }
    }

    @Test
    public void 负载太短或者数值不合理就返回空() {
        byte[] tooShort = new byte[WaveFormatCodec.FIXED_PART]; // 只有 12 字节，读不出编码
        assertNull(WaveFormatCodec.parse(tooShort, tooShort.length));

        byte[] zeroRate = payload(0, 16, 2, AudioEncoding.PCM);
        assertNull(WaveFormatCodec.parse(zeroRate, zeroRate.length));

        byte[] zeroChannels = payload(48000, 16, 0, AudioEncoding.PCM);
        assertNull(WaveFormatCodec.parse(zeroChannels, zeroChannels.length));
    }

    @Test
    public void 只有能被AudioTrack直接播放的格式才算可播() {
        assertTrue(new AudioFormat(48000, 16, 2, AudioEncoding.PCM).isPlayable());
        assertTrue(new AudioFormat(48000, 32, 2, AudioEncoding.IEEE_FLOAT).isPlayable());
        // 32 位整数不是浮点，AudioTrack 收不了
        assertFalse(new AudioFormat(48000, 32, 2, AudioEncoding.PCM).isPlayable());
        assertFalse(new AudioFormat(48000, 24, 2, AudioEncoding.PCM).isPlayable());
        assertFalse(new AudioFormat(48000, 8, 1, AudioEncoding.PCM).isPlayable());
    }

    private static byte[] payload(int sampleRate, int bits, int channels, int encoding) {
        byte[] buffer = new byte[WaveFormatCodec.INT_ENCODING_LENGTH];
        WaveFormatCodec.writeInt32(buffer, 0, sampleRate);
        WaveFormatCodec.writeInt32(buffer, 4, bits);
        WaveFormatCodec.writeInt32(buffer, 8, channels);
        WaveFormatCodec.writeInt32(buffer, WaveFormatCodec.FIXED_PART, encoding);
        return buffer;
    }

    private static byte[] guidPayload(int sampleRate, int bits, int channels, String guid) {
        byte[] buffer = new byte[WaveFormatCodec.GUID_ENCODING_LENGTH];
        WaveFormatCodec.writeInt32(buffer, 0, sampleRate);
        WaveFormatCodec.writeInt32(buffer, 4, bits);
        WaveFormatCodec.writeInt32(buffer, 8, channels);
        byte[] text = guid.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(text, 0, buffer, WaveFormatCodec.FIXED_PART, Math.min(text.length, WaveFormatCodec.GUID_PART));
        return buffer;
    }
}
