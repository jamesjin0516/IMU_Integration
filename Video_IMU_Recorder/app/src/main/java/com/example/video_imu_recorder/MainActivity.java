package com.example.video_imu_recorder;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private static final String VID = "Video logistics";
    private RecordStatusReceiver record_status_receiver;

    public class RecordStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // TODO: use the received broadcasts of video recording start & end to increment the video & IMU data file name
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CameraManager camera_manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        setContentView(R.layout.activity_main);

        // Prepare for launching video capturing activity
        AppCompatButton record_start = findViewById(R.id.record_start);
        record_start.setOnClickListener(view -> {
            record_status_receiver = new RecordStatusReceiver();
            IntentFilter filter = new IntentFilter(getPackageName() + "." + VideoRecordEvent.Start.class);
            ContextCompat.registerReceiver(this, record_status_receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

            Intent record_data = new Intent(this, VideoRecord.class);
            // Locate back-facing camera
            // TODO: instead of checking if device has back camera, formulate a new video and IMU data file name and send to VideoCapture
            try {
                String[] camera_IDs = camera_manager.getCameraIdList();
                Log.d(VID, "Camera-IDs: " + Arrays.toString(camera_IDs));
                for (String camera_id : camera_IDs) {
                    if (camera_manager.getCameraCharacteristics(camera_id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                        Log.i(VID, "Found back-facing camera with id " + camera_id);
                        record_data.putExtra("back_camera_id", camera_id);
                        break;
                    }
                }
            } catch (CameraAccessException exception) {
                exception.printStackTrace();
            }
            if (! record_data.hasExtra("back_camera_id")) {
                throw new UnsupportedOperationException("The device does not have a usable back camera for video recording.");
            }
            startActivity(record_data);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (record_status_receiver != null) unregisterReceiver(record_status_receiver);
    }
}