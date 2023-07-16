package com.nyu.baseline;

class CoordinateShift {

    static double[] rotationMatrix(double[] gravity, double[] norm_world_gravity) {
        double[] norm_gravity = normalized(gravity);

        // Use the cross product between gravity in the phone's vs. the world's coordinate frame
        double[] cross_product = new double[3];
        for (int comp = 1; comp < 4; ++comp) {
            cross_product[comp - 1] = norm_world_gravity[comp % 3] * norm_gravity[(comp + 1) % 3] -
                    norm_world_gravity[(comp + 1) % 3] * norm_gravity[comp % 3];
        }

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

    static float[] axisAngleVector(double[] rotation, double dT) {
        // Calculate the angular speed of the sample
        double omegaMagnitude = CoordinateShift.magnitude(rotation);
        // Normalize the rotation vector if it's big enough to get the axis
        // (that is, EPSILON should represent your maximum allowable margin of error)
        double[] norm_rotation = CoordinateShift.normalized(rotation);

        // Integrate around this axis with the angular speed by the timestep
        // in order to get a delta rotation from this sample over the timestep
        // We will convert this axis-angle representation of the delta rotation
        // into a quaternion before turning it into the rotation matrix.
        double thetaOverTwo = omegaMagnitude * dT / 2;
        double sinThetaOverTwo = Math.sin(thetaOverTwo);
        double cosThetaOverTwo = Math.cos(thetaOverTwo);
        float[] deltaRotationVector = new float[4];
        for (int index = 0; index < 3; ++index) {
            deltaRotationVector[index] = (float) (sinThetaOverTwo * norm_rotation[index]);
        }
        deltaRotationVector[3] = (float) cosThetaOverTwo;
        return deltaRotationVector;
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

    static double[] toDoubleArray(float[] input) {
        double[] output = new double[input.length];
        for (int i = 0; i < input.length; ++i) output[i] = input[i];
        return output;
    }

}
