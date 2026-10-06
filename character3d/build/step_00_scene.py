"""step_00_scene.py - scene, collections, cel-look material kit, camera rig.

Cel-look strategy (Japanese animation industry style NPR):
  * surfaces are shaded with a flat *emission* cel shader: crisp 2-tone bands
    driven by dot(normal, key_dir) + a rim band -> zero noise, zero light
    transport, exactly like painted anime cels.
  * the face uses a *projected normal* (sphere around the head centre) so the
    shadow terminator is one clean curved line, the classic anime face shadow.
  * hair gets an "angel ring" highlight band (object-Z gradient).
  * outlines are drawn by Freestyle (silhouette + border + crease).
"""
import bpy

# ---------------------------------------------------------------- scene base
sc = bpy.context.scene
for ob in list(bpy.data.objects):
    bpy.data.objects.remove(ob, do_unlink=True)
for m in list(bpy.data.materials):
    if m.name.startswith("M_HH_"):
        bpy.data.materials.remove(m)
for me in list(bpy.data.meshes):
    if me.users == 0:
        bpy.data.meshes.remove(me)
for blk in (bpy.data.meshes, bpy.data.materials, bpy.data.curves,
            bpy.data.collections, bpy.data.images, bpy.data.worlds):
    for d in list(blk):
        if d.users == 0:
            blk.remove(d)

sc.unit_settings.system = 'METRIC'
sc.unit_settings.scale_length = 1.0

for cname in ("CH_HH_BODY", "CH_HH_FACE", "CH_HH_HAIR", "CH_HH_OUTFIT",
              "CH_HH_PROPS", "CH_HH_SCENE"):
    c = bpy.data.collections.get(cname)
    if c is None:
        c = bpy.data.collections.new(cname)
        sc.collection.children.link(c)

# world: flat studio backdrop grey (emission shader ignores lighting anyway)
w = bpy.data.worlds.new("HH_World")
sc.world = w
w.use_nodes = True
bg = w.node_tree.nodes["Background"]
bg.inputs[0].default_value = (0.90, 0.90, 0.92, 1.0)
bg.inputs[1].default_value = 1.0

# ---------------------------------------------------------------- cel shader
def new_mat(name):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    nt.nodes.clear()
    return m, nt


