package com.example.video_imu_recorder;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.os.Bundle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatButton;

import java.util.Arrays;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String VID = "Video logistics";
    private SensorManager sensor_manager;
    private Sensor linear_accelerometer, gyroscope;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CameraManager camera_manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        setContentView(R.layout.activity_main);

        sensor_manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        linear_accelerometer = sensor_manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gyroscope = sensor_manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // Prepare for launching video capturing activity
        AppCompatButton record_start = findViewById(R.id.record_start);
        record_start.setOnClickListener(view -> {
            Intent record_data = new Intent(this, VideoRecord.class);
            // Locate back-facing camera
            // TODO: instead of checking if device has back camera, formulate a new video file name and send to VideoCapture
            try {
                String[] camera_IDs = camera_manager.getCameraIdList();
                Log.d(VID, "Camera-IDs: " + Arrays.toString(camera_IDs));
                for (String camera_id : camera_IDs) {
                    if (camera_manager.getCameraCharacteristics(camera_id).get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK) {
                        Log.d(VID, "Found back-facing camera with id " + camera_id);
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
            // TODO: precisely synchronize IMU measurement with video capture start & stop
            sensor_manager.registerListener(this, linear_accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            sensor_manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        sensor_manager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == linear_accelerometer.getType()) {
            Log.d("Accelerometer", "data: " + Arrays.toString(event.values));
        } else if (event.sensor.getType() == gyroscope.getType()) {
            Log.d("Gyroscope", "data: " + Arrays.toString(event.values));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.d("Sensor accuracy", sensor.getName() + " accuracy changed to " + accuracy);
    }
}