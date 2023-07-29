import argparse
import socket
import sys

import cv2
import numpy as np
import yaml


def configure_parser_and_parse():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server_dir", default=None, type=str, required=False)
    parser.add_argument("--csv_locations", default=None, type=str, required=False)
    parser.add_argument("--floorplan_scale", default=None, type=float, required=False)
    parser.add_argument("--port_id", type=int, required=False)
    return parser.parse_args()


def prepare_Hloc_socket(args):
    server_config_path = "localization.yaml"
    with open(server_config_path, 'r') as f:
        server_config = yaml.safe_load(f)
    server_dir = args.server_dir if args.server_dir else server_config["server_dir"]
    hloc_config_path = server_dir + "configs/hloc.yaml"
    with open(hloc_config_path, 'r') as f:
        hloc_config = yaml.safe_load(f)

    sys.path.append(server_dir + "src")
    import loader
    from track import Hloc

    # Provide option for overriding location configuration using arguments
    location_keys = list(server_config["location"].keys())
    location_values = args.csv_locations.split(",") if args.csv_locations else [None] * 3
    location_values += [args.floorplan_scale] if args.floorplan_scale else [None]
    for key_ind in range(len(location_keys)):
        if location_values[key_ind]: server_config["location"][location_keys[key_ind]] = location_values[key_ind]

    map_data = loader.load_data(server_config)
    hloc = Hloc(server_dir, map_data, hloc_config)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind((server_config["server"]["host"], args.port_id if args.port_id else server_config["server"]["port"]))
    sock.listen(5)
    print("Socket configured")

    return hloc, sock


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


def receive_images(hloc, sock):
    sock, _ = sock.accept()
    print("Socket accepted connection")
    command = -1

    while command:
        # Use designated integer codes sent by the client to control actions
        command = int.from_bytes(recvall(sock, 4), "big")
        if command == 1:
            # Receive the incoming image and decode it from bytes
            length = recvall(sock, 4)
            image_bytes = recvall(sock, int.from_bytes(length, 'big'))
            image_array = np.frombuffer(image_bytes, np.uint8)
            image = cv2.imdecode(image_array, cv2.IMREAD_UNCHANGED)
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
            # Use created hierarchical localization instance to localize image and send result back
            pose = hloc.get_location(image)
            print(f"Pose: {pose}")
            pose = str(pose) if pose else "None"
            pose_bytes = bytes(pose, "UTF-8")
            sock.sendall(len(pose).to_bytes(4, "big"), 4)
            sock.sendall(pose_bytes)
            command = -1

    sock.close()


if __name__ == "__main__":
    args = configure_parser_and_parse()
    hloc, sock = prepare_Hloc_socket(args)
    receive_images(hloc, sock)
    sock.close()
