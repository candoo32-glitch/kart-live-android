package com.kartgarden.live;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;
import com.kartgarden.live.protocol.LspAssembler;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

/** Serial, bounded MediaCodec pipeline. Loss/drop forces an IDR resynchronization. */
public final class VideoDecoder {
    private Surface surface;
    private MediaCodec codec;
    private byte[] sps,pps;
    private boolean synced;
    private long timestamp;
    public volatile long rendered,errors,idrs,dropped;
    public volatile String detail="Waiting for video / IDR";
    private static final byte[] FALLBACK_SPS={(byte)0x67,0x64,0,0x20,(byte)0xac,0x4d,0,(byte)0xa0,0x0c,(byte)0xb2};
    private static final byte[] FALLBACK_PPS={0x68,(byte)0xee,0x38,(byte)0xb0};
    public synchronized void surface(Surface s) { releaseCodec();surface=s;synced=false; }
    private void releaseCodec() { if(codec!=null){try{codec.stop();}catch(Exception ignored){}try{codec.release();}catch(Exception ignored){}codec=null;} }
    public synchronized void reset() { releaseCodec();sps=null;pps=null;synced=false;timestamp=0;rendered=errors=idrs=dropped=0;detail="Waiting for video / IDR"; }
    public synchronized void loss() { synced=false;detail="Packet loss · waiting for IDR"; }
    private byte[] csd(byte[] nal) { byte[] out=new byte[nal.length+4];out[3]=1;System.arraycopy(nal,0,out,4,nal.length);return out; }
    public synchronized void feed(byte[] frame) {
        for(byte[] unit:LspAssembler.accessUnits(frame))feedUnit(unit);
    }
    private void feedUnit(byte[] frame) {
        List<byte[]> nals=LspAssembler.nals(frame);boolean idr=false,vcl=false,changed=false;
        for(byte[] n:nals) { int type=n[0]&31;
            if(type==7&&!Arrays.equals(sps,n)){sps=n;changed=true;}if(type==8&&!Arrays.equals(pps,n)){pps=n;changed=true;}
            if(type==5)idr=true;if(type==1||type==5)vcl=true;
        }
        if(changed){releaseCodec();synced=false;}
        if(!vcl||surface==null||!surface.isValid())return;
        if(idr)idrs++;
        if(!synced&&!idr)return;
        try {
            if(!synced||codec==null) {
                if(!idr)return;
                releaseCodec();
                MediaFormat format=MediaFormat.createVideoFormat("video/avc",1280,800);
                format.setByteBuffer("csd-0",ByteBuffer.wrap(csd(sps==null?FALLBACK_SPS:sps)));
                format.setByteBuffer("csd-1",ByteBuffer.wrap(csd(pps==null?FALLBACK_PPS:pps)));
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2*1024*1024);
                codec=MediaCodec.createDecoderByType("video/avc");codec.configure(format,surface,null,0);codec.start();synced=true;
            }
            drain();int index=codec.dequeueInputBuffer(0);
            if(index<0){dropped++;loss();return;}
            ByteBuffer input=codec.getInputBuffer(index);
            if(input==null||input.capacity()<frame.length){errors++;loss();releaseCodec();return;}
            input.clear();input.put(frame);timestamp=Math.max(timestamp+1,System.nanoTime()/1000);
            codec.queueInputBuffer(index,0,frame.length,timestamp,0);drain();detail="H.264 · "+rendered+" displayed · "+dropped+" dropped";
        }catch(Exception e){errors++;releaseCodec();synced=false;detail="Decoder recovery: "+e.getClass().getSimpleName();}
    }
    public synchronized void drain() {
        if(codec==null)return;
        try { MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int i;
            while((i=codec.dequeueOutputBuffer(info,0))>=0){boolean picture=(info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0;codec.releaseOutputBuffer(i,picture);if(picture)rendered++;}
        }catch(Exception e){errors++;releaseCodec();synced=false;}
    }
    public synchronized void close(){reset();surface=null;}
}
