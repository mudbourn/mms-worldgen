use crate::beard::Beardifier;
use crate::cell::{CellPlan, Lanes};
use crate::mth;
use crate::tree::{Hooks, MarkerKind, Memo, Node, Tree};
use std::collections::HashMap;
use std::hash::{BuildHasherDefault, Hasher};

pub const ROOT_BARRIER: usize = 0;
pub const ROOT_FLOODEDNESS: usize = 1;
pub const ROOT_SPREAD: usize = 2;
pub const ROOT_LAVA: usize = 3;
pub const ROOT_EROSION: usize = 7;
pub const ROOT_DEPTH: usize = 8;
pub const ROOT_PRELIMINARY_SURFACE: usize = 10;
pub const ROOT_FINAL_DENSITY: usize = 11;
pub const ROOT_VEIN_TOGGLE: usize = 12;
pub const ROOT_VEIN_RIDGED: usize = 13;
pub const ROOT_VEIN_GAP: usize = 14;

const NO_INDEX: usize = usize::MAX;

// Cell geometry of one chunk as NoiseChunk derives it.
#[derive(Clone, Copy, Debug)]
pub struct Layout {
    pub cell_width: i32,
    pub cell_height: i32,
    pub cell_count_xz: i32,
    pub cell_count_y: i32,
    pub cell_noise_min_y: i32,
    pub first_cell_x: i32,
    pub first_cell_z: i32,
    pub first_noise_x: i32,
    pub first_noise_z: i32,
    pub noise_size_xz: i32,
}

impl Layout {
    // Builds the layout for a chunk at block min x and z with clamped noise min y and height.
    pub fn new(
        size_horizontal: i32,
        size_vertical: i32,
        min_x: i32,
        min_z: i32,
        min_y: i32,
        height: i32,
    ) -> Layout {
        let cell_width = size_horizontal * 4;
        let cell_height = size_vertical * 4;
        let cell_count_xz = 16 / cell_width;
        Layout {
            cell_width,
            cell_height,
            cell_count_xz,
            cell_count_y: height.div_euclid(cell_height),
            cell_noise_min_y: min_y.div_euclid(cell_height),
            first_cell_x: min_x.div_euclid(cell_width),
            first_cell_z: min_z.div_euclid(cell_width),
            first_noise_x: min_x >> 2,
            first_noise_z: min_z >> 2,
            noise_size_xz: (cell_count_xz * cell_width) >> 2,
        }
    }

    fn slice_len(&self) -> usize {
        ((self.cell_count_xz + 1) * (self.cell_count_y + 1)) as usize
    }

    fn slice_index(&self, z: i32, y: i32) -> usize {
        (z * (self.cell_count_y + 1) + y) as usize
    }

    fn cell_len(&self) -> usize {
        (self.cell_width * self.cell_width * self.cell_height) as usize
    }
}

// Stage versions that tell an interpolator which of its lerp results are out of date.
#[derive(Default)]
struct Fractions {
    y: f64,
    x: f64,
    z: f64,
    versions: [u64; 3],
    tick: u64,
}

impl Fractions {
    fn bump(&mut self, from: usize) {
        self.tick += 1;
        for version in &mut self.versions[from..] {
            *version = self.tick;
        }
    }
}

// One NoiseInterpolator: two corner slices and the staged interpolation state.
struct Interpolator {
    input: usize,
    slice0: Vec<f64>,
    slice1: Vec<f64>,
    noise: [f64; 8],
    value_xz: [f64; 4],
    value_z: [f64; 2],
    value: f64,
    seen: [u64; 3],
    cell_ready: bool,
}

impl Interpolator {
    fn new(input: usize, layout: &Layout, cell_ready: bool) -> Interpolator {
        Interpolator {
            input,
            slice0: vec![0.0; layout.slice_len()],
            slice1: vec![0.0; layout.slice_len()],
            noise: [0.0; 8],
            value_xz: [0.0; 4],
            value_z: [0.0; 2],
            value: 0.0,
            seen: [0; 3],
            cell_ready,
        }
    }

    // Selects the eight corners of cell y and z; indices are n000 n001 n100 n101 n010 n011 n110 n111.
    fn select_cell_yz(&mut self, layout: &Layout, y: i32, z: i32) {
        self.noise = [
            self.slice0[layout.slice_index(z, y)],
            self.slice0[layout.slice_index(z + 1, y)],
            self.slice1[layout.slice_index(z, y)],
            self.slice1[layout.slice_index(z + 1, y)],
            self.slice0[layout.slice_index(z, y + 1)],
            self.slice0[layout.slice_index(z + 1, y + 1)],
            self.slice1[layout.slice_index(z, y + 1)],
            self.slice1[layout.slice_index(z + 1, y + 1)],
        ];
    }

