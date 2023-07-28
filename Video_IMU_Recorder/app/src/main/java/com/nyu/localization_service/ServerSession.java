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
import android.util.Pair;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

    public ServerSession(String host, int port, Context owner_context) {
        host_port = new Pair<>(host, port);
        image_dispatcher = Executors.newSingleThreadExecutor();
        this.owner_context = owner_context;
    }

    private class ImageRequest implements Callable<String> {

        byte[] image_bytes;

        ImageRequest(byte[] image_bytes) {
            this.image_bytes = image_bytes;
        }

        @Override
        public String call() throws IOException {
            if (socket == null) socket = new Socket(host_port.first, host_port.second);
            // Decode the byte array of the image as a bitmap, resize it, and re-encode it as a byte array
            Bitmap image = BitmapFactory.decodeByteArray(image_bytes, 0, image_bytes.length);
            Matrix scale = new Matrix();
            scale.postScale(640 / (float) image.getHeight(), 640 / (float) image.getHeight());
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

    public String requestLocalization(byte[] image_bytes) throws InterruptedException, ExecutionException {
        if (pending_pose == null) {
            pending_pose = image_dispatcher.submit(new ImageRequest(image_bytes));
        // Only queue a new localization request if the previous request has finished
        } else if (pending_pose.isDone()) {
            String pose = pending_pose.get();
            image_dispatcher.submit(() -> plotPose(pose));
            pending_pose = image_dispatcher.submit(new ImageRequest(image_bytes));
            return pose;
        }
        return null;
    }

    private void plotPose(String pose) {
        if (pose.equals("None")) return;
        // Match everything between square brackets of the localized pose string
        Pattern array_pattern = Pattern.compile("(?<=\\[)[-0-9.eE, ]+(?=])");
        Matcher array_matcher = array_pattern.matcher(pose);
        float[] pose_values;
        if (array_matcher.find()) {
            String[] array_values = array_matcher.group().split(",");
            pose_values = new float[array_values.length];
            for (int dim = 0; dim < pose_values.length; ++dim) {
                pose_values[dim] = Float.parseFloat(array_values[dim].trim());
            }
        } else {
            throw new IllegalArgumentException("Format of pose (" + pose + ") cannot be parsed by pattern " + array_pattern.pattern());
        }

        // Read the floorplan's dimensions first, and then decode the actual floorplan as a bitmap
        // TODO: Download the floorplan at runtime instead (modify server-side code)
        String floorplan_path = owner_context.getExternalFilesDir(Environment.DIRECTORY_DCIM) + File.separator + "floorplan_modified.png";
        BitmapFactory.Options read_size_toggle = new BitmapFactory.Options();
        read_size_toggle.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(floorplan_path, read_size_toggle);
        read_size_toggle.inJustDecodeBounds = false;
        Bitmap floorplan = BitmapFactory.decodeFile(floorplan_path, read_size_toggle);
        // Make an identically sized new bitmap to allow mutability
        Bitmap floorplan_altered = Bitmap.createBitmap(floorplan.getWidth(), floorplan.getHeight(), floorplan.getConfig());
        Canvas canvas = new Canvas(floorplan_altered);
        Paint paint_green = new Paint();
        paint_green.setColor(Color.GREEN);
        paint_green.setStrokeWidth(5);
        // Use the newly set up drawing canvas to copy the floorplan into the new mutable bitmap
        canvas.drawBitmap(floorplan, new Matrix(), paint_green);
        canvas.drawCircle(pose_values[0], pose_values[1], 10, paint_green);
        // Save the modified floorplan
        File floorplan_file = new File(owner_context.getExternalFilesDir(Environment.DIRECTORY_DCIM), "floorplan_modified.png");
        try {
            Log.i("FloorplanDraw", "Attempt to save floorplan to " + floorplan_file.getPath() + " (new: " + floorplan_file.createNewFile() + ")");
            floorplan_altered.compress(Bitmap.CompressFormat.PNG, 100, new FileOutputStream(floorplan_file));
        } catch (IOException exception) {
            exception.printStackTrace();
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
