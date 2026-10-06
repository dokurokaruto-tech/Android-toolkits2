"""step_02_face.py - anime face: eyeballs, iris, pupil, highlight, lashes,
brows, mouth.  Parts are grouped per material so the cel materials survive."""
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
    if ob.name.startswith(("HH_Eye", "HH_Lash", "HH_Brow", "HH_Mouth",
                           "HH_eyeball", "HH_iris", "HH_pupil", "HH_hl",
                           "HH_lash", "HH_brow", "HH_mouth", "HH_Face")):
        bpy.data.objects.remove(ob, do_unlink=True)

HEAD_RINGS = [
    (1.337, 0.017, 0.022, 0.024, -0.030),
    (1.350, 0.041, 0.042, 0.048, -0.022),
    (1.370, 0.061, 0.064, 0.072, -0.014),
    (1.395, 0.073, 0.077, 0.086, -0.008),
    (1.420, 0.079, 0.081, 0.092, -0.004),
    (1.450, 0.082, 0.080, 0.095, 0.000),
    (1.490, 0.080, 0.076, 0.093, 0.004),
    (1.525, 0.070, 0.065, 0.082, 0.008),
    (1.550, 0.048, 0.045, 0.056, 0.010),
    (1.560, 0.020, 0.020, 0.024, 0.012),
]


def _params(z):
    z = min(max(z, HEAD_RINGS[0][0]), HEAD_RINGS[-1][0])
    for i in range(len(HEAD_RINGS) - 1):
        z0, rx0, f0, b0, c0 = HEAD_RINGS[i]
        z1, rx1, f1, b1, c1 = HEAD_RINGS[i + 1]
        if z0 <= z <= z1:
            u = (z - z0) / (z1 - z0)
            return (rx0 + (rx1 - rx0) * u, f0 + (f1 - f0) * u,
                    b0 + (b1 - b0) * u, c0 + (c1 - c0) * u)
    return HEAD_RINGS[-1][1:]


def face_y(x, z, off=0.0):
    rx, f, _b, c = _params(z)
    q = max(1.0 - (x / rx) ** 2, 0.02)
    return c - f * math.sqrt(q) + off


def uv_sphere(name, r, loc, scale=(1, 1, 1), seg=16, rings=12):
    verts, faces = [], []
    for i in range(rings + 1):
        ph = i / rings * math.pi
        for j in range(seg):
            th = j / seg * 2 * math.pi
            verts.append((r * math.sin(ph) * math.cos(th),
                          r * math.cos(ph),
                          r * math.sin(ph) * math.sin(th)))
    for i in range(rings):
        for j in range(seg):
            a = i * seg + j
            b = i * seg + (j + 1) % seg
            faces.append((a, b, b + seg, a + seg))
    ob = H.mesh_obj(name, verts, faces, "CH_HH_FACE")
    ob.data.transform(H.Matrix.Diagonal(H.Vector(scale) * 1.0).to_4x4())
    ob.data.transform(H.Matrix.Translation(H.Vector(loc)))
    return ob


def ribbon(name, pts_top, pts_bot, thick):
    verts = [tuple(p) for p in pts_top] + [tuple(p) for p in pts_bot]
    n = len(pts_top)
    faces = [(j, j + 1, n + j + 1, n + j) for j in range(n - 1)]
    ob = H.mesh_obj(name, verts, faces, "CH_HH_FACE")
    sol = ob.modifiers.new("Sol", 'SOLIDIFY')
    sol.thickness = thick
    sol.offset = 0.0
    H.apply_mods(ob)
    return ob


G = {}


def add(mat, ob):
    G.setdefault(mat, []).append(ob)