    // The staged value at the current fractions, recomputing only the stale stages.
    fn staged(&mut self, f: &Fractions) -> f64 {
        if self.seen[0] != f.versions[0] {
            let n = &self.noise;
            self.value_xz = [
                mth::lerp(f.y, n[0], n[4]),
                mth::lerp(f.y, n[2], n[6]),
                mth::lerp(f.y, n[1], n[5]),
                mth::lerp(f.y, n[3], n[7]),
            ];
            self.seen[0] = f.versions[0];
        }
        if self.seen[1] != f.versions[1] {
            self.value_z = [
                mth::lerp(f.x, self.value_xz[0], self.value_xz[1]),
                mth::lerp(f.x, self.value_xz[2], self.value_xz[3]),
            ];
            self.seen[1] = f.versions[1];
        }
        if self.seen[2] != f.versions[2] {
            self.value = mth::lerp(f.z, self.value_z[0], self.value_z[1]);
            self.seen[2] = f.versions[2];
        }
        self.value
    }

    fn cell_value(&self, x: f64, y: f64, z: f64) -> f64 {
        let n = &self.noise;
        mth::lerp3(x, y, z, n[0], n[2], n[4], n[6], n[1], n[3], n[5], n[7])
    }

    // Fills the lerp3 value of every block of the selected cell in cell cache order.
    fn fill_cell(&self, layout: &Layout, fx: &[f64], fy: &[f64], fz: &[f64], out: &mut [f64]) {
        match layout.cell_width {
            4 => self.fill_cell_width::<4>(fx, fy, fz, out),
            8 => self.fill_cell_width::<8>(fx, fy, fz, out),
            _ => self.fill_cell_width::<0>(fx, fy, fz, out),
        }
    }

    // Cell fill with the width known at compile time, or runtime when W is 0.
    fn fill_cell_width<const W: usize>(&self, fx: &[f64], fy: &[f64], fz: &[f64], out: &mut [f64]) {
        let n = self.noise;
        let width = if W == 0 { fz.len() } else { W };
        let height = fy.len();
        for (cx, x) in fx.iter().enumerate() {
            let a = mth::lerp(*x, n[0], n[2]);
            let b = mth::lerp(*x, n[4], n[6]);
            let c = mth::lerp(*x, n[1], n[3]);
            let d = mth::lerp(*x, n[5], n[7]);
            for (cy, y) in fy.iter().enumerate() {
                let near = mth::lerp(*y, a, b);
                let far = mth::lerp(*y, c, d);
                let row = ((height - 1 - cy) * width + cx) * width;
                for cz in 0..width {
                    out[row + cz] = mth::lerp(fz[cz], near, far);
                }
            }
        }
    }

    // The smallest and largest corner of the selected cell, or NaN when a corner is NaN.
    fn corner_range(&self) -> (f64, f64) {
        let mut low = f64::INFINITY;
        let mut high = f64::NEG_INFINITY;
        for v in self.noise {
            if v.is_nan() {
                return (f64::NAN, f64::NAN);
            }
            low = low.min(v);
            high = high.max(v);
        }
        (low, high)
    }

    fn swap_slices(&mut self) {
        std::mem::swap(&mut self.slice0, &mut self.slice1);
    }
}

// Cell cache filled for every block of the selected cell, from a tree node or the final density.
struct CellCache {
    input: Option<usize>,
    values: Vec<f64>,
}

// Multiplicative hasher for the two int column keys.
#[derive(Default)]
struct ColumnHasher(u64);

impl Hasher for ColumnHasher {
    fn finish(&self) -> u64 {
        self.0
    }

    fn write(&mut self, bytes: &[u8]) {
        for byte in bytes {
            self.0 = (self.0.rotate_left(5) ^ *byte as u64).wrapping_mul(0x517cc1b727220a95);
        }
    }

    fn write_i32(&mut self, value: i32) {
        self.0 = (self.0.rotate_left(5) ^ value as u32 as u64).wrapping_mul(0x517cc1b727220a95);
    }
}

fn fractions(count: i32) -> Vec<f64> {
    (0..count).map(|i| i as f64 / count as f64).collect()
}

// What the density bounds prove about a selected cell.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum CellState {
    Empty,
    Solid,
    Mixed,
}

