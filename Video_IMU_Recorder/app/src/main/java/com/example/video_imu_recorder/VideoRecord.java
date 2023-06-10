package com.example.video_imu_recorder;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
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

import com.google.common.util.concurrent.ListenableFuture;

import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class VideoRecord extends AppCompatActivity {

    private static String back_camera_id;
    private static final int CAMERA_PERMISSION = 100;
    private static final String CAM = "Video record";
    private ListenableFuture<ProcessCameraProvider> camera_provider_future;
    private VideoCapture<Recorder> video_capture;
    private Recording video_recording;
    PreviewView camera_preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        back_camera_id = getIntent().getStringExtra("back_camera_id");
        setContentView(R.layout.activity_camera);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }

        // Establish camera controls and start recording
        camera_preview = findViewById(R.id.camera_preview);
        camera_provider_future = ProcessCameraProvider.getInstance(this);
        camera_provider_future.addListener(() -> {
            try {
                ProcessCameraProvider camera_provider = camera_provider_future.get();
                bindPreviewAndVideo(camera_provider);
                startVideoCapture();
            } catch (ExecutionException | InterruptedException exception) {
                exception.printStackTrace();
            }
        }, ContextCompat.getMainExecutor(this));

        // Stop recording and save the video upon clicking camera preview
        findViewById(R.id.camera_preview).setOnClickListener(view -> {
            video_recording.stop();
            // On emulator, videos are saved to /sdcard/Movies/
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
                throw new UnsupportedOperationException("camera permission denied");
            }
        }
    }

    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindPreviewAndVideo(ProcessCameraProvider camera_provider) {
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

    private void startVideoCapture() {
        if (video_capture == null) throw new UnsupportedOperationException("VideoCapture instance must be non-null." +
                " Did you try to start capturing before binding video capture to the activity lifecycle?");
        // Configure video output file location
        String file_name = "Video.mp4";
        ContentValues content_values = new ContentValues();
        content_values.put(MediaStore.Video.Media.DISPLAY_NAME, file_name);
        MediaStoreOutputOptions output_options = new MediaStoreOutputOptions.Builder(getContentResolver(),
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI).setContentValues(content_values).build();

        // Start recording and listen for events during recording
        video_recording = video_capture.getOutput().prepareRecording(this, output_options)
                .start(ContextCompat.getMainExecutor(this), video_record_event -> {
                    if (video_record_event instanceof VideoRecordEvent.Start) {
                        Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
                    } else if (video_record_event instanceof VideoRecordEvent.Pause) {
                        Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show();
                    } else if (video_record_event instanceof VideoRecordEvent.Resume) {
                        Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show();
                    } else if (video_record_event instanceof VideoRecordEvent.Finalize) {
                        VideoRecordEvent.Finalize finalize_event = (VideoRecordEvent.Finalize) video_record_event;
                        if (finalize_event.getError() != VideoRecordEvent.Finalize.ERROR_NONE) {
                            Toast.makeText(this, "Recording error!", Toast.LENGTH_SHORT).show();
                            assert finalize_event.getCause() != null;
                            throw new RuntimeException(finalize_event.getCause().getMessage());
                        }
                        Log.i(CAM, "Video path: " + finalize_event.getOutputResults().getOutputUri().getPath());
                    }
                });
    }
}