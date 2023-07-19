import argparse
import cv2
import numpy as np
import socket
import sys
import yaml


def configure_parser_and_parse():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data_root", default="/home/unav/Desktop/UNav/", type=str, required=False)
    parser.add_argument("--csv_locations", default="New_York_University,6_MetroTech,Metrotech_6_Floor_4_With_Stairs", type=str, required=False)
    parser.add_argument("--floorplan_scale", default=0.01209306372, type=float, required=False)
    parser.add_argument("--port_id", type=int, required=False)
    return parser.parse_args()


def prepare_Hloc_socket(args):
    sys.path.append(args.data_root + "src")
    import loader
    from track import Hloc

    server_config_path = args.data_root + "configs/server.yaml"
    hloc_config_path = args.data_root + "configs/hloc.yaml"

    with open(server_config_path, 'r') as f:
        server_config = yaml.safe_load(f)
    with open(hloc_config_path, 'r') as f:
        hloc_config = yaml.safe_load(f)
    
    location_keys = list(server_config["location"].keys())
    location_values = args.csv_locations.split(",") + [args.floorplan_scale]
    for key_ind in range(len(location_keys)):
        server_config["location"][location_keys[key_ind]] = location_values[key_ind]
    
    map_data = loader.load_data(server_config)
    hloc = Hloc(args.data_root, map_data, hloc_config)

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.bind((server_config["server"]["host"], args.port_id if args.port_id else server_config["server"]["port"]))
    sock.listen(5)
    print("Socket configured")

    return hloc, sock


def recvall(sock, count):
    buf = b''
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

    while (command):
        command = int.from_bytes(recvall(sock, 4), "big")
        if command == 1:
            length = recvall(sock, 4)
            image_bytes = recvall(sock, int.from_bytes(length, 'big'))
            image_array = np.frombuffer(image_bytes, np.uint8)
            image = cv2.imdecode(image_array, cv2.IMREAD_UNCHANGED)
            image = cv2.cvtColor(image, cv2.COLOR_BGR2RGB)
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
