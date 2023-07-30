package com.nyu.video_imu_recorder;

import android.Manifest;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.camera2.Camera2Config;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraXConfig;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
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
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VideoRecord extends IMUCapture implements CameraXConfig.Provider {

    private static final int CAMERA_PERMISSION = new SecureRandom().nextInt(100);
    private static final String CAM = "Capture_use_cases";
    private VideoCapture<Recorder> video_capture;
    private Recording video_recording;
    private long last_save_time = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_record);
        String imu_data_name = getIntent().getStringExtra("imu_data_name");
        String media_name = getIntent().getStringExtra("media_name");

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }

        // Establish camera controls and start recording
        PreviewView camera_preview = findViewById(R.id.camera_preview);
        ListenableFuture<ProcessCameraProvider> camera_provider_future = ProcessCameraProvider.getInstance(this);
        camera_provider_future.addListener(() -> {
            try {
                ProcessCameraProvider camera_provider = camera_provider_future.get();
                bindPreviewAndVideo(camera_provider, camera_preview);
                startDataCollection(media_name, imu_data_name);
            } catch (IOException | InterruptedException | ExecutionException | IllegalArgumentException exception) {
                exception.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));

        // Stop recording and save the video and imu data upon clicking camera preview
        camera_preview.setOnClickListener(view -> {
            video_recording.stop();
            stopIMURecording();
            finish();
        });
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

    @NonNull
    @Override
    public CameraXConfig getCameraXConfig() {
        return CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
                .setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA).build();
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private VideoCapture<Recorder> setUpVideoCapture(ProcessCameraProvider camera_provider) {
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
        return VideoCapture.withOutput(recorder);
    }

    private void bindPreviewAndVideo(ProcessCameraProvider camera_provider, PreviewView camera_preview) {
        // Prepare screen to display camera preview
        Preview preview = new Preview.Builder().build();
        CameraSelector camera_selector = new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();
        preview.setSurfaceProvider(camera_preview.getSurfaceProvider());

        // Connect camera preview and video capture to the application
        video_capture = setUpVideoCapture(camera_provider);
        try {
            camera_provider.bindToLifecycle(this, camera_selector, preview, video_capture);
        } catch (IllegalArgumentException illegal_argument_exception) {
            Toast.makeText(this, "Failed to initialize camera", Toast.LENGTH_LONG).show();
            Log.e(CAM, "Preview or video capture use case binding failed");
            throw illegal_argument_exception;
        }
    }

    private void startDataCollection(String media_name, String imu_data_name) throws IOException {
        if (video_capture == null ) throw new UnsupportedOperationException("VideoCapture instances must be non-null. Did you try to" +
                " start capturing before binding video capture to the activity lifecycle?");

        // Configure video output file location
        File video_location = setIMUFileAndGetMediaLocation(imu_data_name, media_name);
        video_location = new File(video_location.getParentFile(), video_location.getName() + ".mp4");
        FileOutputOptions output_options = new FileOutputOptions.Builder(video_location).build();
        // Enable writing to IMU data file, start recording, and listen for sensor data
        startIMURecording();
        video_recording = video_capture.getOutput().prepareRecording(this, output_options)
                .start(ContextCompat.getMainExecutor(this), record_event_listener);
    }

    // This is the callback handed to video capture to handle recording related events
    private final Consumer<VideoRecordEvent> record_event_listener = video_record_event -> {

        if (video_record_event instanceof VideoRecordEvent.Start) {
            // Note the start time of the recording in the imu data file
            notifyVideoStart(SystemClock.elapsedRealtimeNanos());
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();

        } else if (video_record_event instanceof VideoRecordEvent.Pause) {
            stopIMURecording();
            Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show();
        } else if (video_record_event instanceof VideoRecordEvent.Resume) {
            startIMURecording();
            Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show();

        } else if (video_record_event instanceof VideoRecordEvent.Finalize) {
            // check if recording has any errors
            VideoRecordEvent.Finalize finalize_event = (VideoRecordEvent.Finalize) video_record_event;
            String final_message = finalize_event.getClass().getName();
            if (finalize_event.getError() != VideoRecordEvent.Finalize.ERROR_NONE) {
                Toast.makeText(this, "Recording error!", Toast.LENGTH_SHORT).show();
                assert finalize_event.getCause() != null;
                final_message += finalize_event.getCause().getMessage();
                Log.e(CAM, finalize_event.getCause().getMessage());
            } else {
                Toast.makeText(this, "Recording success", Toast.LENGTH_SHORT).show();
            }
            broadcastRecordStatus(final_message);
            Log.i(CAM, "Video path: " + finalize_event.getOutputResults().getOutputUri().getPath());
            return;
        }

        // Close and re-open the FileOutputStream object every 3 seconds to avoid overwhelming file write buffer
        long current_time = video_record_event.getRecordingStats().getRecordedDurationNanos();
        if ((current_time - last_save_time > 3 * Math.pow(10, 9))) {
            last_save_time = current_time;
            generatePositionData();
            stopIMURecording();
            startIMURecording();
        }
    };
}