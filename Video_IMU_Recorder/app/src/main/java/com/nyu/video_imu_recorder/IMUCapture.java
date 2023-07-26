package com.nyu.video_imu_recorder;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public abstract class IMUCapture extends AppCompatActivity implements SensorEventListener {

    private static final String FILE = "IMU_data_file";
    private SensorManager sensor_manager;
    private Sensor linear_accelerometer, gyroscope;
    private File imu_data;
    private FileOutputStream imu_output;
    private long imu_start_time = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensor_manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        linear_accelerometer = sensor_manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gyroscope = sensor_manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long imu_time = SystemClock.elapsedRealtimeNanos();
        // Resume any suspended thread waiting for IMU's start timestamp to become available
        if (imu_start_time == -1) {
            synchronized (IMUCapture.this) {
                imu_start_time = imu_time;
                notifyAll();
            }
        }
        String data = imu_time + " " + event.sensor.getName() + " " + Arrays.toString(event.values) + '\n';
        Log.v(FILE, "imu data: " + data);
        try {
            imu_output.write(data.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.v(FILE, sensor.getName() + " accuracy changed to " + accuracy);
    }

    protected void startIMURecording() {
        try {
            imu_output = new FileOutputStream(imu_data, true);
            sensor_manager.registerListener(this, linear_accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
            sensor_manager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_NORMAL);
        } catch (FileNotFoundException exception) {
            Log.e(FILE, "FileOutputStream failed to be opened");
            exception.printStackTrace();
            Toast.makeText(this, "IMU data storage file cannot be opened", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    protected void stopIMURecording() {
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

    protected void notifyVideoStart(long video_start_time) {
        if (imu_start_time == 0) return;
        new Thread(() -> {
            try {
                // Wait in case IMU's starting timestamp is not yet available
                synchronized (IMUCapture.this) {
                    while (imu_start_time == -1) {
                        wait();
                    }
                }
                long latency = imu_start_time - video_start_time;
                imu_start_time = 0;
                // Calculate the time difference between the IMU starting and the camera starting
                Log.i(FILE, "Latency: " + latency);
                String latency_message = video_start_time + " video recording started. Latency between IMU and camera: "
                        + Math.abs(latency) + " (" + (latency < 0 ? "IMU" : "camera") + " started sooner)\n";
                imu_output.write(latency_message.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException | IOException exception) {
                exception.printStackTrace();
            }
        }).start();
    }

    protected void broadcast_record_status(String status) {
        Log.i(FILE, "status to broadcast: " + status);
        Intent broadcast = new Intent();
        broadcast.setAction(getPackageName() + ".RECORD_STATUS");
        broadcast.putExtra("status", status);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    protected File setIMUFileAndGetMediaLocation(String imu_data_name, String media_name) throws IOException {
        // Create a new file to store IMU measurement data
        imu_data = new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DOCUMENTS)[0], imu_data_name);
        try {
            boolean file_new = imu_data.createNewFile();
            Log.i(FILE, "IMU data file (new: " + file_new + "; exists: " + imu_data.exists() + ") at " + imu_data.getPath());
        } catch (IOException io_exception) {
            Log.e(FILE, "Creation failed: " + imu_data.getPath());
            Toast.makeText(this, "IMU data storage file cannot be created", Toast.LENGTH_LONG).show();
            throw io_exception;
        }

        // Return the location for media
        return new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DCIM)[0], media_name);
    }
}