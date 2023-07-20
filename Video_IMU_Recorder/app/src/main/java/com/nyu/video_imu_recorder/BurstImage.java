package com.nyu.video_imu_recorder;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
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
import android.os.SystemClock;
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

import com.nyu.imu_processing.CoordinateShift;
import com.nyu.imu_processing.IMUSession;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public class BurstImage extends AppCompatActivity implements SensorEventListener {
    private static final int CAMERA_PERMISSION = new SecureRandom().nextInt(100);
    private static final String CAM = "Camera_configuration", FILE = "IMU_data_file";
    private HandlerThread callback_thread;
    private Handler callback_handler;
    private ImageReader image_reader;
    private CameraDevice camera_device;
    private SensorManager sensor_manager;
    private Sensor accelerometer, gyroscope, gravity_sensor;
    private final IMUSession imu_session = new IMUSession(new double[]{0, 0, SensorManager.GRAVITY_EARTH},
            new String[]{"align_quaternion", "align_gravity"}, "align_gravity", 20000000);
    private double[] gravity = {0, 0, 0};
    private File imu_data, images_directory;
    private FileOutputStream imu_output;
    private long[] video_imu_start_times = {-1, -1};
    private long last_save_time = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_burst_image);
        Intent intent = getIntent();
        String[] file_names = {intent.getStringExtra("imu_data_name"), intent.getStringExtra("media_name")};
        String back_camera_id = intent.getStringExtra("back_camera_id");

        // Set up background thread to handle image capturing events
        callback_thread = new HandlerThread("camera_callback_thread");
        callback_thread.start();
        callback_handler = new Handler(callback_thread.getLooper());

        sensor_manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensor_manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensor_manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gravity_sensor = sensor_manager.getDefaultSensor(Sensor.TYPE_GRAVITY);

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
        assert camera_manager.getCameraCharacteristics(camera_id).get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) == 1 : "The camera" +
                " timestamps can't be guaranteed to match IMU timestamps";
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
                    prepareDataStorage(file_names[0], file_names[1]);
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
            if (video_imu_start_times[0] == -1) {
                imu_output.write((timestamp + " video recording started\n").getBytes(StandardCharsets.UTF_8));
                video_imu_start_times[0] = timestamp;
            }
        } catch (IOException exception) {
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
        String data = "";
        if (video_imu_start_times[1] == -1) video_imu_start_times[1] = event.timestamp;
        // Calculate the time difference between the IMU starting and the camera starting
        if (video_imu_start_times[0] > 0 && video_imu_start_times[1] > 0) {
            long latency = video_imu_start_times[1] - video_imu_start_times[0];
            data += "Latency between IMU and camera: " + Math.abs(latency) + " (" + (latency < 0 ? "IMU" : "camera") + " started sooner)\n";
            Log.i(FILE, "Latency: " + latency);
            video_imu_start_times = new long[]{0, 0};
        }
        // Get processed data from alignment and integration algorithms etc. in imu session
        if (event.sensor.getType() == gravity_sensor.getType()) {
            gravity = CoordinateShift.toDoubleArray(event.values);
        } else if (event.sensor.getType() == accelerometer.getType()) {
            data += imu_session.updateAccelerometer(event.timestamp, event.values, gravity);
        } else if (event.sensor.getType() == gyroscope.getType()) {
            imu_session.updateGyroscope(event.timestamp, event.values);
        }
        // Write the combined output from this measurement to the data file
        Log.v(FILE, "imu data: " + data);
        try {
            imu_output.write(data.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.v(FILE, sensor.getName() + " accuracy changed to " + accuracy);
    }

    private void prepareDataStorage(String imu_data_name, String media_name) throws IOException {
        // Create a new file to store IMU measurement data
        imu_data = new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DOCUMENTS)[0], imu_data_name);
        try {
            Log.i(FILE, "IMU data file (new: " + imu_data.createNewFile() + "; exists: " + imu_data.exists() + ") at " + imu_data.getPath());
        } catch (IOException io_exception) {
            Log.e(FILE, "Creation failed: " + imu_data.getPath());
            Toast.makeText(this, "IMU data storage file cannot be created", Toast.LENGTH_LONG).show();
            throw io_exception;
        }

        // Create a new folder to store captured images
        images_directory = new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DCIM)[0], media_name);
        Log.i(CAM, "Image directory (newly created: " + images_directory.mkdirs() + ") at " + images_directory.getAbsolutePath());
    }

    private void startIMURecording() {
        try {
            imu_output = new FileOutputStream(imu_data, true);
            int interval = Math.max(Math.max(accelerometer.getMinDelay(), gyroscope.getMinDelay()), gravity_sensor.getMinDelay());
            sensor_manager.registerListener(this, accelerometer, interval);
            sensor_manager.registerListener(this, gyroscope, interval);
            sensor_manager.registerListener(this, gravity_sensor, interval);
        } catch (FileNotFoundException exception) {
            Log.e(FILE, "FileOutputStream failed to be opened");
            exception.printStackTrace();
            Toast.makeText(this, "IMU data storage file cannot be opened", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void stopIMURecording() {
        sensor_manager.unregisterListener(this);
        try {
            imu_output.flush();
            imu_output.close();
        } catch (IOException exception) {
            Log.e(FILE, "FileOutputStream failed to close");
            Toast.makeText(this, "IMU data failed to save, data lost!", Toast.LENGTH_SHORT).show();
            exception.printStackTrace();
        }
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