"""render_views.py - final presentation renders of the Hitori model."""
import bpy
import time

sc = bpy.context.scene
sc.render.engine = 'CYCLES'
sc.cycles.device = 'CPU'
sc.cycles.samples = 8
sc.view_settings.view_transform = 'Standard'

OUT = "/home/user/Android-toolkits2/character3d/renders/"
views = [
    ("CAM_front", 1000, 1400, "final_front.png"),
    ("CAM_34", 1000, 1400, "final_34.png"),
    ("CAM_side", 1000, 1400, "final_side.png"),
    ("CAM_back", 1000, 1400, "final_back.png"),
    ("CAM_face", 1000, 1000, "final_face.png"),
]
for cam, rx, ry, fn in views:
    sc.camera = bpy.data.objects[cam]
    sc.render.resolution_x = rx
    sc.render.resolution_y = ry
    sc.render.filepath = OUT + fn
    t = time.time()
    bpy.ops.render.render(write_still=True)
    print("rendered", fn, round(time.time() - t, 1), "s")
print("all renders done")
