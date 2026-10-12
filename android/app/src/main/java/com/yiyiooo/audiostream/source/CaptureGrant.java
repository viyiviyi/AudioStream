package com.yiyiooo.audiostream.source;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

/**
 * 用户对内录的那一次授权。
 *
 * Android 不允许应用自己决定「可以录屏内音频」：必须由系统弹一次对话框、用户点同意，
 * 应用拿到的就是这一对（resultCode, data）。data 里是一次性的授权凭据，
 * {@link MediaProjectionManager#getMediaProjection} 用它换出 {@link MediaProjection}。
 *
 * 两个必须记住的性质：
 * <ul>
 *   <li><b>每次都要重新授权</b>。凭据不能存盘、不能跨进程、下次开机也不在，所以内录天生做不到
 *       「开机即用」——用户在手机上点过一次同意之后，只有本次进程活着的这段时间有效。</li>
 *   <li><b>换 MediaProjection 这件事是消耗性的</b>。同一份凭据换两次，第二次会抛异常；
 *       所以这里把换出来的 {@link MediaProjection} 自己留住，需要的人来拿同一个。</li>
 * </ul>
 */
public final class CaptureGrant {

    private final int resultCode;
    private final Intent data;
    private final long grantedAt;

    private MediaProjection projection;

    public CaptureGrant(int resultCode, Intent data) {
        this.resultCode = resultCode;
        this.data = data;
        this.grantedAt = System.currentTimeMillis();
    }

    /** 用户到底点没点同意。点了取消、或者没带回凭据，都不算。 */
    public boolean isUsable() {
        return resultCode == Activity.RESULT_OK && data != null;
    }

    /** 授权拿到手的时刻，用来在界面上说明「这次开启是哪一次点的」。 */
    public long grantedAt() {
        return grantedAt;
    }

    /**
     * 换出（或复用）MediaProjection。
     *
     * @throws SourceUnavailableException 系统拒绝了这次换（凭据被消耗过、被回收、或者调用方
     *         没有先以 mediaProjection 类型进入前台——Android 14 起这是硬要求）
     */
    public synchronized MediaProjection projection(Context context) throws SourceUnavailableException {
        if (projection != null) return projection;
        if (!isUsable()) {
            throw new SourceUnavailableException(SourceUnavailableException.DENIED,
                    "手机还没有允许内录，请在手机上点「允许内录」并在系统弹框里同意");
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "这台设备没有提供内录能力（取不到 MediaProjectionManager）");
        }
        try {
            projection = manager.getMediaProjection(resultCode, data);
        } catch (Exception e) {
            // 凭据被消耗过、或者 Android 14 上没先起 mediaProjection 前台服务，都会落到这里
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "这次内录授权已经不能用了（" + reason(e) + "），请在手机上重新点一次「允许内录」", e);
        }
        if (projection == null) {
            throw new SourceUnavailableException(SourceUnavailableException.UNAVAILABLE,
                    "系统没有给出内录通道，请在手机上重新点一次「允许内录」");
        }
        return projection;
    }

    /** 收工：把内录通道交还系统，之后要重新授权。 */
    public synchronized void release() {
        MediaProjection current = projection;
        projection = null;
        if (current != null) {
            try {
                current.stop();
            } catch (Exception ignored) {
                // 系统可能已经先一步回收了
            }
        }
    }

    public synchronized boolean isOpen() {
        return projection != null;
    }

    private static String reason(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
