package com.nyu.imu_processing;

import org.apache.commons.math3.complex.Quaternion;

public class CoordinateShift {

    static double[] rotationMatrix(double[] gravity, double[] world_gravity) {
        double[] norm_gravity = normalized(gravity);
        double[] norm_world_gravity = normalized(world_gravity);

        // Use the cross product between gravity in the phone's vs. the world's coordinate frame
        double[] cross_product = crossProduct(norm_world_gravity, norm_gravity);

        // The cross product avoids trigonometry and lessens computation
        double sinA = magnitude(cross_product);
        double[] axis = normalized(cross_product);
        double cosA = 0;
        for (int comp = 0; comp < 3; ++comp) {
            cosA += norm_world_gravity[comp] * norm_gravity[comp];
        }
        double oneMinusCosA = 1 - cosA;

        // Calculate the matrix describing the world space's basis in terms of the phone's axes
        double[] rot_matrix = {(axis[0] * axis[0] * oneMinusCosA) + cosA,
                (axis[1] * axis[0] * oneMinusCosA) - (sinA * axis[2]),
                (axis[2] * axis[0] * oneMinusCosA) + (sinA * axis[1]),
                (axis[0] * axis[1] * oneMinusCosA) + (sinA * axis[2]),
                (axis[1] * axis[1] * oneMinusCosA) + cosA,
                (axis[2] * axis[1] * oneMinusCosA) - (sinA * axis[0]),
                (axis[0] * axis[2] * oneMinusCosA) - (sinA * axis[1]),
                (axis[1] * axis[2] * oneMinusCosA) + (sinA * axis[0]),
                (axis[2] * axis[2] * oneMinusCosA) + cosA};

        // Obtaining this matrix's transpose, which rotates the phone's axes back to the world's
        double[] transpose = new double[9];
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 3; ++col) {
                transpose[row * 3 + col] = rot_matrix[col * 3 + row];
            }
        }
        return transpose;
    }

    static double[] axisAngleVector(double[] angular_accel, double dT) {
        // Calculate the angular speed of the sample
        double omegaMagnitude = magnitude(angular_accel);
        // Normalize the rotation vector if it's big enough to get the axis
        // (that is, EPSILON should represent your maximum allowable margin of error)
        double[] norm_rotation = normalized(angular_accel);

        // Integrate around this axis with the angular speed by the timestep
        // in order to get a delta rotation from this sample over the timestep
        // We will convert this axis-angle representation of the delta rotation
        // into a quaternion before turning it into the rotation matrix.
        double thetaOverTwo = omegaMagnitude * dT / 2;
        double sinThetaOverTwo = Math.sin(thetaOverTwo);
        double cosThetaOverTwo = Math.cos(thetaOverTwo);
        double[] deltaRotationVector = new double[4];
        for (int index = 0; index < 3; ++index) {
            deltaRotationVector[index] = sinThetaOverTwo * norm_rotation[index];
        }
        deltaRotationVector[3] = cosThetaOverTwo;
        return deltaRotationVector;
    }

    static Quaternion newQuaternionPose(Quaternion last_pose, float[] acceleration, double[] angular_accel, double dT) {
        double[] norm_acceleration = normalized(toDoubleArray(acceleration));
        double q0 = last_pose.getQ0(), q1 = last_pose.getQ1(), q2 = last_pose.getQ2(), q3 = last_pose.getQ3();
        // Estimated direction of gravity and magnetic flux
        double[] approx_field = {2 * (q1 * q3 - q0 * q2), 2 * (q0 * q1 + q2 * q3), q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3};
        // Error is sum of cross product between estimated direction and measured direction of field
        double[] error = crossProduct(norm_acceleration, approx_field);
        // Apply feedback terms
        for (int index = 0; index < angular_accel.length; ++index) {
            angular_accel[index] += error[index];
        }
        // Compute rate of change of quaternion
        Quaternion delta_pose = last_pose.multiply(new Quaternion(angular_accel)).multiply(0.5);
        // Integrate to yield quaternion
        Quaternion new_pose = last_pose.add(delta_pose.multiply(dT));
        return new_pose.normalize();
    }

    static double[] crossProduct(double[] vector1, double[] vector2) {
        double[] cross_product = new double[3];
        for (int comp = 1; comp < vector1.length + 1; ++comp) {
            cross_product[comp - 1] = vector1[comp % 3] * vector2[(comp + 1) % 3] -
                    vector1[(comp + 1) % 3] * vector2[comp % 3];
        }
        return cross_product;
    }

    static double magnitude(double[] vector) {
        double magnitude_squared = 0;
        for (double comp : vector) {
            magnitude_squared += Math.pow(comp, 2);
        }
        return Math.sqrt(magnitude_squared);
    }

    static double[] normalized(double[] vector) {
        double magnitude = magnitude(vector);
        if (magnitude != 0) {
            for (int comp = 0; comp < vector.length; ++comp) {
                vector[comp] /= magnitude;
            }
        }
        return vector;
    }

    public static double[] toDoubleArray(float[] input) {
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; ++i) output[i] = input[i];
        return output;
    }

}
