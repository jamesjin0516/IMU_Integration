package com.nyu.localization_service;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Environment;
import android.util.Log;

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

public class ServerSession {

    private Socket socket;
    private final Pair<String, Integer> host_port;
    private final ExecutorService image_dispatcher;
    private final Context owner_context;
    private Future<String> pending_pose;
    private String last_pose;
    private final List<Pair<Long, double[]>> poss_info = new ArrayList<>();
    // TODO: let server send information such as floorplan scale and regex
    private static final double FLOORPLAN_SCALE = 0.01209306372;

    public ServerSession(String host, int port, Context owner_context) {
        host_port = new Pair<>(host, port);
        image_dispatcher = Executors.newSingleThreadExecutor();
        this.owner_context = owner_context;
        image_dispatcher.submit(() -> socket = new Socket(host_port.getFirst(), host_port.getSecond()));
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
            int pose_length = socket_istream.readInt(), amount_read = 0;
            byte[] pose_bytes = new byte[pose_length];
            while (amount_read < pose_length) {
                amount_read = socket_istream.read(pose_bytes, amount_read, pose_length - amount_read);
            }
            return new String(pose_bytes, StandardCharsets.UTF_8);
        }
    }

    public String requestLocalization(long image_timestamp, byte[] image_bytes, List<Pair<Long, double[]>> poss_info)
            throws InterruptedException, ExecutionException {
        this.poss_info.addAll(poss_info);
        if (pending_pose == null) {
            pending_pose = image_dispatcher.submit(new ImageRequest(image_bytes));
        // Only queue a new localization request if the previous request has finished
        } else if (pending_pose.isDone()) {
            String pose = pending_pose.get();
            // Only find nearest position data point if the image succesfully localized
            if (!pose.equals("None")) {
                image_dispatcher.submit(() -> incrementPositions(pose, image_timestamp));
            } else {
                last_pose = null;
            }
            pending_pose = image_dispatcher.submit(new ImageRequest(image_bytes));
            return pose;
        }
        return null;
    }

    private void incrementPositions(String pose, long image_timestamp) {
        Bitmap floorplan = prepareFloorplan();
        plotPose(floorplan, pose);

        // While iterating through positions, find pixel coordinates based on displacement from previous pose if available
        ArrayList<float[]> coordinates = new ArrayList<>();
        int index = 1;
        while (index < poss_info.size() && poss_info.get(index).getFirst() < image_timestamp) {
            if (last_pose != null) {
                // First coordinate is previous pose; coordinates after are incremented using differences between positions
                if (coordinates.size() == 0) coordinates.add(Arrays.copyOfRange(extractPose(last_pose), 0, 2));
                float[] last_coordinate = coordinates.get(coordinates.size() - 1);
                float[] new_coordinate = new float[last_coordinate.length];
                for (int dim = 0; dim < new_coordinate.length; ++dim) {
                    new_coordinate[dim] = last_coordinate[dim] + (float) ((poss_info.get(index).getSecond()[dim]
                            - poss_info.get(index - 1).getSecond()[dim]) / FLOORPLAN_SCALE);
                }
                coordinates.add(new_coordinate);
            }
            ++index;
        }
        plotTrajectory(floorplan, coordinates);

        // Discard all consumed position and pose data
        poss_info.subList(0, index - 1).clear();
        last_pose = pose;
        saveFloorplan(floorplan);
    }

    private void plotTrajectory(Bitmap floorplan, ArrayList<float[]> coordinates) {
        // Flatten the 2D collection of coordinates into x and then y axis format
        float[] flat_coordinates = new float[coordinates.size() * 2];
        for (int index = 0; index < coordinates.size(); ++index) {
            System.arraycopy(coordinates.get(index), 0, flat_coordinates, index * 2, coordinates.get(index).length);
        }
        Canvas canvas = new Canvas(floorplan);
        Paint paint_red = new Paint();
        paint_red.setColor(Color.RED);
        paint_red.setStrokeWidth(5);
        canvas.drawLines(flat_coordinates, paint_red);
    }

    private void plotPose(Bitmap floorplan, String pose) {
        // Plot the current pose as a circle and a line to the previous pose if available
        float[] pose_values = extractPose(pose);
        Canvas canvas = new Canvas(floorplan);
        Paint paint_blue = new Paint();
        paint_blue.setColor(Color.BLUE);
        paint_blue.setStrokeWidth(10);
        canvas.drawCircle(pose_values[0], pose_values[1], 15, paint_blue);
        if (last_pose != null) {
            float[] last_pose_values = extractPose(last_pose);
            canvas.drawLine(last_pose_values[0], last_pose_values[1], pose_values[0], pose_values[1], paint_blue);
        }
    }

    private void saveFloorplan(Bitmap floorplan) {
        File floorplan_file = new File(owner_context.getExternalFilesDir(Environment.DIRECTORY_DCIM), "floorplan_modified.png");
        try {
            Log.i("FloorplanDraw", "Attempt to save floorplan to " + floorplan_file.getPath() + " (new: " + floorplan_file.createNewFile() + ")");
            floorplan.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(floorplan_file));
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }

    private Bitmap prepareFloorplan() {
        // Read the floorplan's dimensions first, and then decode the actual floorplan as a bitmap
        // TODO: Download the floorplan at runtime instead (modify server-side code)
        String floorplan_path = owner_context.getExternalFilesDir(Environment.DIRECTORY_DCIM) + File.separator + "floorplan_modified.png";
        BitmapFactory.Options read_size_toggle = new BitmapFactory.Options();
        read_size_toggle.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(floorplan_path, read_size_toggle);
        read_size_toggle.inJustDecodeBounds = false;
        Bitmap floorplan = BitmapFactory.decodeFile(floorplan_path, read_size_toggle);
        // Make an identically sized new bitmap to allow mutability
        return floorplan.copy(floorplan.getConfig(), true);
    }

    private static float[] extractPose(String pose) {
        // Match everything between square brackets of the localized pose string
        Pattern array_pattern = Pattern.compile("(?<=\\[)[-0-9.eE, ]+(?=])");
        Matcher array_matcher = array_pattern.matcher(pose);
        if (array_matcher.find()) {
            String[] array_values = array_matcher.group().split(",");
            float[] pose_values = new float[array_values.length];
            for (int dim = 0; dim < pose_values.length; ++dim) {
                pose_values[dim] = Float.parseFloat(array_values[dim].trim());
            }
            return pose_values;
        } else {
            throw new IllegalArgumentException("Format of pose (" + pose + ") cannot be parsed by pattern " + array_pattern.pattern());
        }
    }

    public void shutdown() throws ExecutionException, InterruptedException {
        // Send a termination signal to the server
        Future<?> termination_task = image_dispatcher.submit(() -> {
            try {
                DataOutputStream socket_ostream = new DataOutputStream(socket.getOutputStream());
                socket_ostream.writeInt(0);
                socket_ostream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        // Make sure the task is complete before shutting down the task handling object
        termination_task.get();
        image_dispatcher.shutdown();
    }

}
