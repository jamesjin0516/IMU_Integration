import numpy as np
from scipy.spatial.transform import Rotation as R

# This is a backup file for the 6 degree of freedom logic of pose_multi_refine in server code's implicit_distortion_model.py

"""
qvec is a rotation vector determined according (roughly, for accuracy use -rot_base rotation around vertical axis)
to basis x = -y_f, y = -z_f, z = -x_f (with respect to floorplan basis)
[[0, 0, -1]
 [-1, 0, 0]
 [0, -1, 0]]
We need to transform qvec into the floorplan's basis according to this knowledge
"""
y_flip = np.array([[1, 0, 0],
                   [0, -1, 0],
                   [0, 0, 1]])
y_down = R.from_rotvec([np.pi / 2, 0, 0]).as_matrix() @ y_flip
rotate_to_world = R.from_rotvec([0, 0, rot_base]).as_matrix() @ y_down
world_pose = (rotate_to_world @ qvec.reshape(3,1)).reshape(3)

# Transform coordinates (stored in tvec) to floorplan bases
x_pos, z_pos, y_pos = tvec
world_coor = np.append(T @ np.array([[x_pos], [y_pos], [1]]), [[z_pos]], 0)
return np.append(world_coor.transpose(), world_pose).tolist()