// Evaluation state of one chunk that stands in for vanilla's NoiseChunk.
pub struct Eval<'a> {
    layout: Layout,
    beard: &'a Beardifier,
    in_chunk: bool,
    filling_cell: bool,
    cell_start: [i32; 3],
    in_cell: [i32; 3],
    flat: Vec<Option<Vec<f64>>>,
    column: Vec<Option<(i32, i32, f64)>>,
    interp_of: Vec<usize>,
    cell_of: Vec<usize>,
    interpolators: Vec<Interpolator>,
    cell_caches: Vec<CellCache>,
    root_interp: Vec<usize>,
    corner_ranges: Vec<(f64, f64)>,
    bounds: Vec<(f64, f64)>,
    cells: Vec<Vec<f64>>,
    plan_regs: Vec<f64>,
    lane_offsets: Vec<[i32; 3]>,
    lane_xs: Vec<i32>,
    lane_ys: Vec<i32>,
    lane_zs: Vec<i32>,
    layer_ys: Vec<i32>,
    layer_of: Vec<usize>,
    fractions: Fractions,
    frac_x: Vec<f64>,
    frac_y: Vec<f64>,
    frac_z: Vec<f64>,
    surface_cache: HashMap<(i32, i32), i32, BuildHasherDefault<ColumnHasher>>,
    memo: Memo,
}

