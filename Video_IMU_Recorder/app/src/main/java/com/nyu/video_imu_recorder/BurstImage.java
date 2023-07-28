package com.nyu.video_imu_recorder;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.nyu.localization_service.ServerSession;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

public class BurstImage extends IMUCapture {
    private static final int CAMERA_PERMISSION = new SecureRandom().nextInt(100);
    private static final String CAM = "Camera_configuration";
    private HandlerThread callback_thread;
    private Handler callback_handler;
    private ImageReader image_reader;
    private CameraDevice camera_device;
    private ServerSession server_session;
    private File images_directory;
    private long last_save_time = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_burst_image);
        Intent intent = getIntent();
        String[] file_names = {intent.getStringExtra("imu_data_name"), intent.getStringExtra("media_name")};
        String back_camera_id = intent.getStringExtra("back_camera_id");
        // Establish communication with localization server
        String host_id = intent.getStringExtra("host_id");
        int port_id = Integer.parseInt(intent.getStringExtra("port_id"));
        server_session = new ServerSession(host_id, port_id, getApplication());

        // Set up background thread to handle image capturing events
        callback_thread = new HandlerThread("camera_callback_thread");
        callback_thread.start();
        callback_handler = new Handler(callback_thread.getLooper());

        SurfaceView preview = findViewById(R.id.preview);
        // Use a callback to ensure the preview element responsible for showing the camera footage is ready first
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                try {
                    initiateCamera((CameraManager) getSystemService(Context.CAMERA_SERVICE), back_camera_id, preview, file_names);
                } catch (CameraAccessException exception) {
                    exception.printStackTrace();
                }
            }
            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}
            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
        });

        preview.setOnClickListener(view -> finish());
    }

    @Override
    protected void onStop() {
        super.onStop();
        broadcast_record_status(BurstImage.class.getName());
        camera_device.close();
        stopIMURecording();
        callback_thread.quitSafely();
        try {
            callback_thread.join();
            server_session.shutdown();
        } catch (InterruptedException | ExecutionException exception) {
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

    private void initiateCamera(CameraManager camera_manager, String camera_id, SurfaceView preview, String[] file_names) throws CameraAccessException {
        // Use busy-waiting to ensure permission is granted before opening camera
        boolean pending = true;
        while (ContextCompat.checkSelfPermission(BurstImage.this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED) {
            if (pending) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
                pending = false;
            }
        }

        // Get the back camera's highest resolution and use it to create the image reader
        Size[] sizes = camera_manager.getCameraCharacteristics(camera_id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
        final Size max_size = Arrays.stream(sizes).max(Comparator.comparing(size -> size.getWidth() * size.getHeight())).orElse(sizes[0]);
        image_reader = ImageReader.newInstance(max_size.getWidth(), max_size.getHeight(), ImageFormat.JPEG, 2);

        // Callback passed to opening the camera to start using camera outputs once ready
        CameraDevice.StateCallback state_callback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                camera_device = camera;
                try {
                    images_directory = setIMUFileAndGetMediaLocation(file_names[0], file_names[1]);
                    configureCameraOutputs(preview);
                } catch (CameraAccessException | IOException exception) {
                    exception.printStackTrace();
                }
            }
            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.w(CAM, "disconnected");
            }
            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e(CAM, "Error with code: " + error);
            }
        };

        camera_manager.openCamera(camera_id, state_callback, callback_handler);
    }

    private void configureCameraOutputs(SurfaceView preview) throws CameraAccessException {
        // Package the camera data destinations (device screen and image reader) into a capture request
        CaptureRequest.Builder capture_request_builder = camera_device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        Surface surface = preview.getHolder().getSurface();
        capture_request_builder.addTarget(surface);
        capture_request_builder.addTarget(image_reader.getSurface());

        // Callback passed to the creation of the camera capture session; handles displaying preview and feeding to image reader
        CameraCaptureSession.StateCallback state_callback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                image_reader.setOnImageAvailableListener(image_available_listener, callback_handler);
                try {
                    startIMURecording();
                    session.setRepeatingRequest(capture_request_builder.build(), null, null);
                    Toast.makeText(BurstImage.this, "Configuration succeeded", Toast.LENGTH_LONG).show();
                } catch (CameraAccessException exception) {
                    Toast.makeText(BurstImage.this, "Image capturing request failed", Toast.LENGTH_LONG).show();
                    exception.printStackTrace();
                }
            }
            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Toast.makeText(BurstImage.this, "Configuration failed", Toast.LENGTH_LONG).show();
            }
        };

        // Use a capture request to start a camera capture session, which directs camera data appropriately
        SessionConfiguration session_configuration = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                List.of(new OutputConfiguration(surface), new OutputConfiguration(image_reader.getSurface())),
                ContextCompat.getMainExecutor(this), state_callback);
        camera_device.createCaptureSession(session_configuration);
    }

    // Callback passed to image reader to save captured images
    private final ImageReader.OnImageAvailableListener image_available_listener = reader -> {
        Image image = reader.acquireLatestImage();
        long timestamp = image.getTimestamp();
        // Store bytes representing the image into a byte array
        ByteBuffer image_buffer = image.getPlanes()[0].getBuffer();
        byte[] image_bytes = new byte[image_buffer.remaining()];
        image_buffer.get(image_bytes);
        image.close();

        File image_file = new File(images_directory, timestamp + ".jpeg");
        try {
            // Write the byte data into the image file
            FileOutputStream image_output = new FileOutputStream(image_file);
            image_output.write(image_bytes);
            image_output.flush();
            image_output.close();
            // Note the start time of the recording in the imu data file
            notifyVideoStart(timestamp);
            // Dispatch a localization request using the newly captured image
            String pose = server_session.requestLocalization(image_bytes);
            if (pose != null) {
                Log.i(CAM, "localized pose: " + pose);
            }
        } catch (IOException | ExecutionException | InterruptedException exception) {
            exception.printStackTrace();
        }

        // Close and re-open the FileOutputStream object every 3 seconds to avoid overwhelming file write buffer
        long current_time = SystemClock.elapsedRealtimeNanos();
        if ((current_time - last_save_time > 3 * Math.pow(10, 9))) {
            last_save_time = current_time;
            stopIMURecording();
            startIMURecording();
        }
    };

    protected File setIMUFileAndGetMediaLocation(String imu_data_name, String media_name) throws IOException {
        // Create a new folder to store captured images
        File new_images_directory = super.setIMUFileAndGetMediaLocation(imu_data_name, media_name);
        boolean dir_new = new_images_directory.mkdirs();
        Log.i(CAM, "Image directory (newly created: " + dir_new + ") at " + new_images_directory.getAbsolutePath());
        return new_images_directory;
    }

    // The option to capture a singular image per method call, currently not used
    private void captureImage(CameraDevice camera) throws CameraAccessException {
        SparseIntArray orientations = new SparseIntArray(4);
        orientations.append(Surface.ROTATION_0, 0);
        orientations.append(Surface.ROTATION_90, 90);
        orientations.append(Surface.ROTATION_180, 180);
        orientations.append(Surface.ROTATION_270, 270);

        CaptureRequest.Builder capture_request_builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        capture_request_builder.addTarget(image_reader.getSurface());

        int rotation = getDisplay().getRotation();
        capture_request_builder.set(CaptureRequest.JPEG_ORIENTATION, orientations.get(rotation));
    }
}