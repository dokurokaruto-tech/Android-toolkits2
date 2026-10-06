"""step_04_outfit.py - pink track jacket w/ collar+zipper+piping, pleated
skirt, knee socks, loafers, guitar case on the back."""
import sys
_BP = "/home/user/Android-toolkits2/character3d/build"
if _BP not in sys.path:
    sys.path.insert(0, _BP)
import math
import bpy
from mathutils import Matrix, Vector
import importlib
import hh_lib as H
importlib.reload(H)

M = bpy.data.materials
for ob in list(bpy.data.objects):
    if ob.name.startswith(("HH_Jacket", "HH_Skirt", "HH_Socks", "HH_Shoes",
                           "HH_Prop")):
        bpy.data.objects.remove(ob, do_unlink=True)

# ------------------------------------------------------------------ jacket
jk_rings = [
    (0.940, 0.099, 0.069, 0.079, 0.002),
    (1.000, 0.091, 0.063, 0.071, 0.000),
    (1.080, 0.095, 0.065, 0.073, 0.000),
    (1.160, 0.107, 0.071, 0.079, 0.000),
    (1.230, 0.113, 0.063, 0.071, -0.002),
    (1.268, 0.093, 0.053, 0.059, -0.002),
    (1.292, 0.053, 0.043, 0.047, -0.004),
]
secs = [H.ellipse_ring(0, cy, z, rx, f, b, n=24)
        for (z, rx, f, b, cy) in jk_rings]
jack = H.loft("HH_jk_body", secs, cap_start=False, cap_end=False,
              coll="CH_HH_OUTFIT")
sol = jack.modifiers.new("Sol", 'SOLIDIFY'); sol.thickness = 0.006
H.apply_mods(jack)
parts = [jack]

# collar (high, zipped to the chin)
col_secs = [H.ellipse_ring(0, -0.008, z, r, r * 0.92, r * 0.98, n=20)
            for (z, r) in ((1.288, 0.042), (1.310, 0.040), (1.332, 0.038))]
col = H.loft("HH_jk_collar", col_secs, cap_start=False, cap_end=False,
             coll="CH_HH_OUTFIT")
sol = col.modifiers.new("Sol", 'SOLIDIFY'); sol.thickness = 0.005
H.apply_mods(col)
parts.append(col)

# hem band
hem_secs = [H.ellipse_ring(0, 0.002, z, 0.102, 0.072, 0.082, n=24)
            for z in (0.928, 0.942, 0.956)]
hem = H.loft("HH_jk_hem", hem_secs, cap_start=False, cap_end=False,
             coll="CH_HH_OUTFIT")
sol = hem.modifiers.new("Sol", 'SOLIDIFY'); sol.thickness = 0.005
H.apply_mods(hem)
parts.append(hem)

# sleeves + cuffs (local arm frame, then rotate like the skin arm)
def sleeve(sign):
    pts = [(0.000, -0.002, 0.000), (0.062, -0.003, -0.004), (0.125, -0.005, -0.008),
           (0.168, -0.006, -0.010), (0.215, -0.008, -0.012), (0.262, -0.009, -0.014),
           (0.298, -0.010, -0.016)]
    radii = [0.046, 0.043, 0.039, 0.037, 0.034, 0.031, 0.028]
    sl = H.tube("HH_jk_sleeve", pts, radii, n=14, cap_start=False,
                cap_end=False, coll="CH_HH_OUTFIT")
    so = sl.modifiers.new("Sol", 'SOLIDIFY'); so.thickness = 0.005
    H.apply_mods(sl)
    cuff = H.tube("HH_jk_cuff", [(0.292, -0.010, -0.016), (0.312, -0.011, -0.018)],
                  [0.026, 0.025], n=12, coll="CH_HH_OUTFIT")
    ob = H.join([sl, cuff], "HH_jk_sleeveX")
    Rm = Matrix.Rotation(0.7853981634, 4, 'Y')
    Tr = Matrix.Translation(Vector((0.104 * sign, -0.002, 1.256)))
    Sx = Matrix.Scale(sign, 4, Vector((1, 0, 0)))
    ob.matrix_world = Tr @ Sx @ Rm
    if sign < 0:
        ob.data.flip_normals()
    return ob