impl<'a> Eval<'a> {
    // Wraps the markers of a tree for one chunk; the last cell cache is the final density plus beardifier.
    pub fn new(tree: &Tree, layout: Layout, beard: &'a Beardifier) -> Eval<'a> {
        let mut interp_of = vec![NO_INDEX; tree.node_count()];
        let mut cell_of = vec![NO_INDEX; tree.node_count()];
        let mut interpolators = Vec::new();
        let mut cell_caches = Vec::new();
        let markers = tree.markers();
        let mut cell_roots = vec![tree.root_node(ROOT_FINAL_DENSITY)];
        cell_roots.extend(
            markers
                .iter()
                .filter(|m| m.2 == MarkerKind::CacheAllInCell)
                .map(|m| m.1),
        );
        let in_cell = tree.reachable_outside_interpolated(&cell_roots);
        for (id, input, kind) in markers {
            match kind {
                MarkerKind::Interpolated => {
                    interp_of[id] = interpolators.len();
                    interpolators.push(Interpolator::new(input, &layout, in_cell[id]));
                }
                MarkerKind::CacheAllInCell => {
                    cell_of[id] = cell_caches.len();
                    cell_caches.push(CellCache {
                        input: Some(input),
                        values: vec![0.0; layout.cell_len()],
                    });
                }
                _ => {}
            }
        }
        cell_caches.push(CellCache {
            input: None,
            values: vec![0.0; layout.cell_len()],
        });
        let root_interp = (0..tree.root_count())
            .map(|root| match tree.node(tree.root_node(root)) {
                Node::Marker {
                    kind: MarkerKind::Interpolated,
                    ..
                } => interp_of[tree.root_node(root)],
                _ => NO_INDEX,
            })
            .collect();
        let cells = interpolators
            .iter()
            .map(|i| {
                if i.cell_ready {
                    vec![0.0; layout.cell_len()]
                } else {
                    Vec::new()
                }
            })
            .collect();
        let interpolators_len = interpolators.len();
        let lanes = layout.cell_len();
        let lane_offsets: Vec<[i32; 3]> = (0..lanes as i32)
            .map(|index| {
                let k = index / layout.cell_width;
                [
                    k % layout.cell_width,
                    layout.cell_height - 1 - k / layout.cell_width,
                    index % layout.cell_width,
                ]
            })
            .collect();
        let mut plan_regs = Vec::new();
        if let Some(plan) = tree.cell_plan(ROOT_FINAL_DENSITY) {
            plan_regs = vec![0.0; plan.register_count() * lanes];
            plan.init(&mut plan_regs, lanes);
        }
        let layer_of = lane_offsets.iter().map(|o| o[1] as usize).collect();
        Eval {
            layout,
            beard,
            in_chunk: false,
            filling_cell: false,
            cell_start: [0; 3],
            in_cell: [0; 3],
            flat: vec![None; tree.node_count()],
            column: vec![None; tree.node_count()],
            interp_of,
            cell_of,
            interpolators,
            cell_caches,
            root_interp,
            corner_ranges: vec![(0.0, 0.0); interpolators_len],
            bounds: Vec::new(),
            cells,
            plan_regs,
            lane_offsets,
            lane_xs: Vec::new(),
            lane_ys: Vec::new(),
            lane_zs: Vec::new(),
            layer_ys: Vec::new(),
            layer_of,
            fractions: Fractions::default(),
            frac_x: fractions(layout.cell_width),
            frac_y: fractions(layout.cell_height),
            frac_z: fractions(layout.cell_width),
            surface_cache: HashMap::default(),
            memo: Memo::new(tree.node_count()),
        }
    }

    // Evaluates a router field with the chunk itself as the function context.
    pub fn chunk(&mut self, tree: &Tree, root: usize, x: i32, y: i32, z: i32) -> f64 {
        let direct = self.root_interp[root];
        if direct != NO_INDEX && !self.filling_cell {
            return self.interpolators[direct].staged(&self.fractions);
        }
        let saved = self.in_chunk;
        self.in_chunk = true;
        let value = tree.compute_with(tree.root_node(root), x, y, z, self);
        self.in_chunk = saved;
        value
    }

    // Evaluates a router field with a single point as the function context.
    pub fn point(&mut self, tree: &Tree, root: usize, x: i32, y: i32, z: i32) -> f64 {
        let saved = self.in_chunk;
        self.in_chunk = false;
        let value = tree.compute_with(tree.root_node(root), x, y, z, self);
        self.in_chunk = saved;
        value
    }

    // NoiseChunk.preliminarySurfaceLevel: the cached surface at the quart-aligned column.
    pub fn preliminary_surface_level(&mut self, tree: &Tree, x: i32, z: i32) -> i32 {
        let qx = (x >> 2) << 2;
        let qz = (z >> 2) << 2;
        if let Some(level) = self.surface_cache.get(&(qx, qz)) {
            return *level;
        }
        let level = mth::floor(self.point(tree, ROOT_PRELIMINARY_SURFACE, qx, 0, qz));
        self.surface_cache.insert((qx, qz), level);
        level
    }

    // NoiseChunk.maxPreliminarySurfaceLevel over a block rectangle in steps of four.
    pub fn max_preliminary_surface_level(
        &mut self,
        tree: &Tree,
        min_x: i32,
        min_z: i32,
        max_x: i32,
        max_z: i32,
    ) -> i32 {
        let mut result = i32::MIN;
        let mut z = min_z;
        while z <= max_z {
            let mut x = min_x;
            while x <= max_x {
                let level = self.preliminary_surface_level(tree, x, z);
                if level > result {
                    result = level;
                }
                x += 4;
            }
            z += 4;
        }
        result
    }

    // Fills one corner slice of every interpolator at the cell column x.
    fn fill_slice(&mut self, tree: &Tree, first: bool, x_cell: i32) {
        let layout = self.layout;
        self.cell_start[0] = x_cell * layout.cell_width;
        self.in_cell[0] = 0;
        self.in_chunk = true;
        for j in 0..layout.cell_count_xz + 1 {
            self.cell_start[2] = (layout.first_cell_z + j) * layout.cell_width;
            self.in_cell[2] = 0;
            for n in 0..self.interpolators.len() {
                let input = self.interpolators[n].input;
                for i in 0..layout.cell_count_y + 1 {
                    self.cell_start[1] = (i + layout.cell_noise_min_y) * layout.cell_height;
                    self.in_cell[1] = 0;
                    let value = tree.compute_with(
                        input,
                        self.cell_start[0],
                        self.cell_start[1],
                        self.cell_start[2],
                        self,
                    );
                    let index = layout.slice_index(j, i);
                    let interpolator = &mut self.interpolators[n];
                    if first {
                        interpolator.slice0[index] = value;
                    } else {
                        interpolator.slice1[index] = value;
                    }
                }
            }
        }
        self.in_chunk = false;
    }

    pub fn initialize_for_first_cell_x(&mut self, tree: &Tree) {
        self.fill_slice(tree, true, self.layout.first_cell_x);
    }

    pub fn advance_cell_x(&mut self, tree: &Tree, i: i32) {
        self.fill_slice(tree, false, self.layout.first_cell_x + i + 1);
        self.cell_start[0] = (self.layout.first_cell_x + i) * self.layout.cell_width;
    }

    // Selects cell y and z and fills the cell caches unless the density bounds settle the cell.
    pub fn select_cell_yz(&mut self, tree: &Tree, y: i32, z: i32, empty_above: i32) -> CellState {
        let layout = self.layout;
        let bottom = (y + layout.cell_noise_min_y) * layout.cell_height;
        for (n, interpolator) in self.interpolators.iter_mut().enumerate() {
            interpolator.select_cell_yz(&layout, y, z);
            self.corner_ranges[n] = interpolator.corner_range();
        }
        self.fractions.bump(0);
        self.cell_start[2] = (layout.first_cell_z + z) * layout.cell_width;
        if self.beard.is_empty() {
            match self.density_range(tree, bottom) {
                Some((_, high)) if high < 0.0 && bottom > empty_above => return CellState::Empty,
                Some((low, _)) if low > 0.0 => return CellState::Solid,
                _ => {}
            }
        }
        for (n, interpolator) in self.interpolators.iter().enumerate() {
            if interpolator.cell_ready {
                interpolator.fill_cell(
                    &layout,
                    &self.frac_x,
                    &self.frac_y,
                    &self.frac_z,
                    &mut self.cells[n],
                );
            }
        }
        self.filling_cell = true;
        self.in_chunk = true;
        self.cell_start[1] = (y + layout.cell_noise_min_y) * layout.cell_height;
        for c in 0..self.cell_caches.len() {
            let input = self.cell_caches[c].input;
            if input.is_none() && self.fill_final_density(tree) {
                continue;
            }
            for index in 0..layout.cell_len() as i32 {
                let cz = index % layout.cell_width;
                let k = index / layout.cell_width;
                let cx = k % layout.cell_width;
                let cy = layout.cell_height - 1 - k / layout.cell_width;
                self.in_cell = [cx, cy, cz];
                let x = self.cell_start[0] + cx;
                let yy = self.cell_start[1] + cy;
                let zz = self.cell_start[2] + cz;
                let value = match input {
                    Some(node) => tree.compute_with(node, x, yy, zz, self),
                    None => {
                        let density =
                            tree.compute_with(tree.root_node(ROOT_FINAL_DENSITY), x, yy, zz, self);
                        density + self.beard.compute(x, yy, zz)
                    }
                };
                self.cell_caches[c].values[index as usize] = value;
            }
        }
        self.filling_cell = false;
        self.in_chunk = false;
        CellState::Mixed
    }

    // Bounds of the final density over the cell starting at a block y, when they can be proven.
    fn density_range(&mut self, tree: &Tree, bottom: i32) -> Option<(f64, f64)> {
        let plan = tree.cell_plan(ROOT_FINAL_DENSITY)?;
        let top = bottom + self.layout.cell_height - 1;
        let x = self.cell_start[0];
        let z = self.cell_start[2];
        let width = self.layout.cell_width - 1;
        plan.root_range(
            tree,
            &mut self.bounds,
            &self.corner_ranges,
            bottom,
            top,
            [x, x + width, z, z + width],
        )
    }

    // Runs a cell plan over the lanes of the selected cell.
    fn run_plan(&mut self, tree: &Tree, plan: &CellPlan) {
        let lanes = self.layout.cell_len();
        let start = self.cell_start;
        self.lane_xs.clear();
        self.lane_ys.clear();
        self.lane_zs.clear();
        for o in &self.lane_offsets {
            self.lane_xs.push(start[0] + o[0]);
            self.lane_ys.push(start[1] + o[1]);
            self.lane_zs.push(start[2] + o[2]);
        }
        self.layer_ys.clear();
        self.layer_ys
            .extend((0..self.layout.cell_height).map(|cy| start[1] + cy));
        let at = Lanes {
            xs: &self.lane_xs,
            ys: &self.lane_ys,
            zs: &self.lane_zs,
            layer_ys: &self.layer_ys,
            layer_of: &self.layer_of,
            columns: (self.layout.cell_width * self.layout.cell_width) as usize,
        };
        plan.run(tree, &mut self.plan_regs, lanes, &at, &self.cells);
    }

    // Fills the final density cache lane-wise when its plan compiled, and reports whether it did.
    fn fill_final_density(&mut self, tree: &Tree) -> bool {
        let Some(plan) = tree.cell_plan(ROOT_FINAL_DENSITY) else {
            return false;
        };
        let lanes = self.layout.cell_len();
        self.run_plan(tree, plan);
        let root = plan.root() * lanes;
        let density = &self.plan_regs[root..root + lanes];
        let Some(cache) = self.cell_caches.last_mut() else {
            return true;
        };
        let values = &mut cache.values;
        if self.beard.is_empty() {
            for (v, d) in values.iter_mut().zip(density) {
                *v = *d + 0.0;
            }
        } else {
            for (l, (v, d)) in values.iter_mut().zip(density).enumerate() {
                let o = self.lane_offsets[l];
                let x = self.cell_start[0] + o[0];
                let z = self.cell_start[2] + o[2];
                *v = *d + self.beard.compute(x, self.lane_ys[l], z);
            }
        }
        true
    }

    pub fn update_for_y(&mut self, y: i32, d: f64) {
        self.in_cell[1] = y - self.cell_start[1];
        self.fractions.y = d;
        self.fractions.bump(0);
    }

    pub fn update_for_x(&mut self, x: i32, d: f64) {
        self.in_cell[0] = x - self.cell_start[0];
        self.fractions.x = d;
        self.fractions.bump(1);
    }

    pub fn update_for_z(&mut self, z: i32, d: f64) {
        self.in_cell[2] = z - self.cell_start[2];
        self.fractions.z = d;
        self.fractions.bump(2);
    }

    pub fn swap_slices(&mut self) {
        for interpolator in &mut self.interpolators {
            interpolator.swap_slices();
        }
    }

    // Moves the final density cache of the selected cell out of the evaluator.
    pub fn take_density(&mut self) -> Vec<f64> {
        self.cell_caches
            .last_mut()
            .map(|c| std::mem::take(&mut c.values))
            .unwrap_or_default()
    }

    pub fn restore_density(&mut self, values: Vec<f64>) {
        if let Some(cache) = self.cell_caches.last_mut() {
            cache.values = values;
        }
    }

    // The final density cache entry of one lane of the selected cell.
    pub fn density_at(&self, index: usize) -> f64 {
        self.cell_caches[self.cell_caches.len() - 1].values[index]
    }

    // Fraction of a block along the cell width, as the block loop feeds it.
    pub fn width_fraction(&self, offset: i32) -> f64 {
        self.frac_x[offset as usize]
    }

    fn flat_cache(&mut self, tree: &Tree, id: usize, input: usize, x: i32, y: i32, z: i32) -> f64 {
        let size = self.layout.noise_size_xz + 1;
        let qx = (x >> 2) - self.layout.first_noise_x;
        let qz = (z >> 2) - self.layout.first_noise_z;
        if qx < 0 || qz < 0 || qx >= size || qz >= size {
            return tree.eval_here(input, x, y, z, self);
        }
        if self.flat[id].is_none() {
            let saved = self.in_chunk;
            self.in_chunk = false;
            let mut values = vec![0.0; (size * size) as usize];
            for i in 0..size {
                let block_x = (self.layout.first_noise_x + i) << 2;
                for l in 0..size {
                    let block_z = (self.layout.first_noise_z + l) << 2;
                    values[(i + l * size) as usize] =
                        tree.compute_with(input, block_x, 0, block_z, self);
                }
            }
            self.in_chunk = saved;
            self.flat[id] = Some(values);
        }
        match &self.flat[id] {
            Some(values) => values[(qx + qz * size) as usize],
            None => 0.0,
        }
    }
}

impl Hooks for Eval<'_> {
    fn memo(&mut self) -> &mut Memo {
        &mut self.memo
    }

