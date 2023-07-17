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

import org.apache.commons.math3.complex.Quaternion;

import java.util.List;

public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor accelerometer, gravity_sensor, gyroscope;
    private static final double[] NORM_WORLD_GRAVITY = {0, 0, 1};
    private static final double NANOSECONDS = Math.pow(10, 9);
    private String alignment_method = "";
    private double[] linear_accel = {}, angular_accel = {0, 0, 0},  gravity = {0, 0, 0};
    private final double[] velocity = {0, 0, 0}, position = {0, 0, 0}, turned_angle = {0, 0, 0};
    private Quaternion pose = Quaternion.IDENTITY;
    private long accel_timestamp, gyro_timestamp = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gravity_sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        // Set up the dropdown list widget to enable selection of possible coordinate frame alignment methods
        Spinner alignment_methods = findViewById(R.id.alignment_methods);
        List<String> methods = List.of("", getString(R.string.align_gravity), getString(R.string.align_quaternion));
        ArrayAdapter<String> methods_adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, methods);
        methods_adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alignment_methods.setAdapter(methods_adapter);
        alignment_methods.setOnItemSelectedListener(method_selected);

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
                synchronized (MainActivity.this) {
                    MainActivity.this.notifyAll();
                    Toast.makeText(MainActivity.this, "Enabling IMU sensors.", Toast.LENGTH_SHORT).show();
                }
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
            // Transform the new acceleration readings into the world coordinate frame
            final double dT = (event.timestamp - accel_timestamp) / NANOSECONDS;
            double[] aligned_accel = alignAcceleration(event.values, dT);

            // Perform double integration of the new and old acceleration values using time gap between readings
            /*
            Assuming no jerk
            dv = jt + a_0 dt
            v =  1/2 jt^2 + ta_0
            v = 1/2 t(2a_0 + jt)
            v = 1/2 t(a_0 + a_new)

            float deltaV = normZ * dT;
            float displacement = dT * dT * normZ / 2 + dT * velocityZ;
            */
            for (int dim = 0; dim < linear_accel.length; ++dim) {
                /*
                Assuming constant jerk
                dD = at + v_0 dt
                dD = 1/2 jt^2 + ta_0 + v_0 dt
                D = 1/6 jt^3 + 1/2 t^2a_0 + tv_0
                D = 1/6 t^2 (jt + 3a_0) + tv_0
                D = 1/6 t^2 (a_new + 2a_0) + tv_0
                */
                double delta_velocity = dT * (linear_accel[dim] + aligned_accel[dim]) / 2;
                double delta_position = dT * dT * (aligned_accel[dim] + 2 * linear_accel[dim]) / 6 + dT * velocity[dim];

                velocity[dim] += delta_velocity;
                position[dim] += delta_position;
            }

            linear_accel = aligned_accel;
            accel_timestamp = event.timestamp;
        } else if (event.sensor.getType() == gyroscope.getType()) {
            if (gyro_timestamp != 0) {
                final double dT = (event.timestamp - gyro_timestamp) / NANOSECONDS;
                float[] deltaRotationVector = CoordinateShift.axisAngleVector(CoordinateShift.toDoubleArray(event.values), dT);

                // Obtain rotation matrix from axis angle vector and calculate degrees turned along each axis
                float[] deltaRotationMatrix = new float[9];
                SensorManager.getRotationMatrixFromVector(deltaRotationMatrix, deltaRotationVector);
                turned_angle[0] += Math.toDegrees(Math.atan2(deltaRotationMatrix[7],deltaRotationMatrix[8]));
                turned_angle[1] += Math.toDegrees(Math.atan2(-deltaRotationMatrix[6],Math.sqrt(deltaRotationMatrix[7]*deltaRotationMatrix[7]+deltaRotationMatrix[8]*deltaRotationMatrix[8])));
                turned_angle[2] += Math.toDegrees(Math.atan2(deltaRotationMatrix[3],deltaRotationMatrix[0]));
            }
            angular_accel = CoordinateShift.toDoubleArray(event.values);
            gyro_timestamp = event.timestamp;
        }
    }

    private double[] alignAcceleration(float[] sensor_values, double dT) {
        double[] aligned_accel;
        if (alignment_method.equals(getString(R.string.align_quaternion))) {
            // Update quaternion representing device pose using gyroscope and accelerometer values
            pose = CoordinateShift.newQuaternionPose(pose, sensor_values, angular_accel, dT);
            // Use quaternion to rotate acceleration values with formula qvq*
            aligned_accel = pose.multiply(new Quaternion(CoordinateShift.toDoubleArray(sensor_values))).multiply(pose.getConjugate()).getVectorPart();
        } else {
            // Obtain rotation matrix by comparing gravity in device's and in world's axes
            double[] rot_matrix = CoordinateShift.rotationMatrix(gravity, NORM_WORLD_GRAVITY);
            // Apply the rotation matrix to the acceleration readings
            aligned_accel = new double[]{0, 0, 0};
            for (int row = 0; row < sensor_values.length; row++) {
                for (int comp = 0; comp < sensor_values.length; comp++) {
                    aligned_accel[row] += sensor_values[comp] * rot_matrix[row * 3 + comp];
                }
            }
        }
        // Subtract gravity from the newly obtained acceleration values in the world coordinate frame
        for (int index = 0; index < aligned_accel.length; ++index) {
            aligned_accel[index] -= NORM_WORLD_GRAVITY[index] * SensorManager.GRAVITY_EARTH;
        }
        return aligned_accel;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}