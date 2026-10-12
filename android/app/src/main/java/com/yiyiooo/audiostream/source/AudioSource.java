package com.yiyiooo.audiostream.source;

import com.yiyiooo.audiostream.protocol.AudioFormat;

import java.io.Closeable;

/**
 * 一路可以被 PC 端拉取的音频来源。手机端目前只有麦克风，内录稍后加入。
 *
 * 生命周期与订阅者绑在一起：第一个订阅者进来才真的开始采，最后一个走了就停。
 * 这不只是省电——没人听的时候麦克风还开着，状态栏的录音标志会一直亮着，
 * 用户看到的是「这程序在偷听」。
 */
public interface AudioSource extends Closeable {

    /** 这一路的格式。必须在任何订阅之前就能给出来——对端点单时先要格式，那时候还没开始采。 */
    AudioFormat format();

    /** 界面上说明用的一句话。 */
    String describe();

    /** 加一个订阅者。第一个订阅者会让采集真正动起来。 */
    void addSink(Sink sink);

    /** 去掉一个订阅者。最后一个走的会把采集停掉并释放。 */
    void removeSink(Sink sink);

    /** 此刻还有几个订阅者。 */
    int sinkCount();

    /** 采集出来的一段 PCM。 */
    interface Sink {

        /**
         * data 是**复用的缓冲**：出了这个回调就不能再引用它，要留就得自己拷一份。
         *
         * 这个方法跑在采集线程上，里面做耗时的事（写 socket、等锁）会让采集缓冲撑爆，
         * 表现是音频一卡一卡地丢。
         */
        void onAudio(byte[] data, int offset, int length);
    }
}