def cel_material(name, base, shadow, thr=0.28, rim=(0, 0, 0, 1), rim_thr=0.62,
                 rim_w=0.10, proj_center=None, angel=None, band=None):
    """Flat 2-tone cel emission shader.

    base/shadow : rgba colours of the lit / shadow cel tone
    thr         : dot(N,key) cut for the shadow terminator (in -1..1)
    rim         : rim-light colour + threshold/width (facing based)
    proj_center : (x,y,z) sphere centre for projected normals (face trick)
    angel       : (z0, z1, colour) horizontal angel-ring band on hair
    band        : list of (z0, z1, y_max, colour, strength) fake occlusion
                  bands in object space (bang shadow on forehead etc.)
    """
    m, nt = new_mat(name)
    n, L = nt.nodes, nt.links
    out = n.new("ShaderNodeOutputMaterial"); out.location = (900, 0)
    emis = n.new("ShaderNodeEmission"); emis.location = (700, 0)
    geo = n.new("ShaderNodeNewGeometry"); geo.location = (-900, 0)
    # ---- normal (optionally projected) -----------------------------------
    if proj_center:
        sub = n.new("ShaderNodeVectorMath"); sub.operation = 'SUBTRACT'
        sub.inputs[1].default_value = proj_center
        sub.location = (-700, -200)
        L.new(geo.outputs["Position"], sub.inputs[0])
        nrm = n.new("ShaderNodeVectorMath"); nrm.operation = 'NORMALIZE'
        nrm.location = (-540, -200)
        L.new(sub.outputs[0], nrm.inputs[0])
        nout = nrm.outputs[0]
    else:
        nout = geo.outputs["Normal"]

    # ---- key light band ---------------------------------------------------
    key = n.new("ShaderNodeVectorMath"); key.operation = 'DOT_PRODUCT'
    key.inputs[1].default_value = (0.42, -0.62, 0.66)   # key dir (front-top-left)
    key.location = (-540, 100)
    L.new(nout, key.inputs[0])
    ramp = n.new("ShaderNodeMath"); ramp.operation = 'GREATER_THAN'
    ramp.inputs[1].default_value = thr
    ramp.location = (-360, 100)
    L.new(key.outputs["Value"], ramp.inputs[0])
    mix1 = n.new("ShaderNodeMix"); mix1.data_type = 'RGBA'
    mix1.location = (-160, 100)
    mix1.inputs["A"].default_value = shadow
    mix1.inputs["B"].default_value = base
    L.new(ramp.outputs[0], mix1.inputs["Factor"])
    col_out = mix1.outputs["Result"]

    # ---- angel ring (hair highlight band) ---------------------------------
    if angel:
        z0, z1, hcol = angel
        tc = n.new("ShaderNodeTexCoord"); tc.location = (-900, 400)
        sep = n.new("ShaderNodeSeparateXYZ"); sep.location = (-720, 400)
        L.new(tc.outputs["Object"], sep.inputs[0])
        mr = n.new("ShaderNodeMapRange"); mr.location = (-540, 400)
        mr.interpolation_type = 'SMOOTHSTEP'
        mr.inputs["From Min"].default_value = z0
        mr.inputs["From Max"].default_value = z0 + (z1 - z0) * 0.35
        mr.inputs["To Min"].default_value = 0.0
        mr.inputs["To Max"].default_value = 1.0
        L.new(sep.outputs["Z"], mr.inputs["Value"])
        mr2 = n.new("ShaderNodeMapRange"); mr2.location = (-540, 620)
        mr2.interpolation_type = 'SMOOTHSTEP'
        mr2.inputs["From Min"].default_value = z1
        mr2.inputs["From Max"].default_value = z1 - (z1 - z0) * 0.35
        mr2.inputs["To Min"].default_value = 0.0
        mr2.inputs["To Max"].default_value = 1.0
        L.new(sep.outputs["Z"], mr2.inputs["Value"])
        mul = n.new("ShaderNodeMath"); mul.operation = 'MULTIPLY'
        mul.location = (-360, 500)
        L.new(mr.outputs[0], mul.inputs[0])
        L.new(mr2.outputs[0], mul.inputs[1])
        # only on surfaces facing up/out a bit (soft, to avoid speckle)
        updot = n.new("ShaderNodeVectorMath"); updot.operation = 'DOT_PRODUCT'
        updot.inputs[1].default_value = (0, -0.35, 0.94)
        updot.location = (-360, 700)
        L.new(nout, updot.inputs[0])
        upgt = n.new("ShaderNodeMapRange")
        upgt.interpolation_type = 'SMOOTHSTEP'
        upgt.inputs["From Min"].default_value = 0.10
        upgt.inputs["From Max"].default_value = 0.45
        upgt.location = (-200, 700)
        L.new(updot.outputs["Value"], upgt.inputs["Value"])
        mul2 = n.new("ShaderNodeMath"); mul2.operation = 'MULTIPLY'
        mul2.location = (-40, 560)
        L.new(mul.outputs[0], mul2.inputs[0])
        L.new(upgt.outputs[0], mul2.inputs[1])
        mixA = n.new("ShaderNodeMix"); mixA.data_type = 'RGBA'
        mixA.location = (60, 200)
        mixA.inputs["B"].default_value = hcol
        L.new(col_out, mixA.inputs["A"])
        L.new(mul2.outputs[0], mixA.inputs["Factor"])
        col_out = mixA.outputs["Result"]

    # ---- fake occlusion bands (bang shadow / chin shadow on skin) ---------
    if band:
        tc2 = n.new("ShaderNodeTexCoord"); tc2.location = (-900, -500)
        sep2 = n.new("ShaderNodeSeparateXYZ"); sep2.location = (-720, -500)
        L.new(tc2.outputs["Object"], sep2.inputs[0])
        y = -420
        for (z0, z1, ymax, bcol, stren) in band:
            gz0 = n.new("ShaderNodeMath"); gz0.operation = 'GREATER_THAN'
            gz0.inputs[1].default_value = z0; gz0.location = (-560, y)
            L.new(sep2.outputs["Z"], gz0.inputs[0])
            lz1 = n.new("ShaderNodeMath"); lz1.operation = 'LESS_THAN'
            lz1.inputs[1].default_value = z1; lz1.location = (-560, y - 140)
            L.new(sep2.outputs["Z"], lz1.inputs[0])
            ly = n.new("ShaderNodeMath"); ly.operation = 'LESS_THAN'
            ly.inputs[1].default_value = ymax; ly.location = (-560, y - 280)
            L.new(sep2.outputs["Y"], ly.inputs[0])
            m1 = n.new("ShaderNodeMath"); m1.operation = 'MULTIPLY'; m1.location = (-400, y)
            L.new(gz0.outputs[0], m1.inputs[0]); L.new(lz1.outputs[0], m1.inputs[1])
            m2 = n.new("ShaderNodeMath"); m2.operation = 'MULTIPLY'; m2.location = (-260, y)
            L.new(m1.outputs[0], m2.inputs[0]); L.new(ly.outputs[0], m2.inputs[1])
            m3 = n.new("ShaderNodeMath"); m3.operation = 'MULTIPLY'; m3.location = (-120, y)
            m3.inputs[1].default_value = stren
            L.new(m2.outputs[0], m3.inputs[0])
            mx = n.new("ShaderNodeMix"); mx.data_type = 'RGBA'; mx.location = (20, y)
            mx.inputs["B"].default_value = bcol
            L.new(col_out, mx.inputs["A"])
            L.new(m3.outputs[0], mx.inputs["Factor"])
            col_out = mx.outputs["Result"]
            y -= 460

    # ---- rim light ---------------------------------------------------------
    lw = n.new("ShaderNodeLayerWeight"); lw.location = (-160, -350)
    lw.inputs["Blend"].default_value = 0.32
    rgt = n.new("ShaderNodeMath"); rgt.operation = 'GREATER_THAN'
    rgt.inputs[1].default_value = rim_thr
    rgt.location = (20, -350)
    L.new(lw.outputs["Facing"], rgt.inputs[0])
    # rim only on the lit-ish side OR always? keep always but thin
    mixr = n.new("ShaderNodeMix"); mixr.data_type = 'RGBA'; mixr.location = (300, 0)
    mixr.inputs["B"].default_value = rim
    L.new(col_out, mixr.inputs["A"])
    L.new(rgt.outputs[0], mixr.inputs["Factor"])
    col_out = mixr.outputs["Result"]

    L.new(col_out, emis.inputs["Color"])
    emis.inputs["Strength"].default_value = 1.0
    L.new(emis.outputs[0], out.inputs["Surface"])
    return m


