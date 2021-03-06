package com.videomaker.photowithsong.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.view.Surface;

import com.videomaker.photowithsong.helper.OnUpdateProcessingVideo;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Created by DaiPhongPC on 8/22/2017.
 */

public class VideoUtils {

    private static final boolean VERBOSE = false;
    /**
     * Thuộc tính của file xuất video/avc dạng video
     **/
    private static final String MIME_TYPE = "video/avc";

    /**
     * Bitrate của video
     **/
    private static final int BIT_RATE = 2000000;
    /**
     * Số lượng frame trên 1 giây,
     * càng lớn sẽ càng mượt nhưng mắt thường khó cảm nhận được hết,
     * với các video HD  hiện tại trên Youtube đang ở mức 28
     **/
    public static final int FRAMES_PER_SECOND = 20;
    private static final int IFRAME_INTERVAL = 5;
    /**
     * Khai báo width, height của video, các bạn có thể thay đổi thành video HD tuỳ ý muốn
     **/
    public static int VIDEO_WIDTH = 640;
    public static int VIDEO_HEIGHT = 480;

    // "live" state during recording
    private MediaCodec.BufferInfo mBufferInfo;
    private MediaCodec mEncoder;
    private MediaMuxer mMuxer;
    private Surface mInputSurface;
    private int mTrackIndex;
    private boolean mMuxerStarted;
    private long mFakePts;
    private Context context;
    private File output;
    private ArrayList<Bitmap> lsBitmap;
    private ArrayList<String> lsPathBitmap;
    /**
     * Vì ở đây ta sử dụng bitmap để vẽ, vì vậy cần có thuộc tính FILTER_BITMAP_FLAG để khi zoom ảnh không bị vỡ hoặc nhoè ảnh
     **/
    private Paint paint = new Paint();

    /**
     * Có thể định nghĩa trước số frame, ví dụ muốn tạo video 5giây, số frame = FRAMES_PER_SECOND * 5;
     **/
    public static int maxFrame;

    public static OnUpdateProcessingVideo onUpdateProcessingVideo = null;

    public VideoUtils(Context context, ArrayList<String> lsPathBitmap, String path) {
        this.context = context;
        this.lsPathBitmap = lsPathBitmap;
        try {
            output = new File(path);
            prepareEncoder(output);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String makeVideo_() {
        try {
            /** Tạo ra video có thời lượng là 5giây **/
            //5s=maxFrame/FRAMES_PER_SECOND
            maxFrame = (lsPathBitmap.size()) * FRAMES_PER_SECOND;
            for (int i = 0; i < maxFrame; i++) {
//                // chuẩn bị cho việc vẽ lên surface
                drainEncoder(false);
                Bitmap bitmap = makeScaled(Constant.getBitmapFromLocalPath(lsPathBitmap.get(i / FRAMES_PER_SECOND)));
                generateFrame_B(bitmap);

//                /** Tính toán percent exported, để có thể đưa ra dialog thông báo cho người dùng, cho họ biết còn cần phải chờ bao lâu nữa **/
                int percent = (int) (100.0 * i / maxFrame);
                onUpdateProcessingVideo.uploadIUVideo(percent);
                Log.d("DEBUG", "uploading " + percent);
            }
            drainEncoder(true);
        } finally {
            releaseEncoder();
        }
        return output.getAbsolutePath();
    }

    /**
     * Prepares the video encoder, muxer, and an input surface.
     */
    private void prepareEncoder(File outputFile) throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();

        MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT);

        // Set some properties.  Failing to specify some of these can cause the MediaCodec
        // configure() call to throw an unhelpful exception.
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAMES_PER_SECOND);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
        if (VERBOSE) Log.d("DEBBUG", "format: " + format);

        // Create a MediaCodec encoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mInputSurface = mEncoder.createInputSurface();
        mEncoder.start();

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the encoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        if (VERBOSE) Log.d("DEBUG", "output will go to " + outputFile);
        mMuxer = new MediaMuxer(outputFile.toString(),
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        mTrackIndex = -1;
        mMuxerStarted = false;
    }

