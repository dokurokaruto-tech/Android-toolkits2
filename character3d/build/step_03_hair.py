"""step_03_hair.py - Hitori's hair: scalp shell, zigzag bangs, side locks,
long back mass with pointed lobes, ahoge, and the blue/yellow cube clips."""
import sys
_BP = "/home/user/Android-toolkits2/character3d/build"
if _BP not in sys.path:
    sys.path.insert(0, _BP)
import math
import bpy
import importlib
import hh_lib as H
importlib.reload(H)

M = bpy.data.materials
for ob in list(bpy.data.objects):
    if ob.name.startswith(("HH_Hair", "HH_Clip")):
        bpy.data.objects.remove(ob, do_unlink=True)

# ------------------------------------------------------------------ scalp
secs = []
for z in (1.430, 1.455, 1.480, 1.510, 1.535, 1.555, 1.568):
    rx, f, b, c = H.head_params(z)
    secs.append(H.ellipse_ring(0, c + 0.004, z, rx + 0.011, f + 0.010,
                               b + 0.011, n=24))
scalp = H.loft("HH_Hair_Scalp", secs, cap_start=True, cap_end=True,
               coll="CH_HH_HAIR")

# ------------------------------------------------------------------ bangs
# 9 strands in two depth layers, roots hidden under the scalp shell
bang_x = [-0.062, -0.0465, -0.031, -0.0155, 0.0, 0.0155, 0.031, 0.0465, 0.062]
bang_tip = [1.448, 1.424, 1.440, 1.414, 1.432, 1.412, 1.438, 1.420, 1.446]
strands = []
for i, x in enumerate(bang_x):
    zt = bang_tip[i]
    dep = -0.015 if i % 2 == 0 else -0.012
    x2 = x * 1.05
    p0 = (x * 0.80, H.face_y(x * 0.80, 1.545, -0.006), 1.545)
    p1 = (x * 0.95, H.face_y(x, 1.492, dep), 1.492)
    p2 = (x2, H.face_y(x2, 1.452, dep + 0.002), 1.452)
    p3 = (x2 * 0.97, H.face_y(x2 * 0.97, zt + 0.010, dep + 0.006), zt + 0.010)
    p4 = (x2 * 0.93, H.face_y(x2 * 0.93, zt, dep + 0.010), zt)
    path = H.bez(p0, p1, p2, p3, 5) + [p4]
    radii = [0.011, 0.0125, 0.012, 0.008, 0.004, 0.001]
    strands.append(H.tube(f"HH_bang{i}", path, radii, n=8, scale=(0.40, 1.15),
                          coll="CH_HH_HAIR"))

# ------------------------------------------------------------------ side locks
for sgn, tag in ((1, "R"), (-1, "L")):
    p0 = (0.066 * sgn, -0.038, 1.500)
    p1 = (0.080 * sgn, -0.046, 1.400)
    p2 = (0.085 * sgn, -0.046, 1.260)
    p3 = (0.082 * sgn, -0.041, 1.150)
    p4 = (0.078 * sgn, -0.037, 1.105)
    path = H.bez(p0, p1, p2, p3, 7) + [p4]
    radii = [0.012, 0.014, 0.014, 0.013, 0.010, 0.006, 0.003, 0.001]
    strands.append(H.tube(f"HH_sidelock_{tag}", path, radii, n=10,
                          scale=(0.75, 1.05), coll="CH_HH_HAIR"))

# ------------------------------------------------------------------ back mass
# open arc (face window left open), lobes grow toward the hem
def back_ring(z, r, cy, amp, n=33, phi_max=118.0):
    pts = []
    for j in range(n):
        phi = (-1 + 2 * j / (n - 1)) * math.radians(phi_max)
        mod = 1.0 + amp * math.cos(phi * 7.0)
        x = r * mod * math.sin(phi)
        y = cy + r * mod * 0.95 * math.cos(phi)
        pts.append((x, y, z))
    return pts

back_secs = [
    back_ring(1.515, 0.074, 0.018, 0.00),
    back_ring(1.470, 0.090, 0.024, 0.01),
    back_ring(1.430, 0.098, 0.028, 0.02),
    back_ring(1.330, 0.104, 0.040, 0.03),
    back_ring(1.200, 0.108, 0.052, 0.045),
    back_ring(1.060, 0.112, 0.060, 0.06),
    back_ring(0.940, 0.108, 0.066, 0.075),
    back_ring(0.850, 0.096, 0.068, 0.09),
    back_ring(0.780, 0.078, 0.068, 0.10),
    back_ring(0.735, 0.050, 0.066, 0.10),
]
back = H.loft("HH_Hair_Back", back_secs, cap_start=False, cap_end=False,
              coll="CH_HH_HAIR", closed=False)
sol = back.modifiers.new("Sol", 'SOLIDIFY')
sol.thickness = 0.008
sol.offset = 1.0
H.apply_mods(back)

# ------------------------------------------------------------------ ahoge
ah = H.tube("HH_Hair_Ahoge",
            [(0.004, 0.004, 1.558), (0.010, -0.010, 1.582),
             (0.024, -0.028, 1.588), (0.036, -0.040, 1.572)],
            [0.0022, 0.0018, 0.0012, 0.0005], n=6, coll="CH_HH_HAIR")
stray = H.tube("HH_Hair_Stray",
               [(-0.070, -0.036, 1.470), (-0.086, -0.052, 1.430),
                (-0.092, -0.058, 1.385)],
               [0.0022, 0.0015, 0.0005], n=6, coll="CH_HH_HAIR")

hair = H.join([scalp] + strands + [back, ah, stray], "HH_Hair")
H.set_mat(hair, M["M_HH_Hair"])
H.smooth(hair, levels=1)

# ------------------------------------------------------------------ cube clips
cb = H.cube("HH_Clip_Blue", (0.015, 0.013, 0.012), (-0.066, -0.056, 1.472),
            rot=(0.50, 0.30, 0.60), coll="CH_HH_HAIR")
cy_ = H.cube("HH_Clip_Yellow", (0.013, 0.011, 0.010), (-0.076, -0.050, 1.449),
             rot=(0.40, -0.25, 0.40), coll="CH_HH_HAIR")
H.set_mat(cb, M["M_HH_CubeBlue"])
H.set_mat(cy_, M["M_HH_CubeYellow"])
print("step_03 done:", len(hair.data.polygons), "hair faces")
