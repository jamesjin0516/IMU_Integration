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
import androidx.preference.PreferenceManager;

import com.nyu.imu_processing.CoordinateShift;
import com.nyu.imu_processing.IMUSession;

import org.apache.commons.math3.util.Pair;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class IMUCapture extends AppCompatActivity implements SensorEventListener {

    private static final String FILE = "IMU_data_file";
    private SensorManager sensor_manager;
    private Sensor accelerometer, linear_acceleration, gyroscope, gravity_sensor;
    private IMUSession imu_session;
    private double[] gravity = {0, 0, 0};
    private Map<String, File> imu_files;
    private Map<String, FileOutputStream> imu_outputs;
    private final StringBuffer new_data_buffer = new StringBuffer();
    private long imu_start_time = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String[] alignment_methods = getResources().getStringArray(R.array.alignment_methods);
        String alignment_method = PreferenceManager.getDefaultSharedPreferences(this)
                .getString("alignment_method", null);
        imu_session = new IMUSession(new double[]{0, 0, SensorManager.GRAVITY_EARTH}, alignment_methods, alignment_method);

        sensor_manager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensor_manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        linear_acceleration = sensor_manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
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
        // Feed data to imu session for integration and other processing
        FileOutputStream appropriate_stream = null;
        if (event.sensor.getType() == gravity_sensor.getType()) {
            gravity = CoordinateShift.toDoubleArray(event.values);
        } else if (event.sensor.getType() == linear_acceleration.getType()) {
            imu_session.updateAccelerometer(event.timestamp, event.values, gravity);
        } else if (event.sensor.getType() == accelerometer.getType()) {
            imu_session.updateIMUPose(event.timestamp, event.values);
            appropriate_stream = imu_outputs.get("accelerometer");
        } else if (event.sensor.getType() == gyroscope.getType()) {
            imu_session.updateGyroscope(event.timestamp, event.values);
            appropriate_stream = imu_outputs.get("gyroscope");
        }
        // Write the combined output from this measurement to the data file
        String position_data = new_data_buffer.toString();
        new_data_buffer.delete(0, new_data_buffer.length());
        String sensor_data = imu_time + ' ' + event.sensor.getName() + ' ' + Arrays.toString(event.values) + '\n';
        Log.v(FILE, "imu data:\n" + position_data + sensor_data);
        // TODO: since the localized image serving as IMU's reference is now changing, the integrated position is no longer meaningful
        try {
            imu_outputs.get("position").write(position_data.getBytes(StandardCharsets.UTF_8));
            if (appropriate_stream != null) {
                appropriate_stream.write(sensor_data.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.v(FILE, sensor.getName() + " accuracy changed to " + accuracy);
    }

    protected void resetIMUOrientation() {
        imu_session.setQuaternionPose(1, 0, 0, 0);
    }

    protected void startIMURecording() {
        try {
            for (Map.Entry<String, File> entry : imu_files.entrySet()) {
                imu_outputs.put(entry.getKey(), new FileOutputStream(entry.getValue(), true));
            }
            // Determine the best compatible sensor frequency and start listening from all sensors
            int interval = Math.max(Math.max(Math.max(accelerometer.getMinDelay(), linear_acceleration.getMinDelay()),
                    gyroscope.getMinDelay()), gravity_sensor.getMinDelay());
            sensor_manager.registerListener(this, accelerometer, interval);
            sensor_manager.registerListener(this, linear_acceleration, interval);
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
            for (FileOutputStream output_stream : imu_outputs.values()) {
                output_stream.flush();
                output_stream.close();
            }
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
                imu_outputs.get("position").write(latency_message.getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException | IOException exception) {
                exception.printStackTrace();
            }
        }).start();
    }

    protected List<Pair<Long, double[]>> generatePositionData() {
        // Get positions data and convert it into the timestamp + type + values format
        List<Pair<Long, double[]>> poss_info = imu_session.retrieveKinematicArrays().get("poss_info");
        assert poss_info != null : "The kinematics arrays either doesn't contain positions data or explicitly sets it to null";
        List<String> poss_output = poss_info.stream().flatMap(pos -> Stream.of(pos.getFirst() + " position "
                + Arrays.toString(pos.getSecond()) + '\n')).collect(Collectors.toList());
        new_data_buffer.append(String.join("", poss_output));
        return poss_info;
    }

    protected void broadcastRecordStatus(String status) {
        Log.i(FILE, "status to broadcast: " + status);
        Intent broadcast = new Intent();
        broadcast.setAction(getPackageName() + ".RECORD_STATUS");
        broadcast.putExtra("status", status);
        broadcast.setPackage(getPackageName());
        sendBroadcast(broadcast);
    }

    protected File setIMUFileAndGetMediaLocation(String imu_data_name, String media_name) throws IOException {
        // Create new directories to the intended imu measurement data location and a new file to store data
        File imu_data = new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DOCUMENTS)[0], imu_data_name);
        File parent_directory = imu_data.getParentFile();
        assert parent_directory != null : "A parent directory couldn't be obtained from the supplied imu data name: " + imu_data_name;
        imu_files = Map.of("accelerometer", new File(parent_directory, imu_data.getName() + "accelerometer.txt"),
                "gyroscope", new File(parent_directory, imu_data.getName() + "gyroscope.txt"),
                "position", new File(parent_directory, imu_data.getName() + "position.txt"));
        imu_outputs = new HashMap<>(3);
        try {
            boolean dir_new = parent_directory.mkdirs();
            Log.i(FILE, "IMU data directory (new: " + dir_new + "; exists: " + parent_directory.exists() + ") at " + parent_directory.getPath());
            for (File data_file : imu_files.values()) {
                boolean file_new = data_file.createNewFile();
                Log.i(FILE, "IMU file (new: " + file_new + "; exists: " + data_file.exists() + ") at " + data_file.getPath());
            }
        } catch (IOException io_exception) {
            Log.e(FILE, "Creation failed for a data file at " + parent_directory.getPath());
            Toast.makeText(this, "IMU data storage file cannot be created", Toast.LENGTH_LONG).show();
            throw io_exception;
        }

        // Return the location for media
        return new File(ContextCompat.getExternalFilesDirs(this, Environment.DIRECTORY_DOCUMENTS)[0], media_name);
    }
}