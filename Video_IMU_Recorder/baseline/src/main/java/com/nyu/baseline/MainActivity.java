package com.nyu.baseline;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;

import com.nyu.imu_processing.CoordinateShift;
import com.nyu.imu_processing.IMUSession;

import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    private static final double[] WORLD_GRAVITY = {0, 0, SensorManager.GRAVITY_EARTH};
    private SensorManager sensorManager;
    private Sensor accelerometer, gravity_sensor, gyroscope;
    private IMUSession imu_session;
    private String[] alignment_methods;
    private String alignment_method = "";
    private double[] gravity = {0, 0, 0};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gravity_sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // Set up the dropdown list widget to enable selection of possible coordinate frame alignment methods
        alignment_methods = new String[]{getResources().getResourceEntryName(R.string.align_quaternion),
                getResources().getResourceEntryName(R.string.align_gravity)};
        Spinner method_selector = findViewById(R.id.method_selector);
        List<String> methods_descriptions = List.of("", getString(R.string.align_quaternion), getString(R.string.align_gravity));
        ArrayAdapter<String> methods_adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, methods_descriptions);
        methods_adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        method_selector.setAdapter(methods_adapter);
        method_selector.setOnItemSelectedListener(method_selected);

        new Thread(enable_sensors).start();
    }

    private final Runnable enable_sensors = () -> {
        // Wait as long as the chosen alignment method is the empty placeholder
        synchronized (MainActivity.this) {
            while (alignment_method.equals("")) {
                try {
                    MainActivity.this.wait();
                } catch (InterruptedException exception) {
                    exception.printStackTrace();
                }
            }
        }
        // Once woken up, finish setting up the sensors
        imu_session = new IMUSession(WORLD_GRAVITY, alignment_methods, alignment_method, 0);
        sensorManager.registerListener(MainActivity.this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(MainActivity.this, gravity_sensor, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(MainActivity.this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
    };

    private final AdapterView.OnItemSelectedListener method_selected = new AdapterView.OnItemSelectedListener() {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            alignment_method = parent.getItemAtPosition(position).toString();
            // After detecting a method besides the empty placeholder, wake up the thread responsible for enabling sensors
            if (!alignment_method.equals("")) {
                alignment_method = alignment_methods[position - 1];
                synchronized (MainActivity.this) {
                    MainActivity.this.notifyAll();
                }
                Toast.makeText(MainActivity.this, "Enabling IMU sensors.", Toast.LENGTH_SHORT).show();
            }
        }
        @Override
        public void onNothingSelected(AdapterView<?> parent) {}
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == gravity_sensor.getType()) {
            gravity = CoordinateShift.toDoubleArray(event.values);
        } else if (event.sensor.getType() == accelerometer.getType()) {
            imu_session.updateAccelerometer(event.timestamp, event.values, gravity);
        } else if (event.sensor.getType() == gyroscope.getType()) {
            imu_session.updateGyroscope(event.timestamp, event.values);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}