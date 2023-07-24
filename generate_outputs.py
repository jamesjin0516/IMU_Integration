import csv
import os
import re

import numpy as np
from PIL import Image, ImageDraw


floorplan_name = ""


def draw_trajectory(coordinates):
    if len(coordinates) == 0: return
    # For an IMU trajectory, plot the localization location as a circle and integrated positions as continuous line segments
    global floorplan_name
    if not floorplan_name:
        for filename in os.listdir():
            if re.match(r"[\s_-]*[fF]loor[\s_-]*[pP]lan[\s_-]*\.\w", filename):
                floorplan_name = filename
                break
        new_floorplan_name = floorplan_name[:floorplan_name.rindex(".")] + "_with_trajectories" + floorplan_name[floorplan_name.rindex("."):]
        temp_copy = Image.open(floorplan_name)
        temp_copy.save(new_floorplan_name)
        temp_copy.close()
        floorplan_name = new_floorplan_name
    floorplan = Image.open(floorplan_name)
    draw = ImageDraw.Draw(floorplan)
    draw.ellipse((tuple([axis - 10 for axis in coordinates[0]]), tuple([axis + 10 for axis in coordinates[0]])), "green")
    draw.line([tuple([coordinates[i][dim] for dim in range(len(coordinates[i]))]) for i in range(1, len(coordinates))], "blue", 5)
    floorplan.save(floorplan_name)
    floorplan.close()


RESULTS_FIELDNAMES = ["image_timestamp", "imu_timestamp", "location", "error"]
data_name = ""


def write_to_results(new_data_name, **kwargs):
    global RESULTS_FIELDNAMES, data_name
    if not data_name: data_name = new_data_name
    new_file = not os.path.exists(data_name + "_results.csv")
    with open(data_name + "_results.csv", "a", newline="") as results_file:
        writer = csv.DictWriter(results_file, RESULTS_FIELDNAMES)
        if new_file:
            writer.writeheader()
        if len(kwargs.keys()) > 0:
            writer.writerow(kwargs)


def increment_coordinates(imu_transform, coordinates, previous_line, data_line, previous_pose, floorplan_scale, re_get):
    # Calculate IMU trajectory by appending IMU displacement onto the last known location (starting from previous pose)
    previous_position, new_position = re_get["values"](previous_line), re_get["values"](data_line)
    displacement = imu_transform @ np.delete(np.subtract(new_position, previous_position), -1) / floorplan_scale
    if len(coordinates) == 0: coordinates.append(previous_pose[:2])
    new_coordinate = [coordinates[len(coordinates) - 1][dim] + displacement[dim] for dim in range(len(displacement))]
    write_to_results(None, imu_timestamp=re_get["timestamp"](data_line), location=f"{new_coordinate} (pixel position); "
                     f"{new_position} (physical position)")
    coordinates.append(new_coordinate)