# ---------------------------------------------------------------- palette
# sampled from official art / figures of Gotoh Hitori
SKIN      = (0.985, 0.855, 0.760, 1)
SKIN_SH   = (0.930, 0.700, 0.620, 1)
HAIR      = (0.940, 0.560, 0.660, 1)
HAIR_SH   = (0.800, 0.360, 0.480, 1)
HAIR_HI   = (1.000, 0.800, 0.850, 1)
JACKET    = (0.955, 0.600, 0.585, 1)
JACKET_SH = (0.850, 0.430, 0.450, 1)
WHITE     = (0.960, 0.955, 0.950, 1)
WHITE_SH  = (0.800, 0.790, 0.800, 1)
SKIRT     = (0.180, 0.185, 0.210, 1)
SKIRT_SH  = (0.100, 0.105, 0.130, 1)
SOCK      = (0.090, 0.085, 0.095, 1)
SOCK_SH   = (0.045, 0.042, 0.050, 1)
SHOE      = (0.330, 0.160, 0.090, 1)
SHOE_SH   = (0.200, 0.090, 0.050, 1)
LASH      = (0.230, 0.110, 0.120, 1)
BROW      = (0.800, 0.430, 0.500, 1)
MOUTH     = (0.780, 0.360, 0.360, 1)
CUBE_B    = (0.230, 0.520, 0.880, 1)
CUBE_Y    = (0.980, 0.780, 0.180, 1)
CASE      = (0.080, 0.080, 0.090, 1)

mats = {}
mats['skin'] = cel_material("M_HH_Skin", SKIN, SKIN_SH, thr=0.10,
                            rim=(1.0, 0.93, 0.88, 1), rim_thr=0.72,
                            proj_center=(0, 0.01, 1.45),
                            band=[(1.435, 1.505, -0.02, SKIN_SH, 0.85),   # bang shadow
                                  (1.285, 1.345, 0.10, SKIN_SH, 0.80)])   # chin->neck shadow
mats['hair'] = cel_material("M_HH_Hair", HAIR, HAIR_SH, thr=0.05,
                            rim=(1.0, 0.82, 0.88, 1), rim_thr=0.70,
                            angel=(1.455, 1.492, HAIR_HI))