parts.append(sleeve(1))
parts.append(sleeve(-1))
for sign in (1, -1):
    cap = H.tube(f"HH_jk_shcap{sign}",
                 [(0.086 * sign, -0.002, 1.262), (0.104 * sign, -0.002, 1.256),
                  (0.120 * sign, -0.002, 1.244)],
                 [0.040, 0.048, 0.044], n=12, scale=(0.9, 0.95),
                 coll="CH_HH_OUTFIT")
    parts.append(cap)
jacket = H.join(parts, "HH_Jacket")
H.set_mat(jacket, M["M_HH_Jacket"])
H.smooth(jacket, levels=1)

# ------------------------------------------------------------------ trim
trim = []
# white piping along the top of each sleeve
for sign in (1, -1):
    pts = [(0.100, -0.0425, -0.0075), (0.125, -0.041, -0.008),
           (0.168, -0.039, -0.010), (0.215, -0.036, -0.012), (0.262, -0.033, -0.014),
           (0.296, -0.030, -0.016)]
    st = H.tube("HH_jk_stripe", pts, 0.0040, n=6, coll="CH_HH_OUTFIT")
    st.matrix_world = (Matrix.Translation(Vector((0.104 * sign, -0.002, 1.256)))
                       @ Matrix.Scale(sign, 4, Vector((1, 0, 0)))
                       @ Matrix.Rotation(0.7853981634, 4, 'Y'))
    if sign < 0:
        st.data.flip_normals()
    trim.append(st)
# zipper line down the front: thin tube on the jacket surface
zpath = []
for z in (0.950, 1.00, 1.06, 1.12, 1.18, 1.24, 1.275, 1.292):
    yy = None
    for i in range(len(jk_rings) - 1):
        z0, rx0, f0, b0, c0 = jk_rings[i]
        z1, rx1, f1, b1, c1 = jk_rings[i + 1]
        if z0 <= z <= z1:
            u = (z - z0) / (z1 - z0)
            yy = (c0 + (c1 - c0) * u) - (f0 + (f1 - f0) * u) - 0.0085
    zpath.append((0.0, yy, z))
zipob = H.tube("HH_jk_zip", zpath, 0.0038, n=6, coll="CH_HH_OUTFIT")
trim.append(zipob)
trimob = H.join(trim, "HH_Jacket_Trim")
H.set_mat(trimob, M["M_HH_White"])
H.smooth(trimob, levels=1)

# ------------------------------------------------------------------ skirt
def pleat_ring(z, rx, ry, amp, pleats=22, n=88):
    pts = []
    for j in range(n):
        t = j / n * 2 * math.pi
        ph = t * pleats
        tri = (2 / math.pi) * math.asin(math.sin(ph))
        m = 1.0 + amp * tri
        pts.append((rx * m * math.cos(t), -ry * m * math.sin(t) + 0.004, z))
    return pts

sk_secs = [
    pleat_ring(0.930, 0.094, 0.070, 0.006),
    pleat_ring(0.880, 0.106, 0.082, 0.014),
    pleat_ring(0.780, 0.118, 0.092, 0.024),
    pleat_ring(0.680, 0.126, 0.100, 0.032),
    pleat_ring(0.590, 0.130, 0.106, 0.046),
    pleat_ring(0.545, 0.134, 0.110, 0.052),
]
skirt = H.loft("HH_Skirt", sk_secs, cap_start=False, cap_end=False,
               coll="CH_HH_OUTFIT", closed=True)
so = skirt.modifiers.new("Sol", 'SOLIDIFY'); so.thickness = 0.005
H.apply_mods(skirt)
H.set_mat(skirt, M["M_HH_Skirt"])
H.smooth(skirt, levels=1)

