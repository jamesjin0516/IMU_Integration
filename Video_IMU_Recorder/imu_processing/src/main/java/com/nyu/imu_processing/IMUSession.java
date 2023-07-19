package com.nyu.imu_processing;

import org.apache.commons.math3.complex.Quaternion;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationConvention;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;

import java.util.Arrays;

public class IMUSession {

    private static final double NANOSECONDS = Math.pow(10, 9);
    private final double[] world_gravity;
    private final String[] alignment_methods;
    private String alignment_method = "";
    // TODO: buffer some acceleration data; double integration optionally with high pass; write to file
    private int data_storage_interval;
    private double[] linear_accel = {}, angular_accel = {0, 0, 0};
    private final double[] velocity = {0, 0, 0}, position = {0, 0, 0}, turned_angle = {0, 0, 0};
    private Quaternion pose = Quaternion.IDENTITY;
    private long accel_timestamp, gyro_timestamp = 0;

    public IMUSession(double[] world_gravity, String[] alignment_methods, String alignment_method, int data_storage_interval) {
        this.world_gravity = world_gravity;
        this.alignment_methods = alignment_methods;
        this.alignment_method = alignment_method;
        this.data_storage_interval = data_storage_interval;
    }

    public void updateAccelerometer(long event_timestamp, float[] accel_values, double[] gravity) {
        // Transform the new acceleration readings into the world coordinate frame
        final double dT = (event_timestamp - accel_timestamp) / NANOSECONDS;
        double[] aligned_accel = alignAcceleration(accel_values, gravity, dT);

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
        accel_timestamp = event_timestamp;
    }

    public void updateGyroscope(long event_timestamp, float[] gyro_values) {
        if (gyro_timestamp != 0) {
            final double dT = (event_timestamp - gyro_timestamp) / NANOSECONDS;
            double[] delta_rotation_vector = CoordinateShift.axisAngleVector(CoordinateShift.toDoubleArray(gyro_values), dT);
            Vector3D rotation_axis = new Vector3D(Arrays.copyOfRange(delta_rotation_vector, 0, 3));

            // Obtain rotation matrix from axis angle vector and calculate degrees turned along each axis
            double[][] delta_rotation_matrix = new Rotation(rotation_axis, delta_rotation_vector[3], RotationConvention.VECTOR_OPERATOR).getMatrix();
            turned_angle[0] += Math.toDegrees(Math.atan2(delta_rotation_matrix[2][1], delta_rotation_matrix[2][2]));
            turned_angle[1] += Math.toDegrees(Math.atan2(-delta_rotation_matrix[2][0], CoordinateShift.magnitude(Arrays.copyOfRange(delta_rotation_matrix[2], 1, 3))));
            turned_angle[2] += Math.toDegrees(Math.atan2(delta_rotation_matrix[1][0], delta_rotation_matrix[0][0]));
        }
        angular_accel = CoordinateShift.toDoubleArray(gyro_values);
        gyro_timestamp = event_timestamp;
    }

    private double[] alignAcceleration(float[] accel_values, double[] gravity, double dT) {
        double[] aligned_accel;
        if (alignment_method.equals(alignment_methods[0])) {
            // Update quaternion representing device pose using gyroscope and accelerometer values
            pose = CoordinateShift.newQuaternionPose(pose, accel_values, angular_accel, dT);
            // Use quaternion to rotate acceleration values with formula qvq*
            aligned_accel = pose.multiply(new Quaternion(CoordinateShift.toDoubleArray(accel_values))).multiply(pose.getConjugate()).getVectorPart();
        } else {
            // Obtain rotation matrix by comparing gravity in device's and in world's axes
            double[] rot_matrix = CoordinateShift.rotationMatrix(gravity, world_gravity.clone());
            // Apply the rotation matrix to the acceleration readings
            aligned_accel = new double[]{0, 0, 0};
            for (int row = 0; row < accel_values.length; row++) {
                for (int comp = 0; comp < accel_values.length; comp++) {
                    aligned_accel[row] += accel_values[comp] * rot_matrix[row * 3 + comp];
                }
            }
        }
        // Subtract gravity from the newly obtained acceleration values in the world coordinate frame
        for (int index = 0; index < aligned_accel.length; ++index) {
            aligned_accel[index] -= world_gravity[index];
        }
        return aligned_accel;
    }

}