    fn marker(
        &mut self,
        tree: &Tree,
        id: usize,
        input: usize,
        kind: MarkerKind,
        x: i32,
        y: i32,
        z: i32,
    ) -> f64 {
        match kind {
            MarkerKind::FlatCache => self.flat_cache(tree, id, input, x, y, z),
            MarkerKind::Cache2d => match self.column[id] {
                Some((cx, cz, value)) if cx == x && cz == z => value,
                _ => {
                    let value = tree.eval_here(input, x, y, z, self);
                    self.column[id] = Some((x, z, value));
                    value
                }
            },
            MarkerKind::Interpolated if self.in_chunk && self.interp_of[id] != NO_INDEX => {
                let interpolator = &mut self.interpolators[self.interp_of[id]];
                if !self.filling_cell {
                    interpolator.staged(&self.fractions)
                } else if interpolator.cell_ready {
                    let layout = &self.layout;
                    let [i, j, k] = self.in_cell;
                    let index = ((layout.cell_height - 1 - j) * layout.cell_width + i)
                        * layout.cell_width
                        + k;
                    self.cells[self.interp_of[id]][index as usize]
                } else {
                    interpolator.cell_value(
                        self.in_cell[0] as f64 / self.layout.cell_width as f64,
                        self.in_cell[1] as f64 / self.layout.cell_height as f64,
                        self.in_cell[2] as f64 / self.layout.cell_width as f64,
                    )
                }
            }
            MarkerKind::CacheAllInCell if self.in_chunk && self.cell_of[id] != NO_INDEX => {
                let [i, j, k] = self.in_cell;
                let layout = self.layout;
                if i >= 0
                    && j >= 0
                    && k >= 0
                    && i < layout.cell_width
                    && j < layout.cell_height
                    && k < layout.cell_width
                {
                    let index = ((layout.cell_height - 1 - j) * layout.cell_width + i)
                        * layout.cell_width
                        + k;
                    self.cell_caches[self.cell_of[id]].values[index as usize]
                } else {
                    tree.eval_here(input, x, y, z, self)
                }
            }
            _ => tree.eval_here(input, x, y, z, self),
        }
    }