mats['jacket'] = cel_material("M_HH_Jacket", JACKET, JACKET_SH, thr=0.12,
                              rim=(1.0, 0.85, 0.84, 1), rim_thr=0.72)
mats['white'] = cel_material("M_HH_White", WHITE, WHITE_SH, thr=0.12,
                             rim=(1, 1, 1, 1), rim_thr=0.75)
mats['skirt'] = cel_material("M_HH_Skirt", SKIRT, SKIRT_SH, thr=0.10,
                             rim=(0.55, 0.57, 0.68, 1), rim_thr=0.70)
mats['sock'] = cel_material("M_HH_Sock", SOCK, SOCK_SH, thr=0.05,
                            rim=(0.45, 0.44, 0.50, 1), rim_thr=0.68)
mats['shoe'] = cel_material("M_HH_Shoe", SHOE, SHOE_SH, thr=0.10,
                            rim=(0.85, 0.65, 0.5, 1), rim_thr=0.72)
mats['lash'] = cel_material("M_HH_Lash", LASH, (0.15, 0.07, 0.08, 1), thr=-1.0,
                            rim=(0, 0, 0, 1), rim_thr=1.1)
mats['brow'] = cel_material("M_HH_Brow", BROW, (0.65, 0.32, 0.40, 1), thr=-1.0,
                            rim=(0, 0, 0, 1), rim_thr=1.1)
mats['mouth'] = cel_material("M_HH_Mouth", MOUTH, MOUTH, thr=-1.0,
                             rim=(0, 0, 0, 1), rim_thr=1.1)
# eyes: flat anime iris, vertical gradient dark-top -> light-bottom
m_iris, nt = new_mat("M_HH_Iris")
nn, ll = nt.nodes, nt.links
o = nn.new("ShaderNodeOutputMaterial"); o.location = (600, 0)
e = nn.new("ShaderNodeEmission"); e.location = (400, 0)
tc = nn.new("ShaderNodeTexCoord"); tc.location = (-400, 0)
sp = nn.new("ShaderNodeSeparateXYZ"); sp.location = (-220, 0)
ll.new(tc.outputs["Object"], sp.inputs[0])
mr = nn.new("ShaderNodeMapRange"); mr.location = (-40, 0)
mr.inputs["From Min"].default_value = -0.012
mr.inputs["From Max"].default_value = 0.012
ll.new(sp.outputs["Z"], mr.inputs["Value"])
cr = nn.new("ShaderNodeValToRGB"); cr.location = (140, 0)
cr.color_ramp.interpolation = 'EASE'
cr.color_ramp.elements[0].position = 0.0
cr.color_ramp.elements[0].color = (0.45, 0.78, 0.95, 1)   # light azure bottom
cr.color_ramp.elements[1].position = 1.0
cr.color_ramp.elements[1].color = (0.10, 0.28, 0.58, 1)   # deep blue top
ll.new(mr.outputs[0], cr.inputs[0])
ll.new(cr.outputs["Color"], e.inputs["Color"])
ll.new(e.outputs[0], o.inputs["Surface"])
mats['iris'] = m_iris

mats['pupil'] = cel_material("M_HH_Pupil", (0.02, 0.05, 0.15, 1),
                             (0.02, 0.05, 0.15, 1), thr=-1.0,
                             rim=(0, 0, 0, 1), rim_thr=1.1)
mats['hl'] = cel_material("M_HH_Highlight", (1, 1, 1, 1), (1, 1, 1, 1),
                          thr=-1.0, rim=(1, 1, 1, 1), rim_thr=1.1)
mats['sclera'] = cel_material("M_HH_Sclera", (0.99, 0.985, 0.98, 1),
                              (0.88, 0.87, 0.90, 1), thr=0.0,
                              rim=(1, 1, 1, 1), rim_thr=1.1)
mats['cube_b'] = cel_material("M_HH_CubeBlue", CUBE_B, (0.13, 0.33, 0.65, 1),
                              thr=0.1, rim=(0.7, 0.85, 1, 1), rim_thr=0.72)
mats['cube_y'] = cel_material("M_HH_CubeYellow", CUBE_Y, (0.80, 0.58, 0.10, 1),
                              thr=0.1, rim=(1, 0.95, 0.7, 1), rim_thr=0.72)
mats['case'] = cel_material("M_HH_Case", CASE, (0.03, 0.03, 0.035, 1),
                            thr=0.05, rim=(0.45, 0.45, 0.5, 1), rim_thr=0.68)

