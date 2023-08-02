import argparse
import os
import re
import socket
import struct
import sys
from multiprocessing.pool import ThreadPool

import cv2
import numpy as np
import yaml


def configure_parser_and_parse():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server_config_path", default=None, type=str, required=False)
    parser.add_argument("--server_dir", default=None, type=str, required=False)
    parser.add_argument("--csv_locations", default=None, type=str, required=False)
    parser.add_argument("--floorplan_scale", default=None, type=float, required=False)
    parser.add_argument("--port_id", type=int, required=False)
    return parser.parse_args()


def load_configs(args):
    # Load in the configurations for server networking, localization preferences, and hierarchical localization
    server_config_path = args.server_config_path if args.server_config_path else "localization.yaml"
    with open(server_config_path, 'r') as f:
        server_config = yaml.safe_load(f)
    if args.server_dir: server_config["server_dir"] = args.server_dir
    server_dir = args.server_dir if args.server_dir else server_config["server_dir"]
    hloc_config_path = server_dir + "configs/hloc.yaml"
    with open(hloc_config_path, 'r') as f:
        hloc_config = yaml.safe_load(f)

    # Provide option for overriding location configuration using arguments
    location_keys = list(server_config["location"].keys())
    location_values = args.csv_locations.split(",") if args.csv_locations else [None] * 3
    location_values += [args.floorplan_scale] if args.floorplan_scale else [None]
    for key_ind in range(len(location_keys)):
        if location_values[key_ind]: server_config["location"][location_keys[key_ind]] = location_values[key_ind]

    return server_config, hloc_config


def prepare_Hloc_socket(server_config, hloc_config):
    sys.path.append(server_config["server_dir"].rstrip(os.sep) + os.sep + "src")
    import loader
    from track import Hloc

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind((server_config["server"]["host"], args.port_id if args.port_id else server_config["server"]["port"]))
    sock.listen(5)
    print("Socket configured")

    # Extract path and scale of floorplan
    raw_floorplan_info = [server_config["IO_root"].rstrip(os.sep), "data"] + list(server_config["location"].values())
    floorplan_scale = raw_floorplan_info.pop()
    floorplan_dir_path, floorplan_path = os.sep.join(raw_floorplan_info), None
    for filename in os.listdir(floorplan_dir_path):
        if re.match(r"[\s_-]*[fF]loor[\s_-]*[pP]lan[\s_-]*\.\w", filename):
            floorplan_path = floorplan_dir_path.rstrip(os.sep) + os.sep + filename
            break
    floorplan_info = (floorplan_path, floorplan_scale)
    imu_transform = np.array([server_config["IMU_transform"][column] for column in server_config["IMU_transform"].keys()],
                             np.double).transpose()

    map_data = loader.load_data(server_config)
    hloc = Hloc(server_config["server_dir"], map_data, hloc_config)

    return hloc, sock, floorplan_info, imu_transform


def recvall(sock, count):
    buf = b''
    # Keep using the socket to receive data until the remaining unreceived length decreases to 0
    while count:
        newbuf = sock.recv(count)
        if not newbuf:
            return None
        buf += newbuf
        count -= len(newbuf)
    return buf


def receive_images(hloc, client_sock, floorplan_info, imu_transform, command):
    print("Socket accepted connection")

    while command > 0:
        if command == 1:
            # Receive the incoming image and decode it from bytes
            length = recvall(client_sock, 4)
            image_bytes = recvall(client_sock, int.from_bytes(length, 'big'))
            image_array = np.frombuffer(image_bytes, np.uint8)
            image = cv2.imdecode(image_array, cv2.IMREAD_UNCHANGED)
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            # Use created hierarchical localization instance to localize image and send result back
            pose = hloc.get_location(image)
            print(f"Pose: {pose}")
            pose = str(pose) if pose else "None"
            pose_bytes = bytes(pose, "UTF-8")
            client_sock.sendall(len(pose).to_bytes(4, "big"))
            client_sock.sendall(pose_bytes)
        elif command == 2:
            # Send floorplan image and scale to client
            if floorplan_info[0]:
                with open(floorplan_info[0], "rb") as floorplan:
                    floorplan_bytes = floorplan.read()
            else:
                floorplan_bytes = b''
            client_sock.sendall(len(floorplan_bytes).to_bytes(4, "big"))
            client_sock.sendall(floorplan_bytes)
            client_sock.sendall(struct.pack("!d", floorplan_info[1]))
            # Send the IMU's orientation with respect to the world axes as a matrix
            client_sock.sendall(len(imu_transform).to_bytes(4, "big"))
            for row in imu_transform:
                for elem in row:
                    client_sock.sendall(struct.pack('!d', float(elem)))

        # Use designated integer codes sent by the client to control actions
        command = int.from_bytes(recvall(client_sock, 4), "big", signed=True)

    client_sock.close()


if __name__ == "__main__":
    args = configure_parser_and_parse()
    hloc, sock, floorplan_info, imu_transform = prepare_Hloc_socket(*load_configs(args))
    clients_manager, clients_status = ThreadPool(5), []

    while True:
        for index in range(len(clients_status) - 1, -1, -1):
            if clients_status[index].ready():
                del clients_status[index]
        client_sock, _ = sock.accept()
        command = int.from_bytes(recvall(client_sock, 4), "big", signed=True)
        if command == -1:
            client_sock.close()
            break
        clients_status.append(clients_manager.apply_async(receive_images, (hloc, client_sock, floorplan_info, imu_transform, command)))

    clients_manager.close()
    sock.close()
