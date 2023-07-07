package com.example.video_imu_recorder;

import android.Manifest;
import android.content.Context;
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
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class BurstImage extends AppCompatActivity {
    private static final int CAMERA_PERMISSION = new SecureRandom().nextInt(100);
    private HandlerThread callback_thread;
    private Handler callback_handler;
    private ImageReader image_reader;
    private CameraDevice camera_device;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_burst_image);

        callback_thread = new HandlerThread("camera_callback_thread");
        callback_thread.start();
        callback_handler = new Handler(callback_thread.getLooper());

        SurfaceView preview = findViewById(R.id.preview);
        // Use a callback to ensure the preview element responsible for showing the camera footage is ready first
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                CameraManager camera_manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                try {
                    for (String camera_id : camera_manager.getCameraIdList()) {
                        if (camera_manager.getCameraCharacteristics(camera_id).get(CameraCharacteristics.LENS_FACING)
                                == CameraCharacteristics.LENS_FACING_BACK) {
                            initiateCamera(camera_manager, camera_id, preview);
                            break;
                        }
                    }
                } catch (CameraAccessException exception) {
                    exception.printStackTrace();
                }
            }
            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}
            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {}
        });

        preview.setOnClickListener(view -> {
            finish();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        camera_device.close();
        callback_thread.quitSafely();
        try {
            callback_thread.join();
        } catch (InterruptedException exception) {
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

    private void initiateCamera(CameraManager camera_manager, String camera_id, SurfaceView preview) throws CameraAccessException {
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
                    configureCameraOutputs(preview);
                } catch (CameraAccessException exception) {
                    exception.printStackTrace();
                }
            }
            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Log.w("Camera", "disconnected");
            }
            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Log.e("Camera", "Error with code: " + error);
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
                Toast.makeText(BurstImage.this, "Configuration succeeded", Toast.LENGTH_LONG).show();
                image_reader.setOnImageAvailableListener(image_available_listener, callback_handler);
                try {
                    session.setRepeatingRequest(capture_request_builder.build(), null, null);
                } catch (CameraAccessException exception) {
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

        File image_file = new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DCIM)[0], timestamp + ".jpeg");
        try {
            // Write the byte data into an image file
            FileOutputStream file_output_stream = new FileOutputStream(image_file);
            file_output_stream.write(image_bytes);
            file_output_stream.flush();
            file_output_stream.close();
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    };

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