# ---------------------------------------------------------------- render set
sc.render.engine = 'CYCLES'
sc.cycles.device = 'CPU'
sc.cycles.samples = 6
sc.cycles.use_denoising = False
sc.render.resolution_x = 1000
sc.render.resolution_y = 1400
sc.render.film_transparent = False
sc.view_settings.view_transform = 'Standard'
sc.view_settings.look = 'None'

# freestyle ink lines
sc.render.use_freestyle = True
sc.render.line_thickness_mode = 'ABSOLUTE'
sc.render.line_thickness = 2.3
vl = sc.view_layers[0]
vl.use_freestyle = True
fs = vl.freestyle_settings
for ls in list(fs.linesets):
    fs.linesets.remove(ls)
ls = fs.linesets.new("HH_Lines")
ls.select_silhouette = True
ls.select_border = True
ls.select_crease = True
ls.select_material_boundary = False
ls.linestyle.color = (0.09, 0.05, 0.07)
ls.linestyle.thickness = 2.3
fs.crease_angle = 2.35  # ~135 deg

# ---------------------------------------------------------------- cameras
import math
def add_cam(name, loc, look_at):
    from mathutils import Vector
    cd = bpy.data.cameras.new(name)
    ob = bpy.data.objects.new(name, cd)
    bpy.data.collections["CH_HH_SCENE"].objects.link(ob)
    ob.location = loc
    d = (Vector(look_at) - Vector(loc)).normalized()
    ob.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    cd.lens = 85
    cd.sensor_width = 36
    return ob

cams = {}
cams['front'] = add_cam("CAM_front", (0, -4.6, 1.02), (0, 0, 0.86))
cams['q34']   = add_cam("CAM_34",   (-2.9, -3.5, 1.35), (0, 0, 0.88))
cams['side']  = add_cam("CAM_side", (-4.6, -0.35, 1.05), (0, 0, 0.86))
cams['back']  = add_cam("CAM_back", (1.6, 4.3, 1.25), (0, 0, 0.88))
cams['face']  = add_cam("CAM_face", (-0.42, -1.05, 1.44), (0, -0.02, 1.415))
cams['face'].data.lens = 100
sc.camera = cams['q34']

# soft fake contact shadow disc under the feet
me = bpy.data.meshes.new("HH_GroundShadow")
verts, faces = [], []
import math as _m
N = 32
for j in range(N):
    t = j / N * 2 * _m.pi
    verts.append((0.21 * _m.cos(t), 0.15 * _m.sin(t), 0.001))
verts.append((0, 0, 0.001))
for j in range(N):
    faces.append((N, j, (j + 1) % N))
me.from_pydata(verts, [], faces)
gs = bpy.data.objects.new("HH_GroundShadow", me)
bpy.data.collections["CH_HH_SCENE"].objects.link(gs)
mg = bpy.data.materials.new("M_HH_GroundShadow")
mg.use_nodes = True
ntg = mg.node_tree
ntg.nodes.clear()
og = ntg.nodes.new("ShaderNodeOutputMaterial")
tr = ntg.nodes.new("ShaderNodeBsdfTransparent")
mixsh = ntg.nodes.new("ShaderNodeMixShader")
dif = ntg.nodes.new("ShaderNodeBackground")
dif.inputs[0].default_value = (0.35, 0.33, 0.36, 1)
tcg = ntg.nodes.new("ShaderNodeTexCoord")
grad = ntg.nodes.new("ShaderNodeVectorMath"); grad.operation = 'LENGTH'
rampg = ntg.nodes.new("ShaderNodeMapRange")
rampg.inputs["From Min"].default_value = 0.15
rampg.inputs["From Max"].default_value = 1.0
rampg.inputs["To Min"].default_value = 0.38
rampg.inputs["To Max"].default_value = 0.0
ntg.links.new(tcg.outputs["Object"], grad.inputs[0])
ntg.links.new(grad.outputs["Value"], rampg.inputs["Value"])
ntg.links.new(rampg.outputs[0], mixsh.inputs["Fac"])
ntg.links.new(tr.outputs[0], mixsh.inputs[1])
ntg.links.new(dif.outputs[0], mixsh.inputs[2])
ntg.links.new(mixsh.outputs[0], og.inputs["Surface"])
mg.blend_method = 'BLEND'
gs.data.materials.append(mg)
gs.visible_shadow = False

print("step_00 done. materials:", len(bpy.data.materials), "cams:", len(cams))
