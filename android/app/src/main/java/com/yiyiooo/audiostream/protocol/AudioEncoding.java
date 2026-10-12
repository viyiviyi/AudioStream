package com.yiyiooo.audiostream.protocol;

/**
 * C# 端 CSCore 的 {@code AudioEncoding} 里用得着的两个取值，数值是**从 CSCore.dll 里读出来的**，
 * 不是照着记忆写的：
 *
 * <pre>
 * AudioEncoding.Unknown   = 0
 * AudioEncoding.Pcm       = 1
 * AudioEncoding.Adpcm     = 2
 * AudioEncoding.IeeeFloat = 3
 * </pre>
 *
 * 另外 CSCore 的 {@code AudioSubTypes} 是 GUID 字符串（不是 16 字节二进制）：
 * Pcm = 00000001-0000-0010-8000-00aa00389b71，IeeeFloat = 00000003-0000-0010-8000-00aa00389b71。
 * C# 端的 SendWaveFormat 在「extensible 且子格式既不是 PCM 也不是浮点」时，
 * 会把 {@code SubFormat.ToString()} 这 36 个 ASCII 字符直接写进负载里。
 */
public final class AudioEncoding {
    public static final int UNKNOWN = 0;
    public static final int PCM = 1;
    public static final int ADPCM = 2;
    public static final int IEEE_FLOAT = 3;

    public static final String SUBTYPE_PCM = "00000001-0000-0010-8000-00aa00389b71";
    public static final String SUBTYPE_IEEE_FLOAT = "00000003-0000-0010-8000-00aa00389b71";

    private AudioEncoding() {
    }

    /** 摘掉花括号/圆括号/横线与空白，只留下 32 位十六进制——两端写的 GUID 大小写与格式不一定一样。 */
    public static String normalizeGuid(String guid) {
        if (guid == null) return "";
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < guid.length(); i++) {
            char c = guid.charAt(i);
            if (c == '{' || c == '}' || c == '(' || c == ')' || c == '-' || c == ' ') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 把 32 位十六进制还原成 .NET {@code Guid.ToString()} 那种小写带横线的形式。 */
    public static String canonicalGuid(String guid) {
        String hex = normalizeGuid(guid);
        if (hex.length() != 32) return hex;
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                + "-" + hex.substring(16, 20) + "-" + hex.substring(20);
    }

    /** 给人看的名字，只用于界面上说明「拉到的这路是什么格式」。 */
    public static String name(int encoding) {
        switch (encoding) {
            case PCM:
                return "PCM";
            case IEEE_FLOAT:
                return "IEEE 浮点";
            case ADPCM:
                return "ADPCM";
            default:
                return "编码" + encoding;
        }
    }
}
