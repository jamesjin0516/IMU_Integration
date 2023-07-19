import argparse
import cv2
import os
import socket

def configure_parser_and_parse():
    parser = argparse.ArgumentParser()
    parser.add_argument("--images_dir", default=None, type=str, required=True)
    return parser.parse_args()

def send_image(image_path, sock):
    print(f"image_path: {image_path}")
    image = cv2.imread(image_path, cv2.IMREAD_UNCHANGED)
    height, width, _ = image.shape
    new_size = (640, int(width * (640 / height)))
    resized_image = cv2.resize(image, new_size)
    _, image_byte_buffer = cv2.imencode(".jpg", resized_image)
    image_bytes = image_byte_buffer.tobytes()

    sock.sendall(int(1).to_bytes(4, "big"), 4)
    sock.sendall(len(image_bytes).to_bytes(4, "big"), 4)
    sock.sendall(image_bytes)
    
    pose_length = int.from_bytes(sock.recv(4), "big")
    pose_bytes = sock.recv(pose_length)
    pose = pose_bytes.decode("utf-8")
    print(f"pose: {pose}")


if __name__ == "__main__":
    args = configure_parser_and_parse()
    images_dir = args.images_dir if args.images_dir.endswith("/") else args.images_dir + "/"
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect(("128.122.136.173", 30004))
    image_names = sorted(os.listdir(images_dir))

    for image_name in image_names:
       send_image(images_dir + image_name, sock)
    sock.sendall(int(0).to_bytes(4, "big"), 4)
    sock.close()
    