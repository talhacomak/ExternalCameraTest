package com.hsj.camera.externalcameratest;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {
    private final int MY_PERMISSIONS_REQUEST = 113;
    private boolean isRecording = false;
    private Timer timer;
    private CamView mCameraView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Context context = this;

        mCameraView = findViewById(R.id.camera_view);
        setLayoutParams(mCameraView);
        ImageView captureBtn = findViewById(R.id.capture);
        captureBtn.setOnClickListener(v -> {
            if (!isRecording){
                File videoFile = null;
                try {
                    Uri uri = createVideoFile(context);
                    // If you don't use pfd, the file can't open at JNI side!
                    ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
                    assert pfd != null;
                    int fd = pfd.getFd();
                    pfd.close();
                    videoFile = new File(getPathFromUri(context, uri));
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (videoFile != null) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(context, "Audio Permission Problem!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    mCameraView.changeRecordingState(videoFile, 30);
                    isRecording = true;
                    Log.d("TAG", "record file: " + videoFile.getPath());
                    Toast.makeText(context, "Recording Started...", Toast.LENGTH_SHORT).show();
                }
            }
            else {
                mCameraView.changeRecordingState(false);
                isRecording = false;
                Toast.makeText(context, "Recording Finished.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setLayoutParams(CamView mCameraView) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
        int screen_width = displayMetrics.widthPixels;
        int screen_height = displayMetrics.heightPixels;
        int res_W = 2048;
        int res_H = 1080;

        float scale;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) mCameraView.getLayoutParams();
        if ((((float)screen_width)/res_W) < ((float)screen_height)/res_H) {
            scale = ((float) screen_width) / res_W;
            params.width = screen_width;
            params.height = Math.round(scale * res_H);
        }
        else {
            scale = ((float) screen_height) / res_H;
            params.width = Math.round(scale * res_W);
            params.height = screen_height;
        }
        //mCameraView.getHolder().setFixedSize(params.width, params.height);
        mCameraView.setLayoutParams(params);
    }

    public Uri createVideoFile(Context context) {
        // Use date format to create a unique file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String videoFileName = "VIDEO_" + timeStamp;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android Q and above
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, videoFileName + ".mp4");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
            return context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        } else {
            // For below Android Q
            File mediaDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VideoTest");
            if (!mediaDir.exists()) {
                mediaDir.mkdirs();
            }
            File videoFile = new File(mediaDir, videoFileName + ".mp4");
            return Uri.fromFile(videoFile);
        }
    }

    public String getPathFromUri(Context context, Uri uri) {
        String[] projection = { MediaStore.Video.Media.DATA };
        Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null);
        if (cursor != null) {
            int column_index = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA);
            cursor.moveToFirst();
            String path = cursor.getString(column_index);
            cursor.close();
            return path;
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{
                            Manifest.permission.RECORD_AUDIO}, MY_PERMISSIONS_REQUEST);
            return;
        }

        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> mCameraView.requestRender());
            }
        }, 2000, 1000/30);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (timer != null) {
                timer.cancel();
            }
        }
        catch (Exception ignored){}
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MY_PERMISSIONS_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("TAG", "permission granted.");
                timer = new Timer();
                timer.scheduleAtFixedRate(new TimerTask() {
                    @Override
                    public void run() {
                        runOnUiThread(() -> mCameraView.requestRender());
                    }
                }, 2000, 1000/30);
            } else {
                Log.d("TAG", "permission denied!");
            }
        }
    }
}
