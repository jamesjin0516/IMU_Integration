package com.nyu.video_imu_recorder;

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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;
import androidx.appcompat.widget.SwitchCompat;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceFragmentCompat;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String VID = "Recording_logistics";
    private RecordStatusReceiver record_status_receiver;
    private static int record_count = 1;

    public class RecordStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String status = intent.getStringExtra("status");
            SwitchCompat record_mode = findViewById(R.id.record_mode);
            String source = record_mode.isChecked() ? BurstImage.class.getName() : VideoRecordEvent.Finalize.class.getName();
            Log.i(VID, "status received from broadcast: " + status);
            // Verify whether the broadcast indicates that the recording has stopped
            if (status.contains(source)) {
                // Verify whether the broadcast indicates an error-free recording
                if (status.equals(source)) {
                    ++record_count;
                    TextView record_counter = findViewById(R.id.record_counter);
                    record_counter.setText(getString(R.string.count_designator, record_count));
                    Log.i(VID, "Incremented record_count, now at " + record_count);
                }
                safeUnregisterReceiver();
            }
        }
    }

    /*
    Currently, both imu and video data are stored under /sdcard/Android/data/com.nyu.video_imu_recorder

    The IMU data is stored under the following format (A more sophisticated tabular format is also possible in Android)
    <nano seconds elapsed since an external time instant> <sensor name> <[a comma-separated list with sensor data]>
    Example:
    1578699792815 Goldfish 3-axis Gyroscope [0.0, 0.0, 0.0]
    TODO: try to set IMU sensor sampling period close to camera frame rate
    */

    public static class ServerPreferenceFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Prepare for launching data capturing activity
        SwitchCompat record_mode = findViewById(R.id.record_mode);
        AppCompatButton record_start = findViewById(R.id.record_start);
        record_start.setOnClickListener(view -> {
            // Set up broadcast receiver
            record_status_receiver = new RecordStatusReceiver();
            IntentFilter filter = new IntentFilter(getPackageName() + ".RECORD_STATUS");
            ContextCompat.registerReceiver(this, record_status_receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);

            Intent launch_record = new Intent(this, record_mode.isChecked() ? BurstImage.class : VideoRecord.class);
            try {
                launch_record.putExtra("back_camera_id", findBackCameraId());
                // Create appropriate file names for video recording and imu data given number of recordings made
                Pair<String, String> file_names = generateFileNames(record_count);
                launch_record.putExtra("media_name", file_names.first);
                launch_record.putExtra("imu_data_name", file_names.second);
                startActivity(launch_record);
            } catch (CameraAccessException exception) {
                Toast.makeText(this, "The device does not have a usable back camera for recording.", Toast.LENGTH_LONG).show();
                exception.printStackTrace();
                finish();
            }
        });
    }

    private String findBackCameraId() throws CameraAccessException {
        /*
        Locate back-facing camera (Technically this should be implemented like below, but it doesn't work because of emulator I think)
        PackageManager package_manager = getPackageManager();
        boolean has_back_camera = package_manager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY);
        This code seems to always produce false when run on emulator
        */
        CameraManager camera_manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String[] camera_IDs = camera_manager.getCameraIdList();
        Log.v(VID, "Camera-IDs: " + Arrays.toString(camera_IDs));
        for (String camera_id : camera_IDs) {
            if (camera_manager.getCameraCharacteristics(camera_id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                Log.d(VID, "Found back-facing camera with id " + camera_id);
                return camera_id;
            }
        }
        throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "No back-facing camera found.");
    }

    private Pair<String, String> generateFileNames(int record_count) {
        SimpleDateFormat date_format = new SimpleDateFormat("MMM_dd_yyyy", Locale.US);
        String date = date_format.format(new Date());
        String media_name = date + "_IMU_data_" + record_count;
        String imu_data_name = date + "_IMU_data_" + record_count + ".txt";
        return new Pair<>(media_name, imu_data_name);
    }

    @Override
    protected void onResume() {
        super.onResume();
        TextView record_counter = findViewById(R.id.record_counter);
        record_counter.setText(getString(R.string.count_designator, record_count));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        safeUnregisterReceiver();
    }

    private void safeUnregisterReceiver() {
        // Rely on the fact that once the receiver is unregistered, it will be set to null
        if (record_status_receiver != null) {
            unregisterReceiver(record_status_receiver);
            record_status_receiver = null;
        }
    }
}