"""step_01_body.py - skin mesh: head, neck, torso, arms+hands, legs, feet."""
import sys
_BP = "/home/user/Android-toolkits2/character3d/build"
if _BP not in sys.path:
    sys.path.insert(0, _BP)
import bpy
from mathutils import Vector, Matrix
import importlib
import hh_lib as H
importlib.reload(H)

M = bpy.data.materials
for nm in ("HH_Body",):
    if nm in bpy.data.objects:
        bpy.data.objects.remove(bpy.data.objects[nm], do_unlink=True)

# ------------------------------------------------------------------ head
head_rings = [
    # z,     rx,    ry_front, ry_back, cy
    (1.337, 0.017, 0.022, 0.024, -0.030),   # chin tip
    (1.350, 0.041, 0.042, 0.048, -0.022),
    (1.370, 0.061, 0.064, 0.072, -0.014),
    (1.395, 0.073, 0.077, 0.086, -0.008),
    (1.420, 0.079, 0.081, 0.092, -0.004),   # eye line, widest
    (1.450, 0.082, 0.080, 0.095, 0.000),
    (1.490, 0.080, 0.076, 0.093, 0.004),
    (1.525, 0.070, 0.065, 0.082, 0.008),
    (1.550, 0.048, 0.045, 0.056, 0.010),
    (1.560, 0.020, 0.020, 0.024, 0.012),
]
secs = [H.ellipse_ring(0, cy, z, rx, ryf, ryb, n=24)
        for (z, rx, ryf, ryb, cy) in head_rings]
head = H.loft("HH_head", secs, cap_start=True, cap_end=True)

# ------------------------------------------------------------------ neck
neck = H.tube("HH_neck", [(0, -0.004, 1.288), (0, -0.007, 1.325), (0, -0.010, 1.365)],
              [0.036, 0.033, 0.033], n=16)

# ------------------------------------------------------------------ torso
torso_rings = [
    (1.300, 0.042, 0.037, 0.037, -0.006),
    (1.288, 0.066, 0.042, 0.046, -0.004),
    (1.272, 0.086, 0.048, 0.052, -0.002),
    (1.240, 0.104, 0.055, 0.062, 0.000),
    (1.180, 0.099, 0.062, 0.070, 0.000),
    (1.100, 0.087, 0.056, 0.062, 0.000),
    (1.010, 0.076, 0.050, 0.056, 0.000),
    (0.930, 0.088, 0.058, 0.068, 0.002),
    (0.870, 0.096, 0.062, 0.075, 0.004),
    (0.815, 0.088, 0.058, 0.070, 0.004),
]
secs = [H.ellipse_ring(0, cy, z, rx, ryf, ryb, n=24)
        for (z, rx, ryf, ryb, cy) in torso_rings]
torso = H.loft("HH_torso", secs)

# ------------------------------------------------------------------ arms
def build_arm(sign):
    # local frame: +X = down the arm, rotated 45 deg about Y afterwards
    pts = [(0.000, -0.002, 0.000), (0.062, -0.003, -0.004), (0.125, -0.005, -0.008),
           (0.168, -0.006, -0.010), (0.215, -0.008, -0.012), (0.262, -0.009, -0.014),
           (0.300, -0.010, -0.016)]
    radii = [0.037, 0.034, 0.030, 0.028, 0.025, 0.022, 0.019]
    arm = H.tube("HH_arm", pts, radii, n=14)
    parts = [arm]
    # palm
    palm = H.tube("HH_palm", [(0.300, -0.010, -0.016), (0.326, -0.011, -0.019),
                              (0.350, -0.012, -0.021)],
                  [0.026, 0.027, 0.026], n=12, scale=(0.55, 1.0))
    parts.append(palm)
    # four fingers
    for k, fy in enumerate((-0.0235, -0.008, 0.0075, 0.023)):
        ln = (0.050, 0.056, 0.053, 0.042)[k]
        p0 = Vector((0.350, -0.012 + fy * 1.25, -0.021))
        d = Vector((1, 0, -0.22)).normalized()
        path = [p0, p0 + d * ln * 0.45 + Vector((0, 0, -0.004)),
                p0 + d * ln * 0.8 + Vector((0, 0, -0.010)),
                p0 + d * ln + Vector((0, 0, -0.016))]
        parts.append(H.tube(f"HH_fing{k}", [tuple(p) for p in path],
                            [0.0070, 0.0062, 0.0052, 0.0038], n=8))
    # thumb
    t0 = Vector((0.315, -0.028, -0.017))
    td = Vector((0.55, -0.75, -0.30)).normalized()
    parts.append(H.tube("HH_thumb", [tuple(t0), tuple(t0 + td * 0.030),
                                     tuple(t0 + td * 0.055)],
                        [0.0095, 0.0078, 0.0058], n=8))
    ob = H.join(parts, "HH_armR")
    # rotate -45 deg about Y so local +X points down-out, then move to shoulder
    a = 0.7853981634
    Rm = Matrix.Rotation(a, 4, 'Y')
    Tr = Matrix.Translation(Vector((0.104 * sign, -0.002, 1.256)))
    Sx = Matrix.Scale(sign, 4, Vector((1, 0, 0)))
    ob.matrix_world = Tr @ Sx @ Rm
    if sign < 0:
        # fix winding after mirroring
        me = ob.data
        me.flip_normals()
    return ob

armR = build_arm(1)
armL = build_arm(-1)

# ------------------------------------------------------------------ legs
def build_leg(sign):
    pts = [(0.052, 0.004, 0.860), (0.055, 0.000, 0.700), (0.052, -0.005, 0.560),
           (0.050, -0.006, 0.460), (0.050, 0.002, 0.300), (0.049, 0.008, 0.160),
           (0.048, 0.010, 0.075)]
    radii = [0.058, 0.052, 0.046, 0.042, 0.035, 0.028, 0.022]
    leg = H.tube("HH_leg", pts, radii, n=16)
    foot = H.tube("HH_foot", [(0.048, 0.022, 0.045), (0.048, -0.020, 0.030),
                              (0.048, -0.070, 0.022), (0.048, -0.105, 0.018)],
                  [0.026, 0.026, 0.022, 0.014], n=12, scale=(0.80, 1.15))
    ob = H.join([leg, foot], "HH_legR")
    if sign < 0:
        ob.data.transform(Matrix.Scale(-1, 4, Vector((1, 0, 0))))
        ob.data.flip_normals()
    return ob

legR = build_leg(1)
legL = build_leg(-1)

# ------------------------------------------------------------------ nose
nose = H.tube("HH_nose", [(0, -0.080, 1.404), (0, -0.0875, 1.400)],
              [0.007, 0.0035], n=8, scale=(1.0, 0.8))

body = H.join([head, neck, torso, armR, armL, legR, legL, nose], "HH_Body")
H.set_mat(body, M["M_HH_Skin"])
H.smooth(body, levels=2)
print("step_01 done:", body.name, len(body.data.polygons), "faces")
