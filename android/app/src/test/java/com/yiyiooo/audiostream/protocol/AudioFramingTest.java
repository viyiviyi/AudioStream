package com.yiyiooo.audiostream.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 帧协议的两条底线：写出来的字节要和 C# 端一模一样（不然两端根本聊不上），
 * 读的时候要在粘包、半包、超长帧、长度字段是垃圾这四种情况下都不把音频搞错位。
 */
public class AudioFramingTest {

    /**
     * 参照字节由 **PowerShell 侧的实现**（tools\AudioFraming.ps1 + .NET BinaryWriter）生成，
     * 不是 Java 这边自己算一遍再自己比一遍：
     *
     * <pre>
     * 帧1 文本 /Hello/0123456789abcdef0123456789abcdef/%E9%9F%B3%E9%A2%91   （59 字节负载）
     * 帧2 音频 16 字节，逐字节 0xA0..0xAF
     * 帧3 格式 48000 / 32 位 / 2 声道 / 编码 3（IEEE 浮点），小端
     * 帧4 文本，空负载（长度字段为 1）
     * </pre>
     */
    private static final String REFERENCE_STREAM_HEX =
            "3b000000022f48656c6c6f2f30313233343536373839616263646566303132333435363738396162636465662f"
                    + "254539253946254233254539254132253931"
                    + "1100000001a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"
                    + "110000000380bb0000200000000200000003000000"
                    + "0100000002";

    @Test
    public void 帧头是小端长度加类型字节() {
        byte[] payload = "abc".getBytes(StandardCharsets.US_ASCII);
        byte[] frame = AudioFraming.encode(FrameKind.TEXT, payload, 0, payload.length);
        // 负载 3 字节 + 类型 1 字节 = 4，写在小端 4 字节里
        assertArrayEquals(new byte[] { 4, 0, 0, 0, 2, 'a', 'b', 'c' }, frame);
    }

    @Test
    public void 四条帧的字节与PowerShell侧实现完全一致() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // 帧1：文本命令。名字那一段在真实流程里是 Uri.EscapeDataString 的结果，这里直接用同一个字符串。
        out.write(AudioFraming.encodeText(
                "/Hello/0123456789abcdef0123456789abcdef/%E9%9F%B3%E9%A2%91"));
        // 帧2：音频，逐字节递增
        byte[] audio = new byte[16];
        for (int i = 0; i < audio.length; i++) audio[i] = (byte) (0xA0 + i);
        out.write(AudioFraming.encode(FrameKind.AUDIO, audio, 0, audio.length));
        // 帧3：音频格式
        out.write(AudioFraming.encodeWaveFormat(WaveFormatCodec.encode(
                new AudioFormat(48000, 32, 2, AudioEncoding.IEEE_FLOAT))));
        // 帧4：空负载的文本帧
        out.write(AudioFraming.encodeText(""));

