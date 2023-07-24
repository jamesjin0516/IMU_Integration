package com.nyu.imu_processing;

import org.apache.commons.math3.complex.Quaternion;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationConvention;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IMUSession {

    private static final double NANOSECONDS = Math.pow(10, 9);
    private final double[] world_gravity;
    private final String[] alignment_methods;
    private String alignment_method = "";
    private final long integration_interval;
    private final ArrayList<Pair<Long, double[]>> accels_info = new ArrayList<>(), velos_info = new ArrayList<>(), poss_info = new ArrayList<>();
    private final double[] angular_accel = {0, 0, 0}, turned_angle = {0, 0, 0};
    private Quaternion pose = Quaternion.IDENTITY;
    private long gyro_timestamp = 0, integration_timestamp;

    public IMUSession(double[] world_gravity, String[] alignment_methods, String alignment_method, int integration_interval) {
        this.world_gravity = world_gravity;
        this.alignment_methods = alignment_methods;
        this.alignment_method = alignment_method;
        this.integration_interval = integration_interval;
    }

    public String updateAccelerometer(long event_timestamp, float[] accel_values, double[] gravity) {
        // Transform the new acceleration readings into the world coordinate frame
        final double dT = accels_info.size() == 0 ? 0 : (event_timestamp - accels_info.get(accels_info.size() - 1).getFirst()) / NANOSECONDS;
        double[] aligned_accel = alignAcceleration(accel_values, gravity, dT);

        // Append to accelerations data and initialize velocities and positions data if necessary
        accels_info.add(new Pair<>(event_timestamp, aligned_accel));
        if (velos_info.size() == 0) velos_info.add(new Pair<>(accels_info.get(0).getFirst(), new double[]{0, 0, 0}));
        if (poss_info.size() == 0) poss_info.add(new Pair<>(accels_info.get(0).getFirst(), new double[]{0, 0, 0}));
        // Once the specified duration has elapsed, do a bulk integration on all buffered accelerations data
        if (event_timestamp - integration_timestamp > integration_interval) {
            doubleIntegration();
            integration_timestamp = event_timestamp;
            return generatePositionData();
        }
        return "";
    }

    private String generatePositionData() {
        // Verify the same number of accelerations, velocities, and positions calculated for the same sequence of timestamps
        assert accels_info.size() == velos_info.size() && velos_info.size() == poss_info.size() : "Kinematic arrays are of different length";
        for (int index = 0; index < accels_info.size(); ++index) {
            assert Objects.equals(accels_info.get(index).getFirst(), velos_info.get(index).getFirst()) &&
                    Objects.equals(velos_info.get(index).getFirst(), poss_info.get(index).getFirst()) : "Timestamps are inconsistent across kinematic arrays";
        }
        // Remove and remember the last values for each of the three quantities; also conveniently output the remaining positions
        Pair<Long, double[]> remain_pos = poss_info.remove(poss_info.size() - 1);
        List<String> output = poss_info.stream().flatMap(pos -> Stream.of(pos.getFirst() + " position " + Arrays.toString(pos.getSecond()) + '\n')).collect(Collectors.toList());
        Pair<Long, double[]> remain_accel = accels_info.remove(accels_info.size() - 1);
        Pair<Long, double[]> remain_velo = velos_info.remove(velos_info.size() - 1);
        // Reset the last values as the first values (the reference for the next round of integration) for each quantity
        accels_info.clear();
        accels_info.add(remain_accel);
        velos_info.clear();
        velos_info.add(remain_velo);
        poss_info.clear();
        poss_info.add(remain_pos);
        return String.join("", output);
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
        System.arraycopy(CoordinateShift.toDoubleArray(gyro_values), 0, angular_accel, 0, angular_accel.length);
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

    /*
    Assuming no jerk
    dv = jt + a_0 dt
    v =  1/2 jt^2 + ta_0
    v = 1/2 t(2a_0 + jt)
    v = 1/2 t(a_0 + a_new)

    float deltaV = normZ * dT;
    float displacement = dT * dT * normZ / 2 + dT * velocityZ;

    Assuming constant jerk
    J: jerk; A: acceleration; V: velocity; P: position; t: time; *_0: last value of *
    dA/dt = J
    dV/dt = A = Jt + A_0
    dP/dt = V = 1/2 Jt^2 + A_0(t) + V_0
              = 1/2 t (Jt + 2 A_0) + V_0
              = 1/2 t (A + A_0) + V_0
    P = 1/6 Jt^3 + 1/2 A_0(t^2) + V_0(t) + P_0
      = 1/6 t^2 (Jt + 3A_0) + V_0(t) + P_0
      = 1/6 t^2 (A + 2A_0) + V_0(t) + P_0
    */

    private void doubleIntegration() {
        double total_dT = (accels_info.get(accels_info.size() - 1).getFirst() - accels_info.get(0).getFirst()) / NANOSECONDS;
        double avg_dT = total_dT / (accels_info.size() - 1);

        // For every acceleration value after the last integrated velocity, perform integration with itself and previous acceleration
        for (int index = velos_info.size(); index < accels_info.size(); ++index) {
            double[] prev_velo = velos_info.get(velos_info.size() - 1).getSecond(), new_velo = new double[3];
            final double dT = (accels_info.get(index).getFirst() - accels_info.get(index - 1).getFirst()) / NANOSECONDS;
            double[] curr_accel = accels_info.get(index).getSecond(), prev_accel = accels_info.get(index - 1).getSecond();
            for (int dim = 0; dim < curr_accel.length; ++dim) {
                double delta_velocity = dT * (prev_accel[dim] + curr_accel[dim]) / 2;
                new_velo[dim] = prev_velo[dim] + delta_velocity;
            }
            Pair<Long, double[]> new_velo_info = new Pair<>(accels_info.get(index).getFirst(), new_velo);
            velos_info.add(new_velo_info);
        }

        // filterQuantity(velos_info, avg_dT);

        // For every acceleration value after the last integrated position, perform second integration with its corresponding velocity as well
        for (int index = poss_info.size(); index < accels_info.size(); ++index) {
            double[] prev_pos = poss_info.get(poss_info.size() - 1).getSecond(), new_pos = new double[3];
            final double dT = (accels_info.get(index).getFirst() - accels_info.get(index - 1).getFirst()) / NANOSECONDS;
            double[] curr_accel = accels_info.get(index).getSecond(), prev_accel = accels_info.get(index - 1).getSecond();
            double[] prev_velo = velos_info.get(index - 1).getSecond();
            for (int dim = 0; dim < curr_accel.length; ++dim) {
                double delta_position = dT * dT * (curr_accel[dim] + 2 * prev_accel[dim]) / 6 + dT * prev_velo[dim];
                new_pos[dim] = prev_pos[dim] + delta_position;
            }
            Pair<Long, double[]> new_pose_info = new Pair<>(accels_info.get(index).getFirst(), new_pos);
            poss_info.add(new_pose_info);
        }

        // filterQuantity(poss_info, avg_dT);
    }

    /*
    private void filterQuantity(ArrayList<double[]> quantity, double avg_dT) {
        if (avg_dT != 0) {
            Butterworth filter = new Butterworth(1 / avg_dT);
            for (int dim = 0; dim < quantity.get(0).length; ++dim) {
                int final_dim = dim;
                double[] dim_quantity = quantity.stream().mapToDouble(velo -> velo[final_dim]).toArray();
                double[] filtered_dim = filter.highPassFilter(dim_quantity, 1, 0.2 * avg_dT);
                for (int index = 0; index < quantity.size(); ++index)
                    quantity.get(index)[final_dim] = filtered_dim[index];
            }
        }
    }
    */
}