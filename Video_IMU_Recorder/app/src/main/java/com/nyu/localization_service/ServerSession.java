package com.nyu.localization_service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.Log;

import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationConvention;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class ServerSession {

    private Socket socket;
    private final Pair<String, Integer> host_port;
    private final ExecutorService image_dispatcher;
    private Future<String> pending_pose;
    private final Future<String> setup_task;
    private double[] last_pose;
    private final List<Pair<Long, double[]>> poss_info = new ArrayList<>();
    private Pair<Bitmap, Double> floorplan_info;
    private Array2DRowRealMatrix imu_transform;

    public ServerSession(String host, int port) {
        host_port = new Pair<>(host, port);
        image_dispatcher = Executors.newSingleThreadExecutor();
        // Start the server configuration task as the camera is still setting up
        setup_task = image_dispatcher.submit(() -> {
            try {
                socket = new Socket(host_port.getFirst(), host_port.getSecond());
                retrieveLocalizationInfo();
            } catch (IOException exception) {
                exception.printStackTrace();
            }
            return "Done";
        });
    }

    private class ImageRequest implements Callable<String> {

        byte[] image_bytes;

        ImageRequest(byte[] image_bytes) {
            this.image_bytes = image_bytes;
        }

        @Override
        public String call() throws IOException {
            // Decode the byte array of the image as a bitmap, resize it, and re-encode it as a byte array
            Bitmap image = BitmapFactory.decodeByteArray(image_bytes, 0, image_bytes.length);
            Matrix scale = new Matrix();
            scale.postScale(640 / (float) image.getWidth(), 640 / (float) image.getWidth());
            Bitmap resized_image = Bitmap.createBitmap(image, 0, 0, image.getWidth(), image.getHeight(), scale, true);
            ByteArrayOutputStream image_stream = new ByteArrayOutputStream();
            resized_image.compress(Bitmap.CompressFormat.JPEG, 100, image_stream);
            byte[] resize_bytes = image_stream.toByteArray();

            // Calculate the length of the resized image byte array and send it to the server for localization
            DataOutputStream socket_ostream = new DataOutputStream(socket.getOutputStream());
            DataInputStream socket_istream = new DataInputStream(socket.getInputStream());
            socket_ostream.writeInt(1);
            socket_ostream.writeInt(resize_bytes.length);
            socket_ostream.write(resize_bytes);
            socket_ostream.flush();

            // Read the server's localized pose while ensuring the full length of data is read
            int pose_length = socket_istream.readInt();
            byte[] pose_bytes = new byte[pose_length];
            socket_istream.readFully(pose_bytes);
            String pose_str = new String(pose_bytes, StandardCharsets.UTF_8);
            return pose_str.equals("None") ? null : pose_str;
        }
    }

    private void retrieveLocalizationInfo() throws IOException {
        // Retrieve the floorplan as a byte array and the floorplan scale
        DataOutputStream socket_ostream = new DataOutputStream(socket.getOutputStream());
        DataInputStream socket_istream = new DataInputStream(socket.getInputStream());
        socket_ostream.writeInt(2);
        socket_ostream.flush();
        int floorplan_length = socket_istream.readInt();
        byte[] floorplan_bytes = new byte[floorplan_length];
        socket_istream.readFully(floorplan_bytes);
        double floorplan_scale = socket_istream.readDouble();
        // Decode the floorplan byte array into a bitmap to store for the duration of recording
        BitmapFactory.Options mutable = new BitmapFactory.Options();
        mutable.inMutable = true;
        Bitmap floorplan = BitmapFactory.decodeByteArray(floorplan_bytes, 0, floorplan_bytes.length, mutable);
        floorplan_info = new Pair<>(floorplan, floorplan_scale);

        // Receive the initial IMU orientation as a 2D array and store it as a matrix
        int matrix_side_length = socket_istream.readInt();
        double[][] matrix_array = new double[matrix_side_length][matrix_side_length];
        for (int row = 0; row < matrix_array.length; ++row) {
            for (int col = 0; col < matrix_array[row].length; ++col) {
                matrix_array[row][col] = socket_istream.readDouble();
            }
        }
        imu_transform = new Array2DRowRealMatrix(matrix_array);
    }

    public double[] requestLocalization(long image_timestamp, byte[] image_bytes, List<Pair<Long, double[]>> poss_info)
            throws InterruptedException, ExecutionException {
        image_dispatcher.submit(() -> this.poss_info.addAll(poss_info));
        if (pending_pose == null) {
            pending_pose = image_dispatcher.submit(new ImageRequest(image_bytes));
        // Only queue a new localization request if the previous request has finished
        } else if (setup_task.isDone() && pending_pose.isDone()) {
            String pose_str = pending_pose.get();
            double[] pose;
            // Only find nearest position data point if the image successfully localized
            if (pose_str != null) {
                pose = extractPose(pose_str);
                image_dispatcher.submit(() -> incrementPositions(pose, image_timestamp));
                // Only compute a new transformation matrix between image and world if 6 degree of freedom was given
                if (pose.length == 6) image_dispatcher.submit(() -> calculateNewTransformation(pose));
            } else {
                pose = null;
                last_pose = null;
            }
            pending_pose = image_dispatcher.submit(new ImageRequest(image_bytes));
            return pose;
        }
        return null;
    }

    private void incrementPositions(double[] pose, long image_timestamp) {
        plotPose(pose);

        // While iterating through positions, find pixel coordinates based on displacement from previous pose if available
        ArrayList<double[]> coordinates = new ArrayList<>();
        int index = 1;
        while (index < poss_info.size() && poss_info.get(index).getFirst() < image_timestamp) {
            if (last_pose != null) {
                // First coordinate is previous pose; coordinates after are incremented using differences between positions
                if (coordinates.size() == 0) coordinates.add(Arrays.copyOfRange(last_pose, 0, 2));
                double[] last_coordinate = coordinates.get(coordinates.size() - 1);
                coordinates.add(generateNewCoordinate(last_coordinate, index));
            }
            ++index;
        }
        plotTrajectory(coordinates);

        // Discard all consumed position and pose data
        poss_info.subList(0, index - 1).clear();
        last_pose = pose;
    }

    private void calculateNewTransformation(double[] pose) {
        // Temporarily convert to right-hand coordinate system and undo the image's associated rotation vector
        Vector3D rot_vec = new Vector3D(pose[3], -pose[4], pose[5]);
        Rotation pose_to_world = new Rotation(Vector3D.PLUS_K, Math.PI, RotationConvention.FRAME_TRANSFORM)
                .applyTo(new Rotation(rot_vec, rot_vec.getNorm(), RotationConvention.FRAME_TRANSFORM));
        double[][] pose_matrix = Arrays.stream(pose_to_world.getMatrix()).map(row -> new double[]{row[0], -row[1], row[2]})
                .toArray(double[][]::new);
        synchronized (poss_info) {
            imu_transform = new Array2DRowRealMatrix(pose_matrix);
        }
    }

    private double[] generateNewCoordinate(double[] last_coordinate, int poss_index) {
        // Apply the IMU orientation matrix to the IMU's delta position and add the result to the last calculated coordinate
        double[] world_displacement;
        synchronized (poss_info) {
            double[] displacement = IntStream.range(0, imu_transform.getColumnDimension())
                    .mapToDouble(dim -> poss_info.get(poss_index).getSecond()[dim] - poss_info.get(poss_index - 1).getSecond()[dim]).toArray();
            world_displacement = imu_transform.operate(displacement);
        }
        return IntStream.range(0, last_coordinate.length)
                .mapToDouble(dim -> last_coordinate[dim] + world_displacement[dim] / floorplan_info.getSecond()).toArray();
    }

    private void plotTrajectory(ArrayList<double[]> coordinates) {
        // Flatten the 2D collection of coordinates into x and then y axis format
        float[] flat_coordinates = new float[coordinates.size() * 2];
        for (int index = 0; index < coordinates.size(); ++index) {
            for (int dim = 0; dim < 2; ++ dim) {
                flat_coordinates[index * 2 + dim] = (float) coordinates.get(index)[dim];
            }
        }
        Canvas canvas = new Canvas(floorplan_info.getFirst());
        Paint paint_red = new Paint();
        paint_red.setColor(Color.RED);
        paint_red.setStrokeWidth(5);
        canvas.drawLines(flat_coordinates, paint_red);
    }

    private void plotPose(double[] pose) {
        // Plot the current pose as a circle and a line to the previous pose if available
        Canvas canvas = new Canvas(floorplan_info.getFirst());
        Paint paint_blue = new Paint();
        paint_blue.setColor(Color.BLUE);
        paint_blue.setStrokeWidth(10);
        canvas.drawCircle((float) pose[0], (float) pose[1], 15, paint_blue);
        if (last_pose != null) {
            canvas.drawLine((float) last_pose[0], (float) last_pose[1], (float) pose[0], (float) pose[1], paint_blue);
        }
    }

    private static double[] extractPose(String pose_str) {
        // Match everything between square brackets of the localized pose string
        Pattern array_pattern = Pattern.compile("(?<=\\[)[-0-9.eE, ]+(?=])");
        Matcher array_matcher = array_pattern.matcher(pose_str);
        if (array_matcher.find()) {
            String[] array_values = array_matcher.group().split(",");
            double[] pose_values = new double[array_values.length];
            for (int dim = 0; dim < pose_values.length; ++dim) {
                pose_values[dim] = Double.parseDouble(array_values[dim].trim());
            }
            return pose_values;
        } else {
            throw new IllegalArgumentException("Format of pose string (" + pose_str + ") cannot be parsed by pattern " + array_pattern.pattern());
        }
    }

    public void shutdown(File floorplan_file) throws ExecutionException, InterruptedException {
        // Tell the server to stop this particular connection and save the modified floorplan as a file
        Future<?> termination_task = image_dispatcher.submit(() -> {
            try {
                DataOutputStream socket_ostream = new DataOutputStream(socket.getOutputStream());
                socket_ostream.writeInt(0);
                socket.close();
                Log.i("FloorplanDraw", "Attempt to save floorplan to " + floorplan_file.getPath() + " (new: " + floorplan_file.createNewFile() + ")");
                floorplan_info.getFirst().compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(floorplan_file));
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        // Make sure the termination task is complete before shutting down the task handling object
        termination_task.get();
        image_dispatcher.shutdown();
    }

}
