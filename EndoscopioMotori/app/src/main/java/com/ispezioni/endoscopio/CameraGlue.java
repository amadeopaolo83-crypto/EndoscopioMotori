package com.ispezioni.endoscopio;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.herohan.uvcapp.CameraHelper;
import com.herohan.uvcapp.ImageCapture;
import com.herohan.uvcapp.ImageCaptureConfig;
import com.herohan.uvcapp.VideoCapture;
import com.herohan.uvcapp.VideoCaptureConfig;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Colla Java verso la libreria UVCAndroid (le sue classi interne sono pensate per Java).
 * Foto e video vengono scritti in un file temporaneo; poi MainActivity li copia in galleria.
 */
public final class CameraGlue {

    public interface Done {
        void ok();

        void fail(String message);
    }

    private CameraGlue() {
    }

    /** JPG alla massima qualità e audio on/off. Da chiamare prima di aprire la camera. */
    public static void configure(CameraHelper helper, boolean audio) {
        try {
            ImageCaptureConfig ic = helper.getImageCaptureConfig();
            if (ic != null) {
                trySet(ic, "setJpegCompressionQuality", int.class, 100);
                helper.setImageCaptureConfig(ic);
            }
        } catch (Throwable ignored) {
        }
        try {
            VideoCaptureConfig vc = helper.getVideoCaptureConfig();
            if (vc != null) {
                trySet(vc, "setAudioCaptureEnable", boolean.class, audio);
                helper.setVideoCaptureConfig(vc);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trySet(Object target, String name, Class<?> type, Object value) {
        try {
            Method m = target.getClass().getMethod(name, type);
            m.invoke(target, value);
        } catch (Throwable ignored) {
        }
    }

    public static void takePicture(CameraHelper helper, File out, final Done done) {
        ImageCapture.OutputFileOptions options = new ImageCapture.OutputFileOptions.Builder(out).build();
        helper.takePicture(options, new ImageCapture.OnImageCaptureCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults results) {
                done.ok();
            }

            @Override
            public void onError(int imageCaptureError, @NonNull String message, @Nullable Throwable cause) {
                done.fail(message + " (" + imageCaptureError + ")");
            }
        });
    }

    /** done.ok() arriva quando il video è stato chiuso e salvato (dopo stopRecording). */
    public static void startRecording(CameraHelper helper, File out, final Done done) {
        VideoCapture.OutputFileOptions options = new VideoCapture.OutputFileOptions.Builder(out).build();
        helper.startRecording(options, new VideoCapture.OnVideoCaptureCallback() {
                        @Override
            public void onStart() {
            }
            @Override
            public void onVideoSaved(@NonNull VideoCapture.OutputFileResults results) {
                done.ok();
            }

            @Override
            public void onError(int videoCaptureError, @NonNull String message, @Nullable Throwable cause) {
                done.fail(message + " (" + videoCaptureError + ")");
            }
        });
    }

    public static void stopRecording(CameraHelper helper) {
        helper.stopRecording();
    }
}
