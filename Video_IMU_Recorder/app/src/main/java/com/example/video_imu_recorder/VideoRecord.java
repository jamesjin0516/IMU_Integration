package com.example.video_imu_recorder;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VideoRecord extends AppCompatActivity implements SensorEventListener {

    private static final int CAMERA_PERMISSION = new SecureRandom().nextInt(100);
    private static final String CAM = "Video record", FILE = "IMU data file";
    private VideoCapture<Recorder> video_capture;
    private Recording video_recording;
    private SensorManager sensor_manager;
    private Sensor linear_accelerometer, gyroscope;
    private File imu_data;
    private FileOutputStream output_stream;
    private static long record_start_time = Long.MIN_VALUE, last_save_time = 0;

    /*
    The IMU data is stored under the following format (A more sophisticated tabular format is also possible in Android)
    <nano seconds elapsed since an external time instant> <sensor name> <[a comma-separated list with sensor data]>
    Example:
    1578699792815 Goldfish 3-axis Gyroscope [0.0, 0.0, 0.0]
    */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);
        String imu_data_name = getIntent().getStringExtra("imu_data_name");
        String video_name = getIntent().getStringExtra("video_name");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }

        sensor_manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        linear_accelerometer = sensor_manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gyroscope = sensor_manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        prepareIMUDataFile(imu_data_name);

        // Establish camera controls and start recording
        PreviewView camera_preview = findViewById(R.id.camera_preview);
        ListenableFuture<ProcessCameraProvider> camera_provider_future = ProcessCameraProvider.getInstance(this);
        camera_provider_future.addListener(() -> {
            try {
                ProcessCameraProvider camera_provider = camera_provider_future.get();
                bindPreviewAndVideo(camera_provider, camera_preview);
                startVideoCapture(video_name);
            } catch (ExecutionException | InterruptedException exception) {
                exception.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));

        // Stop recording and save the video upon clicking camera preview
        camera_preview.setOnClickListener(view -> {
            video_recording.stop();
            // On emulator, videos are saved to /storage/emulated/0/Movies/
            finish();
        });
    }

    private void prepareIMUDataFile(String filename) {
        /*
        Create a new file to store IMU measurement data. Location (on emulator):
        /storage/emulated/0/Android/data/com.example.video_imu_recorder/files/Documents
        */
        imu_data = new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DOCUMENTS)[0], filename);
        try {
            imu_data.createNewFile();
            Log.d(FILE, "imu data file (exists: " + imu_data.exists() + ") at " + imu_data.getPath());
        } catch (IOException exception) {
            exception.printStackTrace();
            Log.e(FILE, "Creation failed: " + imu_data.getPath());
            Toast.makeText(this, "IMU data storage file cannot be created", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void openIMUDataOutputStream() {
        try {
            output_stream = new FileOutputStream(imu_data, true);
        } catch (FileNotFoundException exception) {
            Log.e(FILE, "FileOutputStream failed to be opened");
            exception.printStackTrace();
            Toast.makeText(this, "IMU data storage file cannot be opened", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void closeIMUDataOutputStream() {
        try {
            output_stream.close();
        } catch (IOException exception) {
            Log.e(FILE, "FileOutputStream failed to close");
            Toast.makeText(this, "IMU data failed to save, data lost!", Toast.LENGTH_SHORT).show();
            exception.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "camera permission granted", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "camera permission denied, aborting", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindPreviewAndVideo(ProcessCameraProvider camera_provider, PreviewView camera_preview) {
        // Prepare screen to display camera preview
        Preview preview = new Preview.Builder().build();
        CameraSelector camera_selector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
        preview.setSurfaceProvider(camera_preview.getSurfaceProvider());

        // Find available image qualities for back camera
        CameraInfo back_camera_info = camera_provider.getAvailableCameraInfos().stream().filter(camera_info ->
                Camera2CameraInfo.from(camera_info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
        ).collect(Collectors.toList()).get(0);
        Stream<Quality> available_qualities = QualitySelector.getSupportedQualities(back_camera_info).stream().filter(
                quality -> Arrays.asList(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).contains(quality)
        );

        // Prepare video capture with the first available image quality
        QualitySelector quality_selector = QualitySelector.from(available_qualities.findFirst().orElseThrow(NoSuchElementException::new));
        Recorder recorder = new Recorder.Builder().setExecutor(ContextCompat.getMainExecutor(this))
                .setQualitySelector(quality_selector).build();
        video_capture = VideoCapture.withOutput(recorder);

        // Connect camera preview and video capture to the application
        try {
            camera_provider.bindToLifecycle(this, camera_selector, preview, video_capture);
        } catch (IllegalArgumentException illegal_argument_exception) {
            Log.e(CAM, "Preview or video capture use case binding failed");
            throw illegal_argument_exception;
        }
    }

    private void startVideoCapture(String video_name) {
        if (video_capture == null) throw new UnsupportedOperationException("VideoCapture instance must be non-null." +
                " Did you try to start capturing before binding video capture to the activity lifecycle?");
        // Configure video output file location
        ContentValues content_values = new ContentValues();
        content_values.put(MediaStore.Video.Media.DISPLAY_NAME, video_name);
        MediaStoreOutputOptions output_options = new MediaStoreOutputOptions.Builder(getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(content_values).build();
        // Start recording and listen for events during recording
        video_recording = video_capture.getOutput().prepareRecording(this, output_options)
                .start(ContextCompat.getMainExecutor(this), record_event_listener);
    }

    private final Consumer<VideoRecordEvent> record_event_listener = video_record_event -> {

        // Close and re-open the FileOutputStream object every 3 seconds to avoid overwhelming file write buffer
        long current_time = video_record_event.getRecordingStats().getRecordedDurationNanos();
        if ((current_time - last_save_time > 3 * Math.pow(10, 9))) {
            last_save_time = current_time;
            closeIMUDataOutputStream();
            openIMUDataOutputStream();
        }

        if (video_record_event instanceof VideoRecordEvent.Start) {
            record_start_time = SystemClock.elapsedRealtimeNanos();
            // Enable writing to IMU data file, start receiving sensor data, and broadcast recording start
            openIMUDataOutputStream();
            sensor_manager.registerListener(this, linear_accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            sensor_manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();

        } else if (video_record_event instanceof VideoRecordEvent.Pause) {
            sensor_manager.unregisterListener(this);
            Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show();
        } else if (video_record_event instanceof VideoRecordEvent.Resume) {
            sensor_manager.registerListener(this, linear_accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            sensor_manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
            Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show();

        } else if (video_record_event instanceof VideoRecordEvent.Finalize) {
            // Stop reading from sensors, close imu data file, and check if recording has any errors
            sensor_manager.unregisterListener(this);
            closeIMUDataOutputStream();
            VideoRecordEvent.Finalize finalize_event = (VideoRecordEvent.Finalize) video_record_event;
            String final_message = finalize_event.getClass().getName();
            if (finalize_event.getError() != VideoRecordEvent.Finalize.ERROR_NONE) {
                Toast.makeText(this, "Recording error!", Toast.LENGTH_SHORT).show();
                assert finalize_event.getCause() != null;
                final_message += finalize_event.getCause().getMessage();
                Log.e(CAM, finalize_event.getCause().getMessage());
            }
            broadcast_record_status(final_message);
            Log.i(CAM, "Video path: " + finalize_event.getOutputResults().getOutputUri().getPath());
        }
    };

    private void broadcast_record_status(String status) {
        Log.i(CAM, "status to broadcast: " + status);
        Intent broadcast = new Intent();
        broadcast.setAction(getPackageName() + ".RECORD_STATUS");
        broadcast.putExtra("status", status);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (record_start_time != Long.MIN_VALUE) {
            Log.i(FILE, "Latency: " + (SystemClock.elapsedRealtimeNanos() - record_start_time));
            record_start_time = Long.MIN_VALUE;
        }
        String data = SystemClock.elapsedRealtimeNanos() + " " + event.sensor.getName() + " " + Arrays.toString(event.values) + "\n";
        Log.v(FILE, "imu data: " + data);
        try {
            output_stream.write(data.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.v(FILE, sensor.getName() + " accuracy changed to " + accuracy);
    }
}