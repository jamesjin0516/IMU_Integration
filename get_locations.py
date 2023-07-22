import argparse
import os
import re
import socket

import cv2
from PIL import Image, ImageDraw


def configure_parser_and_parse():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data_name", default=None, type=str, required=True)
    parser.add_argument("--csv_host", default="128.122.136.173,30001", type=str, required=False)
    return parser.parse_args()


def get_pose_from_image(image_path, sock):
    print(f"image_path: {image_path}")
    # Open the specified image, resize it to height 640, and convert it to byte format
    image = cv2.imread(image_path, cv2.IMREAD_UNCHANGED)
    height, width, _ = image.shape
    new_size = (640, int(width * (640 / height)))
    resized_image = cv2.resize(image, new_size)
    _, image_byte_buffer = cv2.imencode(".jpg", resized_image)
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


def draw_trajectory(coordinates):
    if len(coordinates) == 0: return
    # For an IMU trajectory, plot the localization location as a circle and integrated positions as continuous line segments
    # TODO: parameterize floorplan filenames
    floorplan = Image.open("floorplan_modified.png")
    draw = ImageDraw.Draw(floorplan)
    draw.ellipse((tuple([axis - 10 for axis in coordinates[0]]), tuple([axis + 10 for axis in coordinates[0]])), "green")
    draw.line([tuple([coordinates[i][dim] for dim in range(len(coordinates[i]))]) for i in range(1, len(coordinates))], "blue", 5)
    floorplan.save("floorplan_modified.png")
    floorplan.close()


def find_closest_data_point(data_lines, data_type, image_timestamp, previous_pose, line_index):
    # Regular expression patterns for extracting specific IMU information from text files
    re_get = {"timestamp": lambda line: int(re.match(r"[0-9]+", line).group()),
              "type": lambda line: re.search(r"(?<= )[a-zA-Z0-9 ]+(?= )", line).group(),
              "values": lambda line: [float(value) for value in
                                      re.search(r"(?<=\[)[-0-9.eE, ]+(?=])", line).group().split(",")]}

    # Retrieve currently examined line of IMU data and parse timestamp and localized pose from strings
    data_line, previous_index = data_lines[line_index].strip("\n"), None
    coordinates = []
    image_timestamp, previous_pose = int(image_timestamp), re_get["values"](previous_pose) if previous_pose else None
    # Keep examining the next line of IMU data until timestamp surpasses image's timestamp
    while line_index < len(data_lines) and re_get["timestamp"](data_line) < image_timestamp:
        if re_get["type"](data_line) == data_type:
            # Check for valid last-known IMU data and successful previous localization; if so, calculate IMU trajectory
            if previous_index and previous_pose:
                previous_position, new_position = re_get["values"](data_lines[previous_index]), re_get["values"](data_line)
                if len(coordinates) == 0: coordinates.append(previous_pose[:2])
                new_coordinate = [coordinates[len(coordinates) - 1][dim] + new_position[dim] - previous_position[dim]
                                  for dim in range(len(new_position) - 1)]
                coordinates.append(new_coordinate)
            previous_index = line_index
        line_index += 1
        data_line = data_lines[line_index].strip("\n")

    print(f"coordinates for timestamp {image_timestamp}: {coordinates}\n")
    draw_trajectory(coordinates)
    return previous_index


if __name__ == "__main__":
    args = configure_parser_and_parse()
    images_dir = args.data_name if args.data_name.endswith("/") else args.data_name + "/"
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((args.csv_host.split(",")[0], int(args.csv_host.split(",")[1])))
    # Open the text file of IMU data and store its lines into a list
    with open(args.data_name.strip("/") + ".txt", "r") as data_file:
        data_lines = data_file.readlines()
    image_names = sorted(os.listdir(images_dir))

    line_index = 0
    previous_pose = None
    # Localize each image and synchronize IMU data by advancing within the IMU data file if localization is successful
    for image_name in image_names:
        pose = get_pose_from_image(images_dir + image_name, sock)
        if pose:
            line_index = find_closest_data_point(data_lines, "position", image_name[:image_name.index(".")],
                                                 previous_pose, line_index)
        previous_pose = pose
    sock.sendall(int(0).to_bytes(4, "big"), 4)
    sock.close()