    fn beardifier(&mut self, x: i32, y: i32, z: i32) -> f64 {
        self.beard.compute(x, y, z)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tree::{self, Spec, NODE_DOUBLES, NODE_INTS};

    fn node(spec: &mut Spec, ints: [i32; NODE_INTS], doubles: [f64; NODE_DOUBLES]) -> i32 {
        spec.node_ints.extend(ints);
        spec.node_doubles.extend(doubles);
        (spec.node_ints.len() / NODE_INTS) as i32 - 1
    }

    fn wavy_tree(kind: i32) -> Tree {
        let mut spec = Spec::default();
        let gradient = node(&mut spec, [tree::Y_GRADIENT, -64, 320, 0, 0], [1.0, -1.0]);
        let marker = node(&mut spec, [tree::MARKER, gradient, kind, 0, 0], [0.0, 0.0]);
        spec.roots = vec![marker; 15];
        Tree::build(&spec).unwrap()
    }

    fn fractions_of(count: i32) -> Vec<f64> {
        fractions(count)
    }

    fn layout() -> Layout {
        Layout::new(1, 2, 32, -16, -64, 384)
    }

    #[test]
    fn layout_follows_noise_chunk() {
        let l = layout();
        assert_eq!(l.cell_width, 4);
        assert_eq!(l.cell_height, 8);
        assert_eq!(l.cell_count_xz, 4);
        assert_eq!(l.cell_count_y, 48);
        assert_eq!(l.cell_noise_min_y, -8);
        assert_eq!(l.first_cell_x, 8);
        assert_eq!(l.first_cell_z, -4);
        assert_eq!(l.first_noise_x, 8);
        assert_eq!(l.noise_size_xz, 4);
    }

    #[test]
    fn interpolator_stages_match_manual_lerps() {
        let layout = layout();
        let mut interpolator = Interpolator::new(0, &layout, false);
        for (i, v) in interpolator.slice0.iter_mut().enumerate() {
            *v = (i as f64 * 0.37).sin();
        }
        for (i, v) in interpolator.slice1.iter_mut().enumerate() {
            *v = (i as f64 * 0.11).cos();
        }
        interpolator.select_cell_yz(&layout, 3, 2);
        let n = interpolator.noise;
        let mut fractions = Fractions::default();
        fractions.bump(0);
        fractions.y = 0.375;
        fractions.x = 0.25;
        fractions.z = 0.5;
        interpolator.staged(&fractions);
        let z0 = mth::lerp(
            0.25,
            mth::lerp(0.375, n[0], n[4]),
            mth::lerp(0.375, n[2], n[6]),
        );
        let z1 = mth::lerp(
            0.25,
            mth::lerp(0.375, n[1], n[5]),
            mth::lerp(0.375, n[3], n[7]),
        );
        assert_eq!(interpolator.value, mth::lerp(0.5, z0, z1));
        let mut ready = Interpolator::new(0, &layout, true);
        ready.noise = n;
        let mut cell = vec![0.0; layout.cell_len()];
        ready.fill_cell(
            &layout,
            &fractions_of(4),
            &fractions_of(8),
            &fractions_of(4),
            &mut cell,
        );
        let index = ((8 - 1 - 3) * 4 + 1) * 4 + 2;
        assert_eq!(cell[index], ready.cell_value(0.25, 0.375, 0.5));
        let direct = interpolator.cell_value(0.25, 0.375, 0.5);
        assert!((direct - interpolator.value).abs() < 1e-12);
    }

    #[test]
    fn corners_come_from_the_wrapped_function_at_cell_starts() {
        let beard = Beardifier::default();
        let tree = wavy_tree(0);
        let mut eval = Eval::new(&tree, layout(), &beard);
        eval.initialize_for_first_cell_x(&tree);
        let interpolator = &eval.interpolators[0];
        let l = layout();
        let y = (5 + l.cell_noise_min_y) * l.cell_height;
        let expected = tree.compute(0, 32, y, -16 + 8);
        assert_eq!(interpolator.slice0[l.slice_index(2, 5)], expected);
    }

    #[test]
    fn interpolated_density_reproduces_a_linear_function() {
        let beard = Beardifier::default();
        let tree = wavy_tree(0);
        let l = layout();
        let mut eval = Eval::new(&tree, l, &beard);
        eval.initialize_for_first_cell_x(&tree);
        eval.advance_cell_x(&tree, 0);
        eval.select_cell_yz(&tree, 40, 1, i32::MAX);
        let y = (40 + l.cell_noise_min_y) * l.cell_height + 5;
        eval.update_for_y(y, 5.0 / 8.0);
        eval.update_for_x(33, 0.25);
        eval.update_for_z(-16 + 4 + 2, 0.5);
        let expected = tree.compute(0, 33, y, -16 + 4 + 2);
        let lane = ((l.cell_height - 1 - 5) * l.cell_width + 1) * l.cell_width + 2;
        assert!((eval.density_at(lane as usize) - expected).abs() < 1e-12);
    }

    #[test]
    fn flat_cache_reads_quart_columns_at_y_zero() {
        let beard = Beardifier::default();
        let mut spec = Spec::default();
        let gradient = node(&mut spec, [tree::Y_GRADIENT, 0, 100, 0, 0], [0.0, 100.0]);
        let marker = node(&mut spec, [tree::MARKER, gradient, 1, 0, 0], [0.0, 0.0]);
        spec.roots = vec![marker; 15];
        let tree = Tree::build(&spec).unwrap();
        let mut eval = Eval::new(&tree, layout(), &beard);
        assert_eq!(eval.point(&tree, 0, 33, 50, -15), 0.0);
        assert_eq!(eval.point(&tree, 0, 500, 50, 500), 50.0);
    }

    #[test]
    fn interpolated_marker_outside_the_chunk_context_evaluates_directly() {
        let beard = Beardifier::default();
        let tree = wavy_tree(0);
        let mut eval = Eval::new(&tree, layout(), &beard);
        let value = eval.point(&tree, 0, 1, 100, 1);
        assert_eq!(value, tree.compute(0, 1, 100, 1));
    }

    #[test]
    fn density_bounds_enclose_every_lane() {
        let tree = Tree::build(&crate::fixture::spec(3)).unwrap();
        let beard = Beardifier::default();
        let l = Layout::new(1, 2, 0, 0, -64, 384);
        let mut eval = Eval::new(&tree, l, &beard);
        let plan = tree.cell_plan(ROOT_FINAL_DENSITY).unwrap();
        let lanes = l.cell_len();
        let mut state = 99u64;
        let mut next = || {
            state = state
                .wrapping_mul(6364136223846793005)
                .wrapping_add(1442695040888963407);
            (state >> 11) as f64 / (1u64 << 53) as f64
        };
        let mut proven = 0;
        for trial in 0..3000 {
            let scale = [0.01, 0.5, 2.0, 40.0][trial % 4];
            let shift = [-1.0, 0.0, 1.0][trial % 3] * scale;
            for n in 0..eval.interpolators.len() {
                if !eval.interpolators[n].cell_ready {
                    continue;
                }
                let base = shift + (next() - 0.5) * scale;
                let mut corners = [0.0; 8];
                for corner in &mut corners {
                    *corner = base + (next() - 0.5) * scale * 0.5;
                }
                eval.interpolators[n].noise = corners;
                eval.corner_ranges[n] = eval.interpolators[n].corner_range();
                let (fx, fy, fz) = (&eval.frac_x, &eval.frac_y, &eval.frac_z);
                eval.interpolators[n].fill_cell(&l, fx, fy, fz, &mut eval.cells[n]);
            }
            let bottom = -64 + (trial as i32 % 48) * 8;
            eval.cell_start = [0, bottom, 0];
            eval.run_plan(&tree, plan);
            let root = plan.root() * lanes;
            let range = eval.density_range(&tree, bottom);
            if let Some((low, high)) = range {
                proven += 1;
                for d in &eval.plan_regs[root..root + lanes] {
                    assert!(*d >= low && *d <= high, "{d} outside {low}..{high}");
                }
            }
        }
        assert!(proven > 1000);
    }
}
