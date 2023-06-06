package com.example.video_imu_recorder;

import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.CaptureVideo;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.core.content.FileProvider;

import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private final ActivityResultLauncher<Uri> video_record = registerForActivityResult(new CaptureVideo(), new ActivityResultCallback<Boolean>() {
        @Override
        public void onActivityResult(Boolean result) {
            // Very strangely, despite the video successfully saving to the desired location, the result is set to false
            Log.d("Video capture", "result (" + result + ")");
            if (result) {
                Toast.makeText(MainActivity.this, "Successfully captured video", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "Failed to capture video", Toast.LENGTH_SHORT).show();
            }
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        AppCompatButton record_start = findViewById(R.id.record_start);
        record_start.setOnClickListener(v -> {
            // The video file should be saved to /sdcard/DCIM/, which should also appear somewhere under /storage/
            File video_file =  new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/video.mp4");
            Uri video_uri = FileProvider.getUriForFile(this, this.getApplicationContext().getPackageName() + ".provider", video_file);
            video_record.launch(video_uri);
        });
    }
}