for sgn, tag in ((1, "R"), (-1, "L")):
    ex = 0.0345 * sgn
    add("sclera", uv_sphere(f"HH_eyeball_{tag}", 0.0205, (ex, -0.0655, 1.418),
                            scale=(1.0, 0.55, 1.28)))
    add("iris", uv_sphere(f"HH_iris_{tag}", 0.0155, (ex, -0.0748, 1.4175),
                          scale=(1.0, 0.42, 1.45)))
    add("pupil", uv_sphere(f"HH_pupil_{tag}", 0.0052, (ex, -0.0798, 1.4165),
                           scale=(1.0, 0.35, 1.45)))
    add("hl", uv_sphere(f"HH_hl_{tag}", 0.0046, (ex - 0.0055, -0.0820, 1.4255),
                        scale=(1, 0.4, 1)))
    # upper lash with outer flick
    xs = [0.0155, 0.024, 0.0345, 0.045, 0.053, 0.0585]
    zc = [1.4245, 1.4315, 1.4345, 1.4315, 1.4255, 1.4235]
    wu = [0.0028, 0.0040, 0.0044, 0.0042, 0.0044, 0.0075]
    wd = [0.0022, 0.0030, 0.0032, 0.0030, 0.0028, 0.0030]
    top, bot = [], []
    for k, xx in enumerate(xs):
        x = xx * sgn
        y = face_y(x, zc[k], -0.004)
        zf = zc[k] + (0.006 if k == len(xs) - 1 else 0.0)
        top.append((x, y, zf + wu[k]))
        bot.append((x, y, zc[k] - wd[k]))
    add("lash", ribbon(f"HH_lashU_{tag}", top, bot, 0.005))
    # lower lash line
    xs2 = [0.019, 0.030, 0.041, 0.050]
    zc2 = [1.4035, 1.4005, 1.4010, 1.4045]
    add("lash", ribbon(f"HH_lashL_{tag}",
                       [(x * sgn, face_y(x * sgn, z + 0.0011, -0.003), z + 0.0011)
                        for x, z in zip(xs2, zc2)],
                       [(x * sgn, face_y(x * sgn, z - 0.0011, -0.003), z - 0.0011)
                        for x, z in zip(xs2, zc2)], 0.0018))
    # brow
    xs3 = [0.014, 0.026, 0.038, 0.050, 0.058]
    zc3 = [1.4585, 1.4625, 1.4625, 1.4590, 1.4535]
    add("brow", ribbon(f"HH_brow_{tag}",
                       [(x * sgn, face_y(x * sgn, z + 0.0022, -0.002), z + 0.0022)
                        for x, z in zip(xs3, zc3)],
                       [(x * sgn, face_y(x * sgn, z - 0.0022, -0.002), z - 0.0022)
                        for x, z in zip(xs3, zc3)], 0.003))

# mouth: small neutral line, corners slightly down
xm = [0.000, 0.0045, 0.0085]
zm = [1.3715, 1.3708, 1.3722]
topm = [(x, face_y(x, z + 0.0011, -0.0015), z + 0.0011) for x, z in zip(xm, zm)]
botm = [(x, face_y(x, z - 0.0011, -0.0015), z - 0.0011) for x, z in zip(xm, zm)]
add("mouth", ribbon("HH_mouthR", topm, botm, 0.002))
add("mouth", ribbon("HH_mouthL", [(-x, y, z) for (x, y, z) in topm],
                    [(-x, y, z) for (x, y, z) in botm], 0.002))

NAMES = {"sclera": "HH_Eye_Sclera", "iris": "HH_Eye_Iris",
         "pupil": "HH_Eye_Pupil", "hl": "HH_Eye_Highlight",
         "lash": "HH_Lashes", "brow": "HH_Brows", "mouth": "HH_Mouth"}
for key, objs in G.items():
    ob = H.join(objs, NAMES[key])
    H.set_mat(ob, M[{"sclera": "M_HH_Sclera", "iris": "M_HH_Iris",
                     "pupil": "M_HH_Pupil", "hl": "M_HH_Highlight",
                     "lash": "M_HH_Lash", "brow": "M_HH_Brow",
                     "mouth": "M_HH_Mouth"}[key]])
    H.smooth(ob, levels=0)
print("step_02 done:", [n for n in NAMES.values()])