# ------------------------------------------------------------------ socks
socks = []
for sign in (1, -1):
    x = 0.050 * sign
    pts = [(x, 0.000, 0.440), (x, 0.002, 0.340), (x, 0.006, 0.220),
           (x, 0.009, 0.120), (x, 0.010, 0.070)]
    radii = [0.047, 0.043, 0.036, 0.030, 0.027]
    socks.append(H.tube(f"HH_sock{sign}", pts, radii, n=14,
                        coll="CH_HH_OUTFIT"))
    band = H.tube(f"HH_sockband{sign}", [(x, 0.000, 0.452), (x, 0.000, 0.436)],
                  [0.049, 0.048], n=14, coll="CH_HH_OUTFIT")
    socks.append(band)
sockob = H.join(socks, "HH_Socks")
H.set_mat(sockob, M["M_HH_Sock"])
H.smooth(sockob, levels=1)

# ------------------------------------------------------------------ shoes
shoes = []
for sign in (1, -1):
    x = 0.049 * sign
    sole = H.tube(f"HH_sole{sign}", [(x, 0.040, 0.014), (x, -0.022, 0.012),
                                     (x, -0.085, 0.012), (x, -0.128, 0.017)],
                  [0.032, 0.036, 0.032, 0.022], n=12, scale=(0.66, 1.15),
                  coll="CH_HH_OUTFIT")
    upper = H.tube(f"HH_shoeU{sign}", [(x, 0.036, 0.042), (x, -0.012, 0.055),
                                       (x, -0.070, 0.044), (x, -0.118, 0.028)],
                   [0.030, 0.034, 0.029, 0.017], n=12, scale=(0.95, 1.05),
                   coll="CH_HH_OUTFIT")
    heel = H.cube(f"HH_heel{sign}", (0.026, 0.030, 0.018), (x, 0.028, 0.011),
                  coll="CH_HH_OUTFIT")
    shoes.extend([sole, upper, heel])
shoeob = H.join(shoes, "HH_Shoes")
H.set_mat(shoeob, M["M_HH_Shoe"])
H.smooth(shoeob, levels=1)

# ------------------------------------------------------------------ guitar case
casep = []
bodyc = H.cube("HH_case_body", (0.165, 0.062, 0.47), (0.015, 0.180, 0.99),
               rot=(0.14, 0.06, 0.10), coll="CH_HH_PROPS")
neckc = H.cube("HH_case_neck", (0.050, 0.050, 0.34), (-0.020, 0.165, 1.22),
               rot=(0.14, -0.06, 0.10), coll="CH_HH_PROPS")
for c in (bodyc, neckc):
    bv = c.modifiers.new("Bev", 'BEVEL')
    bv.width = 0.030
    bv.segments = 4
    H.apply_mods(c)
casep.extend([bodyc, neckc])
def jfront_y(z, off=-0.010):
    for i in range(len(jk_rings) - 1):
        z0, rx0, f0, b0, c0 = jk_rings[i]
        z1, rx1, f1, b1, c1 = jk_rings[i + 1]
        if z0 <= z <= z1:
            u = (z - z0) / (z1 - z0)
            return (c0 + (c1 - c0) * u) - (f0 + (f1 - f0) * u) + off
    return -0.07 + off

strap_pts = [(0.058, 0.010, 1.268), (0.052, jfront_y(1.252), 1.252),
             (0.030, jfront_y(1.160), 1.160), (-0.020, jfront_y(1.040), 1.040),
             (-0.062, jfront_y(0.960), 0.960)]
strap = H.tube("HH_case_strap", strap_pts,
               [0.0045, 0.0045, 0.0045, 0.0045, 0.004], n=8, coll="CH_HH_PROPS")
casep.append(strrap := strap)
caseob = H.join(casep, "HH_Prop_GuitarCase")
H.set_mat(caseob, M["M_HH_Case"])
H.smooth(caseob, levels=1)
print("step_04 done")