    /**
     * Releases encoder resources.  May be called after partial / failed initialization.
     */
    private void releaseEncoder() {
        if (VERBOSE) Log.d("DEBUG", "releasing encoder objects");
        if (mEncoder != null) {
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
        if (mInputSurface != null) {
            mInputSurface.release();
            mInputSurface = null;
        }
        if (mMuxer != null) {
            mMuxer.stop();
            mMuxer.release();
            mMuxer = null;
        }
    }

    private void drainEncoder(boolean endOfStream) {
        /** Thời gian delay giữa 2 frame **/
        final int TIMEOUT_USEC = 2500;
        if (VERBOSE) Log.d("DEBUG", "drainEncoder(" + endOfStream + ")");

        if (endOfStream) {
            if (VERBOSE) Log.d("DEBUG", "sending EOS to encoder");
            mEncoder.signalEndOfInputStream();
        }

        ByteBuffer[] encoderOutputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output available yet
                if (!endOfStream) {
                    break;      // out of while
                } else {
                    if (VERBOSE) Log.d("DEBUG", "no output available, spinning to await EOS");
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                // not expected for an encoder
                encoderOutputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // should happen before receiving buffers, and should only happen once
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                Log.d("DEBUG", "encoder output format changed: " + newFormat);

                // now that we have the Magic Goodies, start the muxer
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxer.start();
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w("DEBUG", "unexpected result from encoder.dequeueOutputBuffer: " +
                        encoderStatus);
                // let's ignore it
            } else {
                ByteBuffer encodedData = encoderOutputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus +
                            " was null");
                }

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    // The codec config data was pulled out and fed to the muxer when we got
                    // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
                    if (VERBOSE) Log.d("DEBUG", "ignoring BUFFER_FLAG_CODEC_CONFIG");
                    mBufferInfo.size = 0;
                }

                if (mBufferInfo.size != 0) {
                    if (!mMuxerStarted) {
                        throw new RuntimeException("muxer hasn't started");
                    }

                    // adjust the ByteBuffer values to match BufferInfo
                    encodedData.position(mBufferInfo.offset);
                    encodedData.limit(mBufferInfo.offset + mBufferInfo.size);
                    mBufferInfo.presentationTimeUs = mFakePts;
                    mFakePts += 1000000L / FRAMES_PER_SECOND;

                    mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                    if (VERBOSE) Log.d("DEBUG", "sent " + mBufferInfo.size + " bytes to muxer");
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w("DEBUG", "reached end of stream unexpectedly");
                    } else {
                        if (VERBOSE) Log.d("DEBUG", "end of stream reached");
                    }
                    break;      // out of while
                }
            }
        }
    }

    private void generateFrame_B(Bitmap bitmap) {
        /** Khởi tạo canvas để vẽ từng frame cho video **/
//        Canvas canvas = new Canvas();
        Canvas canvas = mInputSurface.lockCanvas(null);
        paint.setColor(Color.BLACK);
        try {
            canvas.drawColor(Color.BLACK);
            int cx = (VIDEO_WIDTH - bitmap.getWidth()) / 2;
            int cy = (VIDEO_HEIGHT - bitmap.getHeight()) / 2;
            canvas.drawBitmap(bitmap, cx, cy, paint);

        } finally {
            mInputSurface.unlockCanvasAndPost(canvas);
        }
    }

    public Bitmap makeScaled(Bitmap src) {
        Bitmap output = null;
        try {
            int width = src.getWidth();
            int height = src.getHeight();
            // 480  889
            //
            float scale = (float) VIDEO_HEIGHT / height;
            float scaledWidth = width * scale;
            float scaledHeight = height * scale;
            Matrix m = new Matrix();
            m.setRectToRect(new RectF(0, 0, src.getWidth(), src.getHeight()), new RectF(0, 0, scaledWidth, scaledHeight), Matrix.ScaleToFit.CENTER);
            output = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            Canvas xfas = new Canvas(output);
            Paint paint = new Paint();
            paint.setAntiAlias(true);
            paint.setFilterBitmap(true);
            paint.setDither(true);
            xfas.drawBitmap(output, 0, 0, paint);
            return output;
        } catch (Exception e) {
            e.printStackTrace();
            return src;
        }


    }
}
