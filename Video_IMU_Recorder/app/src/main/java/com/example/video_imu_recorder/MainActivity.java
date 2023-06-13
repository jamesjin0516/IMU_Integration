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
import android.util.Pair;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String VID = "Video logistics";
    private RecordStatusReceiver record_status_receiver;
    private static int record_count = 1;

    public class RecordStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            Log.i(VID, "status received from broadcast: " + status);
            if (status.contains(VideoRecordEvent.Finalize.class.getName())) {
                if (status.equals(VideoRecordEvent.Finalize.class.getName())) {
                    ++record_count;
                    Log.i(VID, "Incremented record_count, now at " + record_count);
                }
                unregisterReceiver(this);
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Prepare for launching video capturing activity
        AppCompatButton record_start = findViewById(R.id.record_start);
        record_start.setOnClickListener(view -> {
            record_status_receiver = new RecordStatusReceiver();
            IntentFilter filter = new IntentFilter(getPackageName() + ".RECORD_STATUS");
            ContextCompat.registerReceiver(this, record_status_receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

            Intent launch_record = new Intent(this, VideoRecord.class);
            /*
            Locate back-facing camera (Technically this should be implemented like below, but it doesn't work because of emulator I think)
            PackageManager package_manager = getPackageManager();
            boolean has_back_camera = package_manager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
            This code seems to always produce false when run on emulator
            */
            try {
                CameraManager camera_manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] camera_IDs = camera_manager.getCameraIdList();
                Log.v(VID, "Camera-IDs: " + Arrays.toString(camera_IDs));
                for (String camera_id : camera_IDs) {
                    if (camera_manager.getCameraCharacteristics(camera_id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                        Log.d(VID, "Found back-facing camera with id " + camera_id);
                        launch_record.putExtra("back_camera_id", camera_id);
                        break;
                    }
                }
            } catch (CameraAccessException exception) {
                exception.printStackTrace();
            }
            if (! launch_record.hasExtra("back_camera_id")) {
                throw new UnsupportedOperationException("The device does not have a usable back camera for video recording.");
            }
            // Create appropriate file names for video recording and imu data given number of recordings made
            Pair<String, String> file_names = generateFileNames(record_count);
            launch_record.putExtra("video_name", file_names.first);
            launch_record.putExtra("imu_data_name", file_names.second);
            startActivity(launch_record);
        });
    }

    private Pair<String, String> generateFileNames(int record_count) {
        SimpleDateFormat date_format = new SimpleDateFormat("MMM_dd_yyyy", Locale.US);
        String date = date_format.format(new Date());
        String video_name = date + "_video_" + record_count + ".mp4";
        String imu_data_name = date + "_IMU_data_" + record_count + ".txt";
        return new Pair<>(video_name, imu_data_name);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(record_status_receiver);
    }
}