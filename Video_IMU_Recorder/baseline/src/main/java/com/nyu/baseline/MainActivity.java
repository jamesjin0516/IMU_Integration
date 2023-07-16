package com.nyu.baseline;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;

public class MainActivity extends Activity implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor linear_acceleration, gravity_sensor, gyroscope;
    private static final double[] NORM_WORLD_GRAVITY = {0, 0, 1};
    private static final double NS2S = 1000000000.0;
    private double[] acceleration = {}, gravity = {0, 0, 0};
    private final double[] velocity = {0, 0, 0}, position = {0, 0, 0}, turned_angle = {0, 0, 0};
    private long accel_timestamp, gyro_timestamp = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        linear_acceleration = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        gravity_sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        sensorManager.registerListener(MainActivity.this, linear_acceleration, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(MainActivity.this, gravity_sensor, SensorManager.SENSOR_DELAY_GAME);
        sensorManager.registerListener(MainActivity.this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == gravity_sensor.getType()) {
            gravity = CoordinateShift.toDoubleArray(event.values);
        } else if (event.sensor.getType() == linear_acceleration.getType()) {

            double[] rot_matrix = CoordinateShift.rotationMatrix(gravity, NORM_WORLD_GRAVITY);

            /*
            Transform the new acceleration readings into the world coordinate frame using calculated matrix
            TODO: Since gravity readings only constrains the z axis, the more sophisticated AHRS algorithm from
             https://github.com/xioTechnologies/Oscillatory-Motion-Tracking-With-x-IMU/ is necessary
            */
            double[] aligned_accels = {0, 0, 0};
            for (int row = 0; row < event.values.length; row ++) {
                for (int comp = 0; comp < event.values.length; comp++) {
                    aligned_accels[row] += event.values[comp] * rot_matrix[row * 3 + comp];
                }
            }

            // Perform double integration of the new and old acceleration values using time gap between readings
            final double dT = (event.timestamp - accel_timestamp) / NS2S;
            /*
            Assuming no jerk
            dv = jt + a_0 dt
            v =  1/2 jt^2 + ta_0
            v = 1/2 t(2a_0 + jt)
            v = 1/2 t(a_0 + a_new)

            float deltaV = normZ * dT;
            float displacement = dT * dT * normZ / 2 + dT * velocityZ;
            */
            for (int dim = 0; dim < acceleration.length; ++dim) {
                /*
                Assuming constant jerk
                dD = at + v_0 dt
                dD = 1/2 jt^2 + ta_0 + v_0 dt
                D = 1/6 jt^3 + 1/2 t^2a_0 + tv_0
                D = 1/6 t^2 (jt + 3a_0) + tv_0
                D = 1/6 t^2 (a_new + 2a_0) + tv_0
                */
                double delta_velocity = dT * (acceleration[dim] + aligned_accels[dim]) / 2;
                double delta_position = dT * dT * (aligned_accels[dim] + 2 * acceleration[dim]) / 6 + dT * velocity[dim];

                velocity[dim] += delta_velocity;
                position[dim] += delta_position;
            }

            acceleration = aligned_accels;
            accel_timestamp = event.timestamp;
        } else if (event.sensor.getType() == gyroscope.getType()) {
            if (gyro_timestamp != 0) {
                final double dT = (event.timestamp - gyro_timestamp) / NS2S;
                float[] deltaRotationVector = CoordinateShift.axisAngleVector(CoordinateShift.toDoubleArray(event.values), dT);

                // Obtain rotation matrix from axis angle vector and calculate degrees turned along each axis
                float[] deltaRotationMatrix = new float[9];
                SensorManager.getRotationMatrixFromVector(deltaRotationMatrix, deltaRotationVector);
                turned_angle[0] += Math.toDegrees(Math.atan2(deltaRotationMatrix[7],deltaRotationMatrix[8]));
                turned_angle[1] += Math.toDegrees(Math.atan2(-deltaRotationMatrix[6],Math.sqrt(deltaRotationMatrix[7]*deltaRotationMatrix[7]+deltaRotationMatrix[8]*deltaRotationMatrix[8])));
                turned_angle[2] += Math.toDegrees(Math.atan2(deltaRotationMatrix[3],deltaRotationMatrix[0]));
            }
            gyro_timestamp = event.timestamp;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}