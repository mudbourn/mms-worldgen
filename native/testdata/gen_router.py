import json
import os
import struct
import sys

ROOT = sys.argv[1]
OUT = sys.argv[2]
SETTINGS = sys.argv[3] if len(sys.argv) > 3 else "overworld"
FIELDS = [
    "barrier",
    "fluid_level_floodedness",
    "fluid_level_spread",
    "lava",
    "temperature",
    "vegetation",
    "continents",
    "erosion",
    "depth",
    "ridges",
    "preliminary_surface_level",
    "final_density",
    "vein_toggle",
    "vein_ridged",
    "vein_gap",
]
MARKERS = {
    "interpolated": 0,
    "flat_cache": 1,
    "cache_2d": 2,
    "cache_once": 3,
    "cache_all_in_cell": 4,
}
MAPPED = {
    "abs": 0,
    "square": 1,
    "cube": 2,
    "half_negative": 3,
    "quarter_negative": 4,
    "invert": 5,
    "squeeze": 6,
}


def load(path):
    with open(os.path.join(ROOT, path)) as f:
        return json.load(f)


def bits(v):
    return struct.unpack("<Q", struct.pack("<d", float(v)))[0]


def strip(name):
    return name.split(":", 1)[1]


class Builder:
    def __init__(self):
        self.ints = []
        self.doubles = []
        self.memo = {}
        self.noise_ids = {}
        self.noises = []
        self.blended = []
        self.spline_ints = []
        self.spline_consts = []
        self.point_children = []
        self.point_values = []

    def emit(self, op, a=0, b=0, c=0, d=0, x=0.0, y=0.0):
        self.ints += [op, a, b, c, d]
        self.doubles += [x, y]
        return len(self.ints) // 5 - 1

    def noise(self, name):
        if name not in self.noise_ids:
            data = load("noise/" + strip(name) + ".json")
            self.noise_ids[name] = len(self.noises)
            self.noises.append((name, data["firstOctave"], data["amplitudes"]))
        return self.noise_ids[name]

    def visit(self, f):
        if isinstance(f, (int, float)):
            return self.emit(0, x=float(f))
        if isinstance(f, str):
            if f not in self.memo:
                self.memo[f] = self.visit(load("density_function/" + strip(f) + ".json"))
            return self.memo[f]
        return self.build(f)

    def arg(self, f):
        if isinstance(f, (int, float)):
            return ("c", float(f))
        return ("n", self.visit(f))

    def two(self, op, f1, f2):
        a = f1
        b = f2
        if op in (1, 2):
            if isinstance(a, (int, float)):
                return self.emit(5, self.visit(b), 1 if op == 1 else 0, x=float(a))
            if isinstance(b, (int, float)):
                return self.emit(5, self.visit(a), 1 if op == 1 else 0, x=float(b))
        return self.emit(op, self.visit(a), self.visit(b))

    def build(self, f):
        t = strip(f["type"])
        if t == "add":
            return self.two(1, f["argument1"], f["argument2"])
        if t == "mul":
            return self.two(2, f["argument1"], f["argument2"])
        if t == "min":
            return self.two(3, f["argument1"], f["argument2"])
        if t == "max":
            return self.two(4, f["argument1"], f["argument2"])
        if t in MARKERS:
            return self.emit(8, self.visit(f["argument"]), MARKERS[t])
        if t in MAPPED:
            return self.emit(6, self.visit(f["argument"]), MAPPED[t])
        if t == "clamp":
            return self.emit(7, self.visit(f["input"]), x=f["min"], y=f["max"])
        if t == "noise":
            return self.emit(9, self.noise(f["noise"]), x=f["xz_scale"], y=f["y_scale"])
        if t == "shift_a":
            return self.emit(10, self.noise(f["argument"]))
        if t == "shift_b":
            return self.emit(11, self.noise(f["argument"]))
        if t == "shift":
            return self.emit(12, self.noise(f["argument"]))
        if t == "shifted_noise":
            sx = self.visit(f["shift_x"])
            sy = self.visit(f["shift_y"])
            sz = self.visit(f["shift_z"])
            return self.emit(13, sx, sy, sz, self.noise(f["noise"]), f["xz_scale"], f["y_scale"])
        if t == "weird_scaled_sampler":
            mapper = 1 if f["rarity_value_mapper"] == "type_2" else 0
            return self.emit(14, self.visit(f["input"]), self.noise(f["noise"]), mapper)
        if t == "range_choice":
            i = self.visit(f["input"])
            a = self.visit(f["when_in_range"])
            b = self.visit(f["when_out_of_range"])
            return self.emit(15, i, a, b, 0, f["min_inclusive"], f["max_exclusive"])
        if t == "y_clamped_gradient":
            return self.emit(16, f["from_y"], f["to_y"], 0, 0, f["from_value"], f["to_value"])
        if t == "find_top_surface":
            d = self.visit(f["density"])
            u = self.visit(f["upper_bound"])
            return self.emit(17, d, u, f["lower_bound"], f["cell_height"])
        if t == "blend_alpha":
            return self.emit(18)
        if t == "blend_offset":
            return self.emit(19)
        if t == "blend_density":
            return self.emit(20, self.visit(f["argument"]))
        if t == "beardifier":
            return self.emit(21)
        if t == "old_blended_noise":
            self.blended += [
                f["xz_scale"],
                f["y_scale"],
                f["xz_factor"],
                f["y_factor"],
                f["smear_scale_multiplier"],
            ]
            return self.emit(22, len(self.blended) // 5 - 1)
        if t == "spline":
            return self.emit(23, self.spline(f["spline"]))
        raise Exception("unsupported " + t)

    def spline(self, s):
        if isinstance(s, (int, float)):
            self.spline_ints += [0, 0, 0, 0]
            self.spline_consts.append(float(s))
            return len(self.spline_ints) // 4 - 1
        coordinate = self.visit(s["coordinate"])
        children = [self.spline(p["value"]) for p in s["points"]]
        start = len(self.point_children)
        for p, child in zip(s["points"], children):
            self.point_children.append(child)
            self.point_values.append(p["location"])
            self.point_values.append(p["derivative"])
        self.spline_ints += [1, coordinate, len(children), start]
        self.spline_consts.append(0.0)
        return len(self.spline_ints) // 4 - 1


def main():
    settings = load("noise_settings/" + SETTINGS + ".json")
    router = settings["noise_router"]
    b = Builder()
    roots = [b.visit(router[name]) for name in FIELDS]
    amps = []
    ends = []
    for _, _, a in b.noises:
        amps += a
        ends.append(len(amps))
    lines = [
        "node_ints " + " ".join(map(str, b.ints)),
        "node_doubles " + " ".join("%x" % bits(v) for v in b.doubles),
        "noise_keys " + " ".join(n[0] for n in b.noises),
        "noise_octaves " + " ".join(str(n[1]) for n in b.noises),
        "noise_amp_ends " + " ".join(map(str, ends)),
        "noise_amps " + " ".join("%x" % bits(v) for v in amps),
        "blended " + " ".join("%x" % bits(v) for v in b.blended),
        "spline_ints " + " ".join(map(str, b.spline_ints)),
        "spline_consts " + " ".join("%x" % bits(v) for v in b.spline_consts),
        "point_children " + " ".join(map(str, b.point_children)),
        "point_values " + " ".join("%x" % bits(v) for v in b.point_values),
        "roots " + " ".join(map(str, roots)),
    ]
    with open(OUT, "w") as f:
        f.write("\n".join(lines) + "\n")
    print(len(b.ints) // 5, "nodes", len(b.noises), "noises")


main()
