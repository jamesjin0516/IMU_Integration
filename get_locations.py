import argparse
import os
import re
import socket

import cv2
import numpy as np
import yaml

from generate_outputs import draw_trajectory, write_to_results, increment_coordinates


def configure_parser_and_parse():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data_name", default=None, type=str, required=False)
    parser.add_argument("--csv_host", default=None, type=str, required=False)
    return parser.parse_args()


def prepare_for_localization():
    with open("localization.yaml", "r") as config_file:
        config = yaml.safe_load(config_file)
    args = configure_parser_and_parse()
    # Configure image sources and socket information
    data_name = (args.data_name if args.data_name else config["data_name"]).strip("/")
    image_names, floorplan_scale = sorted(os.listdir(data_name)), config["location"]["scale"]
    host_port = (args.csv_host.split(",")[0], int(args.csv_host.split(",")[1])) if args.csv_host else \
                (config["server"]["host"], config["server"]["port"])
    # Open the text file of IMU data and store its lines into a list; prepare the data's transformation matrix
    with open(data_name + ".txt", "r") as data_file:
        data_lines = data_file.readlines()
    imu_transform = np.array([config["IMU_transform"][column] for column in config["IMU_transform"].keys()]).transpose()
    # Regular expression patterns for extracting specific IMU information from text files
    re_get = {"timestamp": lambda line: int(re.match(config["IMU_regex"]["timestamp"], line).group()),
              "type": lambda line: re.search(config["IMU_regex"]["type"], line).group(),
              "values": lambda line: [float(value) for value in re.search(config["IMU_regex"]["values"], line).group().split(",")]}
    return host_port, data_name, image_names, floorplan_scale, data_lines, imu_transform, re_get


def get_pose_from_image(image_path, sock):
    print(f"image_path: {image_path}")
    # Open the specified image, resize it to height 640, and convert it to byte format
    image = cv2.imread(image_path, cv2.IMREAD_UNCHANGED)
    height, width, _ = image.shape
    new_size = (int(width * (640 / height)), 640)
    resized_image = cv2.resize(image, new_size)
    _, image_byte_buffer = cv2.imencode(image_path[image_path.rindex("."):], resized_image)
    image_bytes = image_byte_buffer.tobytes()

    # Send the number 1 to signal incoming image, send the image data's length, and send the data
    sock.sendall(int(1).to_bytes(4, "big"), 4)
    sock.sendall(len(image_bytes).to_bytes(4, "big"), 4)
    sock.sendall(image_bytes)

    # Receive the length of pose, or localization result, data, and receive the pose data
    pose_length = int.from_bytes(sock.recv(4), "big")
    pose_bytes = sock.recv(pose_length)
    pose = pose_bytes.decode("utf-8")
    print(f"pose: {pose}")
    return None if pose == "None" else pose


def find_closest_data_point(data_lines, data_type, image_timestamp, previous_pose, line_index, floorplan_scale, imu_transform, re_get):
    # Retrieve currently examined line of IMU data and parse timestamp and localized pose from strings
    data_line, previous_index = data_lines[line_index].strip("\n"), None
    coordinates = []
    image_timestamp, previous_pose = int(image_timestamp), re_get["values"](previous_pose) if previous_pose else None
    # Keep examining the next line of IMU data until timestamp surpasses image's timestamp
    while line_index < len(data_lines) and re_get["timestamp"](data_line) < image_timestamp:
        if re_get["type"](data_line) == data_type:
            # Check for valid last-known IMU data and successful previous localization
            if previous_index and previous_pose:
                increment_coordinates(imu_transform, coordinates, data_lines[previous_index], data_line, previous_pose, floorplan_scale, re_get)
            previous_index = line_index
        line_index += 1
        data_line = data_lines[line_index].strip("\n")

    print(f"coordinates for timestamp {image_timestamp}: {coordinates}")
    # TODO: Label consecutive image poses so the actual vs. estimated trajectory is comparable
    draw_trajectory(coordinates)
    return previous_index, coordinates[-1] if len(coordinates) > 0 else None


if __name__ == "__main__":
    host_port, data_name, image_names, floorplan_scale, data_lines, imu_transform, re_get = prepare_for_localization()
    write_to_results(data_name)
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect(host_port)

    line_index = 0
    previous_pose, previous_name = None, None
    # Localize each image and synchronize IMU data by advancing within the IMU data file if localization is successful
    for image_name in image_names:
        pose = get_pose_from_image(data_name + "/" + image_name, sock)
        # TODO: prevent image poses that are too close to each other
        """
        if pose and previous_pose and np.linalg.norm(np.delete(np.subtract(np.array(re_get["values"](pose)), np.array(re_get["values"](previous_pose))), -1)) < 100:
            pose = None
        """
        if not pose: continue
        line_index, last_position = find_closest_data_point(data_lines, "position", image_name[:image_name.rindex(".")],
                                                            previous_pose, line_index, floorplan_scale, imu_transform, re_get)
        # Log the two consecutively localized images' locations and the mismatch of the IMU's position with that of 2nd image
        if last_position:
            write_to_results(None, image_timestamp=previous_name[:previous_name.index(".")], location=previous_pose)
            true_location = re_get["values"](pose)
            write_to_results(None, image_timestamp=image_name[:image_name.index(".")], location=pose,
                             error=f"{last_position[0] - true_location[0]} (horizontal); "
                                   f"{last_position[1] - true_location[1]} (vertical)")
        previous_pose, previous_name = pose, image_name
        print()
    sock.sendall(int(0).to_bytes(4, "big"), 4)
    sock.close()