        assertArrayEquals(hex(REFERENCE_STREAM_HEX), out.toByteArray());
    }

    @Test
    public void 一次read只给一字节也要读满() throws IOException {
        byte[] data = "0123456789".getBytes(StandardCharsets.US_ASCII);
        byte[] buffer = new byte[10];
        // 模拟 TCP 分片：每次最多给 1 字节
        assertTrue(AudioFraming.readExact(new DribbleInputStream(data, 1), buffer, 0, 10));
        assertEquals("0123456789", new String(buffer, StandardCharsets.US_ASCII));
    }

    @Test
    public void 对端提前关闭时读不满就是失败() throws IOException {
        byte[] data = "0123".getBytes(StandardCharsets.US_ASCII);
        assertFalse(AudioFraming.readExact(new DribbleInputStream(data, 2), new byte[10], 0, 10));
    }

    @Test
    public void 粘包时两条帧都能读出来() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(AudioFraming.encodeText("/Pong"));
        out.write(AudioFraming.encode(FrameKind.AUDIO, new byte[] { 1, 2, 3, 4 }, 0, 4));
        FrameReader reader = new FrameReader(new ByteArrayInputStream(out.toByteArray()));

        assertTrue(reader.readNext());
        assertEquals(FrameKind.TEXT, reader.lastKind());
        assertEquals("/Pong", reader.readText());

        assertTrue(reader.readNext());
        assertEquals(FrameKind.AUDIO, reader.lastKind());
        assertEquals(4, reader.lastCount());
        assertArrayEquals(new byte[] { 1, 2, 3, 4 }, java.util.Arrays.copyOf(reader.buffer(), 4));

        assertFalse(reader.readNext());
    }

    @Test
    public void 半包时一条帧也要能读出来() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(AudioFraming.encodeText("/Hello/abc"));
        FrameReader reader = new FrameReader(new DribbleInputStream(out.toByteArray(), 1));
        assertTrue(reader.readNext());
        assertEquals("/Hello/abc", reader.readText());
    }

    @Test
    public void 空负载的帧是合法帧() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(AudioFraming.encodeText(""));
        out.write(AudioFraming.encodeText("下一帧"));
        FrameReader reader = new FrameReader(new ByteArrayInputStream(out.toByteArray()));

        assertTrue(reader.readNext());
        assertEquals(FrameKind.TEXT, reader.lastKind());
        assertEquals(0, reader.lastCount());
        assertEquals("", reader.readText());

        // 空负载那一帧没有把流读歪
        assertTrue(reader.readNext());
        assertEquals("下一帧", reader.readText());
    }

    @Test
    public void 比负载缓冲大的帧整帧丢掉但下一帧仍然对得上() throws IOException {
        byte[] big = new byte[40];
        for (int i = 0; i < big.length; i++) big[i] = (byte) i;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(AudioFraming.encode(FrameKind.AUDIO, big, 0, big.length));
        out.write(AudioFraming.encodeText("OK"));

        // 缓冲故意只给 16 字节：40 字节那帧装不下
        FrameReader reader = new FrameReader(new ByteArrayInputStream(out.toByteArray()), 16);
        assertTrue(reader.readNext());
        assertEquals(FrameKind.AUDIO, reader.lastKind());
        assertEquals(0, reader.lastCount());

        assertTrue(reader.readNext());
        assertEquals("OK", reader.readText());
    }

    @Test
    public void 长度字段不可信时判定流结束() throws IOException {
        // 长度字段远超上限：站在流的中间已经无法知道哪一段是音频，只能断开
        byte[] header = new byte[] { 0, 0, 0x20, 0x00, 5 }; // 0x00200000 = 2MB
        FrameReader reader = new FrameReader(new ByteArrayInputStream(header));
        assertFalse(reader.readNext());

        // 长度为 0（比类型字节还短）同样是坏流
        FrameReader zero = new FrameReader(new ByteArrayInputStream(new byte[] { 0, 0, 0, 0, 5 }));
        assertFalse(zero.readNext());
    }

    @Test
    public void 认不出的类型值不算流坏了() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(AudioFraming.encode(FrameKind.AUDIO, new byte[] { 7, 7 }, 0, 2));
        byte[] stream = out.toByteArray();
        stream[4] = 9; // 把一个未来的类型值塞进帧头
        out.reset();
        out.write(stream);
        out.write(AudioFraming.encodeText("/Pong"));

        FrameReader reader = new FrameReader(new ByteArrayInputStream(out.toByteArray()));
        assertTrue(reader.readNext());
        assertNull(reader.lastKind());
        assertEquals(2, reader.lastCount());

        assertTrue(reader.readNext());
        assertEquals("/Pong", reader.readText());
    }

    /** 每次最多给 limit 个字节的输入流，用来模拟 TCP 分片。 */
    private static final class DribbleInputStream extends InputStream {
        private final byte[] data;
        private final int limit;
        private int position;

        DribbleInputStream(byte[] data, int limit) {
            this.data = data;
            this.limit = limit;
        }

        @Override
        public int read() {
            if (position >= data.length) return -1;
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (position >= data.length) return -1;
            int count = Math.min(Math.min(limit, len), data.length - position);
            System.arraycopy(data, position, b, off, count);
            position += count;
            return count;
        }
    }

    static byte[] hex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
