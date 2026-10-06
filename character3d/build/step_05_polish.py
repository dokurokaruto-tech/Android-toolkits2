"""step_05_polish.py - final scene organisation, metadata, line weight, save."""
import bpy

sc = bpy.context.scene
vl = sc.view_layers[0]
vl.freestyle_settings.linesets[0].linestyle.thickness = 1.9
sc.render.line_thickness = 1.9

# character metadata (industry style custom props)
sc["character"] = "Gotoh Hitori (後藤ひとり) - Bocchi the Rock!"
sc["character_height_m"] = 1.56
sc["pipeline"] = "procedural cel-look build via blender-mcp (bpy 4.5 LTS)"
sc["units"] = "meters, Z-up, character faces -Y"

order = ["CH_HH_BODY", "CH_HH_FACE", "CH_HH_HAIR", "CH_HH_OUTFIT",
         "CH_HH_PROPS", "CH_HH_SCENE"]
for c in order:
    col = bpy.data.collections.get(c)
    if col:
        col.hide_render = False

# make sure every mesh object is smooth-shaded where it matters
for ob in bpy.data.objects:
    if ob.type == 'MESH' and ob.name.startswith("HH_"):
        for p in ob.data.polygons:
            p.use_smooth = True

# stats
tris = 0
dg = bpy.context.evaluated_depsgraph_get()
for ob in bpy.data.objects:
    if ob.type == 'MESH' and ob.name.startswith("HH_"):
        me = ob.evaluated_get(dg).to_mesh()
        tris += sum(len(p.vertices) - 2 for p in me.polygons)
        ob.evaluated_get(dg).to_mesh_clear()
print("objects:", [o.name for o in bpy.data.objects if o.name.startswith('HH_')])
print("approx tris (with modifiers):", tris)

bpy.ops.wm.save_as_mainfile(
    filepath="/home/user/Android-toolkits2/character3d/gotoh_hitori.blend")
print("step_05 done, blend saved")
