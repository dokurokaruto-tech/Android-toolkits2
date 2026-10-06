"""hh_lib.py - procedural modelling toolkit for the Gotoh Hitori cel-look model.

Everything is metric (1 unit = 1 m).  The character faces -Y, up is +Z,
her right side is -X (viewer left when looking at her front).
"""
import math
from math import cos, sin, pi, radians, sqrt

import bmesh
import bpy
from mathutils import Vector, Matrix

# --------------------------------------------------------------------------
# collections / objects
# --------------------------------------------------------------------------
COLL = {}


def get_coll(name):
    if name in COLL and COLL[name] in bpy.context.scene.collection.children[:]:
        return COLL[name]
    c = bpy.data.collections.get(name)
    if c is None:
        c = bpy.data.collections.new(name)
        bpy.context.scene.collection.children.link(c)
    COLL[name] = c
    return c


def link_obj(obj, coll_name):
    c = get_coll(coll_name)
    c.objects.link(obj)
    return obj


def mesh_obj(name, verts, faces, coll_name="CH_HH_BODY"):
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.validate()
    me.update()
    ob = bpy.data.objects.new(name, me)
    link_obj(ob, coll_name)
    return ob


# --------------------------------------------------------------------------
# ring / loft primitives
# --------------------------------------------------------------------------
def ring_pts(n, fn):
    return [fn(j, j / n * 2 * pi) for j in range(n)]


def ellipse_ring(cx, cy, cz, rx, ry_f, ry_b=None, n=24, squash=1.0):
    """Horizontal ring; ry_f = depth toward -Y (face side), ry_b toward +Y."""
    if ry_b is None:
        ry_b = ry_f

    def fn(_j, t):
        s = sin(t)
        ry = ry_f if s > 0 else ry_b
        return (cx + rx * cos(t), cy - ry * s, cz)
    return ring_pts(n, fn)


def loft(name, sections, cap_start=True, cap_end=True, coll="CH_HH_BODY",
         closed=True):
    """Bridge consecutive rings (all same length) with quads, fan-cap ends."""
    n = len(sections[0])
    verts = [v for ring in sections for v in ring]
    faces = []
    R = len(sections)
    span = n if closed else n - 1
    for i in range(R - 1):
        a, b = i * n, (i + 1) * n
        for j in range(span):
            j2 = (j + 1) % n
            faces.append((a + j, a + j2, b + j2, b + j))
    if cap_start and closed:
        c = len(verts)
        ring0 = sections[0]
        ctr = Vector((0, 0, 0))
        for v in ring0:
            ctr += Vector(v)
        ctr /= len(ring0)
        verts.append(tuple(ctr))
        for j in range(n):
            faces.append((c, (j + 1) % n, j))
    if cap_end and closed:
        c = len(verts)
        ringN = sections[-1]
        ctr = Vector((0, 0, 0))
        for v in ringN:
            ctr += Vector(v)
        ctr /= len(ringN)
        verts.append(tuple(ctr))
        base = (R - 1) * n
        for j in range(n):
            faces.append((c, base + j, base + (j + 1) % n))
    return mesh_obj(name, verts, faces, coll)


def _frames(path):
    """Parallel-transport frames along a polyline."""
    P = [Vector(p) for p in path]
    T = []
    for i in range(len(P)):
        if i == 0:
            t = P[1] - P[0]
        elif i == len(P) - 1:
            t = P[-1] - P[-2]
        else:
            t = P[i + 1] - P[i - 1]
        T.append(t.normalized())
    up = Vector((0, 0, 1))
    if abs(up.dot(T[0])) > 0.95:
        up = Vector((0, -1, 0))
    N = (up - T[0] * up.dot(T[0])).normalized()
    frames = []
    for i in range(len(P)):
        if i > 0:
            # rotate previous normal into the plane perpendicular to T[i]
            N = (N - T[i] * N.dot(T[i]))
            if N.length < 1e-6:
                N = (up - T[i] * up.dot(T[i]))
            N.normalize()
        B = T[i].cross(N).normalized()
        frames.append((N.copy(), B.copy()))
    return frames


