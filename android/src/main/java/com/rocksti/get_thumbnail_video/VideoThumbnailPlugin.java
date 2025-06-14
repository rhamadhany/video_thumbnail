package com.rocksti.get_thumbnail_video;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/**
 * VideoThumbnailPlugin
 */
public class VideoThumbnailPlugin implements FlutterPlugin, MethodCallHandler {
    private static final String TAG = "ThumbnailPlugin";

    private Context context;
    private ExecutorService executor;
    private MethodChannel channel;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
        context = binding.getApplicationContext();
        executor = Executors.newCachedThreadPool();
        channel = new MethodChannel(binding.getBinaryMessenger(), "video_thumbnail");
        channel.setMethodCallHandler(this);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        channel = null;
        executor.shutdown();
        executor = null;
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull final Result result) {
        final Map<String, Object> args = call.arguments();

        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) args.get("headers");

        final String video = (String) args.get("video");
        final int format = (int) args.get("format");
        final int maxh = (int) args.get("maxh");
        final int maxw = (int) args.get("maxw");
        final int timeMs = (int) args.get("timeMs");
        final int quality = (int) args.get("quality");
        final String method = call.method;

        executor.execute(() -> {
            Object thumbnail = null;
            boolean handled = false;
            Exception exc = null;

            try {
                if (method.equals("file")) {
                    final String path = (String) args.get("path");
                    thumbnail = buildThumbnailFile(video, headers, path, format, maxh, maxw, timeMs, quality);
                    handled = true;
                } else if (method.equals("data")) {
                    thumbnail = buildThumbnailData(video, headers, format, maxh, maxw, timeMs, quality);
                    handled = true;
                }
            } catch (Exception e) {
                exc = e;
            }

            onResult(result, thumbnail, handled, exc);
        });
    }

    private static Bitmap.CompressFormat intToFormat(int format) {
        switch (format) {
            default:
            case 0:
                return Bitmap.CompressFormat.JPEG;
            case 1:
                return Bitmap.CompressFormat.PNG;
            case 2:
                return Bitmap.CompressFormat.WEBP_LOSSLESS;
        }
    }

    private static String formatExt(int format) {
        switch (format) {
            default:
            case 0:
                return "jpg";
            case 1:
                return "png";
            case 2:
                return "webp";
        }
    }

    private byte[] buildThumbnailData(final String vidPath, final Map<String, String> headers, int format, int maxh,
                                     int maxw, int timeMs, int quality) {
        Bitmap bitmap = createVideoThumbnail(vidPath, headers, maxh, maxw, timeMs);
        if (bitmap == null) {
            return new byte[0]; 
        }

        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(intToFormat(format), quality, stream);
        bitmap.recycle();
        return stream.toByteArray();
    }

    private String buildThumbnailFile(final String vidPath, final Map<String, String> headers, String path,
                                     int format, int maxh, int maxw, int timeMs, int quality) {
        final byte[] bytes = buildThumbnailData(vidPath, headers, format, maxh, maxw, timeMs, quality);
        if (bytes.length == 0) {
            throw new RuntimeException("Failed to generate thumbnail for video: " + vidPath);
        }

        final String ext = formatExt(format);
        final int i = vidPath.lastIndexOf(".");
        String fullpath = vidPath.substring(0, i + 1) + ext;
        final boolean isLocalFile = (vidPath.startsWith("/") || vidPath.startsWith("file://"));

        if (path == null && !isLocalFile) {
            path = context.getCacheDir().getAbsolutePath();
        }

        if (path != null) {
            if (path.endsWith(ext)) {
                fullpath = path;
            } else {
                final int j = fullpath.lastIndexOf("/");
                if (path.endsWith("/")) {
                    fullpath = path + fullpath.substring(j + 1);
                } else {
                    fullpath = path + fullpath.substring(j);
                }
            }
        }

        try {
            FileOutputStream f = new FileOutputStream(fullpath);
            f.write(bytes);
            f.close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to write thumbnail file: " + e.getMessage(), e);
        }
        return fullpath;
    }

    private void onResult(final Result result, final Object thumbnail, final boolean handled, final Exception e) {
        runOnUiThread(() -> {
            if (!handled) {
                result.notImplemented();
                return;
            }

            if (e != null) {
                result.error("exception", e.getMessage(), null);
                return;
            }

            if (thumbnail == null || (thumbnail instanceof byte[] && ((byte[]) thumbnail).length == 0)) {
                result.error("no_thumbnail", "No valid thumbnail could be generated", null);
                return;
            }

            result.success(thumbnail);
        });
    }

    private static void runOnUiThread(Runnable runnable) {
        new Handler(Looper.getMainLooper()).post(runnable);
    }

    /**
     * Create a video thumbnail for a video. May return null if the video is corrupt
     * or the format is not supported.
     *
     * @param video   the URI of video
     * @param targetH the max height of the thumbnail
     * @param targetW the max width of the thumbnail
     */
    public Bitmap createVideoThumbnail(final String video, final Map<String, String> headers, int targetH,
                                      int targetW, int timeMs) {
        Bitmap bitmap = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (video.startsWith("/")) {
                File videoFile = new File(video);
                if (!videoFile.exists() || !videoFile.canRead()) {
                    throw new IOException("Video file does not exist or is not readable: " + video);
                }
                setDataSource(video, retriever);
            } else if (video.startsWith("file://")) {
                File videoFile = new File(video.substring(7));
                if (!videoFile.exists() || !videoFile.canRead()) {
                    throw new IOException("Video file does not exist or is not readable: " + video);
                }
                setDataSource(video.substring(7), retriever);
            } else {
                retriever.setDataSource(video, (headers != null) ? headers : new HashMap<>());
            }

            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            bitmap = tryExtractFrame(retriever, timeMs, targetH, targetW);
            if (bitmap == null) {
                long durationMs = duration != null ? Long.parseLong(duration) : 0;
                long[] fallbackTimes = {0, durationMs / 2, durationMs / 4};
                for (long fallbackTime : fallbackTimes) {
                    bitmap = tryExtractFrame(retriever, (int) fallbackTime, targetH, targetW);
                    if (bitmap != null) {
                        Log.d(TAG, "Thumbnail extracted at fallback time: " + fallbackTime + "ms");
                        break;
                    }
                }
            }

            

        } catch (Exception ex) {
            Log.e(TAG, "Error creating thumbnail for video: " + video, ex);
        } finally {
            try {
                retriever.release();
            } catch (Exception ex) {
                Log.e(TAG, "Error releasing retriever", ex);
            }
        }

        return bitmap;
    }

    private Bitmap tryExtractFrame(MediaMetadataRetriever retriever, int timeMs, int targetH, int targetW) {
        Bitmap bitmap = null;
        try {
            if (targetH != 0 || targetW != 0) {
                if (android.os.Build.VERSION.SDK_INT >= 27 && targetH != 0 && targetW != 0) {
                    bitmap = retriever.getScaledFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST,
                            targetW, targetH);
                } else {
                    bitmap = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (bitmap != null) {
                        int width = bitmap.getWidth();
                        int height = bitmap.getHeight();
                        if (targetW == 0) {
                            targetW = Math.round(((float) targetH / height) * width);
                        }
                        if (targetH == 0) {
                            targetH = Math.round(((float) targetW / width) * height);
                        }
                        bitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
                    }
                }
            } else {
                bitmap = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to extract frame at " + timeMs + "ms", e);
        }
        return bitmap;
    }

    private static void setDataSource(String video, final MediaMetadataRetriever retriever) throws IOException {
        File videoFile = new File(video);
        try (FileInputStream inputStream = new FileInputStream(videoFile.getAbsolutePath())) {
            retriever.setDataSource(inputStream.getFD());
        } catch (Exception e) {
            throw new IOException("Failed to set data source for video: " + video, e);
        }
    }
}
