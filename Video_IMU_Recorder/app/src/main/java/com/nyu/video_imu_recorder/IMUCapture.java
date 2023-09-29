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
import android.util.Pair;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.nyu.imu_processing.CoordinateShift;
import com.nyu.imu_processing.IMUSession;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public abstract class IMUCapture extends AppCompatActivity implements SensorEventListener {

    private static final String FILE = "IMU_data_file";
    private SensorManager sensor_manager;
    private Sensor accelerometer, gyroscope, gravity_sensor;
    private final IMUSession imu_session = new IMUSession(new double[]{0, 0, SensorManager.GRAVITY_EARTH},
            new String[]{"align_quaternion", "align_gravity"}, "align_quaternion", 20000000);
    private double[] gravity = {0, 0, 0};
    private Pair<Long, double[]> accels_info, angular_velo_info;
    private File imu_data;
    private FileOutputStream imu_output;
    private long imu_start_time = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensor_manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensor_manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyroscope = sensor_manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gravity_sensor = sensor_manager.getDefaultSensor(Sensor.TYPE_GRAVITY);
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
        // Get processed data from alignment and integration algorithms etc. in imu session
        if (event.sensor.getType() == gravity_sensor.getType()) {
            gravity = CoordinateShift.toDoubleArray(event.values);
        } else if (event.sensor.getType() == accelerometer.getType()) {
            synchronized (imu_session) {
                accels_info = new Pair<>(event.timestamp, CoordinateShift.toDoubleArray(event.values));
            }
            String position_data = imu_session.updateAccelerometer(event.timestamp, event.values, gravity);
            try {
                imu_output.write(position_data.getBytes(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                exception.printStackTrace();
            }
        } else if (event.sensor.getType() == gyroscope.getType()) {
            synchronized (imu_session) {
                angular_velo_info = new Pair<>(event.timestamp, CoordinateShift.toDoubleArray(event.values));
            }
            imu_session.updateGyroscope(event.timestamp, event.values);
        }
        String save_now_data = imu_time + " " + event.sensor.getName() + " " + Arrays.toString(event.values) + '\n';
        Log.v(FILE, "imu data: " + save_now_data);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.v(FILE, sensor.getName() + " accuracy changed to " + accuracy);
    }

    protected void startIMURecording() {
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

    protected void saveTimestampedIMUData(long timestamp) throws IOException, JSONException {
        Map<String, Object> output_data;
        synchronized (imu_session) {
            output_data = new HashMap<>(IMUSession.getLatest6DOFData(accels_info.first, accels_info.second, angular_velo_info.first, angular_velo_info.second));
            output_data.put("imu_delay", (Long) output_data.get("imu_timestamp") - timestamp);
        }
        File timestamped_data = new File(imu_data.getParentFile(), timestamp + ".json");
        boolean file_new = timestamped_data.createNewFile();
        assert file_new : "Creating duplicate imu data files for the same image timestamp " + timestamp;
        FileOutputStream timestamped_stream = new FileOutputStream(timestamped_data);
        timestamped_stream.write(new JSONObject(output_data).toString(4).getBytes(StandardCharsets.UTF_8));
        timestamped_stream.flush();
        timestamped_stream.close();
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
        String data_dir_name = new SimpleDateFormat("MM_dd_yyyy_HH_mm", Locale.US).format(new Date());
        File data_dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), data_dir_name);
        boolean dir_new = data_dir.mkdirs();
        Log.i(FILE, "IMU data directory (newly created: " + dir_new + ") at " + data_dir.getAbsolutePath());
        // Create a new file to store IMU measurement data
        imu_data = new File(data_dir, imu_data_name);
        try {
            boolean file_new = imu_data.createNewFile();
            Log.i(FILE, "IMU data file (new: " + file_new + "; exists: " + imu_data.exists() + ") at " + imu_data.getPath());
        } catch (IOException io_exception) {
            Log.e(FILE, "Creation failed: " + imu_data.getPath());
            Toast.makeText(this, "IMU data storage file cannot be created", Toast.LENGTH_LONG).show();
            throw io_exception;
        }

        // Return the location for media
        return new File(data_dir, media_name);
    }
}