def tube(name, path, radii, n=12, cap_start=True, cap_end=True, coll="CH_HH_BODY",
         scale=None):
    """Sweep circular (or elliptical via scale=(sx,sz)) rings along path."""
    P = [Vector(p) for p in path]
    if isinstance(radii, (int, float)):
        radii = [radii] * len(P)
    elif len(radii) != len(P):
        m = len(radii)
        radii = [radii[min(m - 1, int(i * (m - 1) / max(1, len(P) - 1) + 0.5))]
                 for i in range(len(P))]
    frames = _frames(P)
    sections = []
    for i, p in enumerate(P):
        N, B = frames[i]
        r = radii[i]
        sx, sy = (scale if scale else (1.0, 1.0))
        ring = []
        for j in range(n):
            t = j / n * 2 * pi
            ring.append(tuple(p + N * (cos(t) * r * sx) + B * (sin(t) * r * sy)))
        sections.append(ring)
    return loft(name, sections, cap_start, cap_end, coll)


def bez(p0, p1, p2, p3, steps):
    p0, p1, p2, p3 = (Vector(p) for p in (p0, p1, p2, p3))
    out = []
    for i in range(steps):
        u = i / (steps - 1)
        out.append(p0 * (1 - u) ** 3 + p1 * (3 * u * (1 - u) ** 2)
                   + p2 * (3 * u ** 2 * (1 - u)) + p3 * u ** 3)
    return out


# --------------------------------------------------------------------------
# mesh helpers
# --------------------------------------------------------------------------
def join(objs, name):
    """Operator-free join (works in background / bpy-module context)."""
    bpy.context.view_layer.update()
    verts, faces = [], []
    for o in objs:
        me = o.data.copy()
        me.transform(o.matrix_basis)
        base = len(verts)
        verts.extend([tuple(v.co) for v in me.vertices])
        for p in me.polygons:
            faces.append(tuple(base + i for i in p.vertices))
        bpy.data.meshes.remove(me)
    coll = objs[0].users_collection[0].name if objs[0].users_collection else "CH_HH_BODY"
    for o in objs:
        bpy.data.objects.remove(o, do_unlink=True)
    return mesh_obj(name, verts, faces, coll)


def apply_mods(ob):
    """Bake modifiers into the mesh (background-safe)."""
    dg = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(ob.evaluated_get(dg))
    old = ob.data
    ob.modifiers.clear()
    ob.data = me
    me.name = ob.name
    if old.users == 0:
        bpy.data.meshes.remove(old)
    return ob


def smooth(ob, levels=2, render=None):
    for p in ob.data.polygons:
        p.use_smooth = True
    if levels:
        m = ob.modifiers.new("Subsurf", 'SUBSURF')
        m.levels = levels
        m.render_levels = render if render is not None else levels
    return ob


def set_mat(ob, mat):
    ob.data.materials.clear()
    ob.data.materials.append(mat)
    return ob


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


def head_params(z):
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
    rx, f, _b, c = head_params(z)
    q = max(1.0 - (x / rx) ** 2, 0.02)
    return c - f * math.sqrt(q) + off


def mirror_x(verts):
    return [(-x, y, z) for (x, y, z) in verts]


def transform(ob, loc=(0, 0, 0), rot=None, scale=(1, 1, 1)):
    if rot:
        M = Matrix.Euler(rot).to_matrix().to_4x4()
    else:
        M = Matrix.Identity(4)
    S = Matrix.Diagonal(Vector(scale) * 1.0).to_4x4()
    T = Matrix.Translation(Vector(loc))
    ob.matrix_world = T @ M @ S
    return ob


def cube(name, size, loc, rot=(0, 0, 0), coll="CH_HH_OUTFIT"):
    me = bpy.data.meshes.new(name)
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    bm.to_mesh(me)
    bm.free()
    ob = bpy.data.objects.new(name, me)
    link_obj(ob, coll)
    ob.scale = size
    ob.location = loc
    ob.rotation_euler = rot
    return ob
